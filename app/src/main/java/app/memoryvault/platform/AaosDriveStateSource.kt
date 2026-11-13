package app.memoryvault.platform

import android.content.Context
import android.os.SystemClock
import app.memoryvault.domain.DriveState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class DriveProperty { GEAR, SPEED }
internal interface VehicleListener {
    fun onChange(reading: VehicleReading)
    fun onError()
}

internal interface CarAccess {
    fun isSupported(property: DriveProperty): Boolean
    fun registerUxListener(listener: (UxReading) -> Unit)
    fun unregisterUxListener()
    fun registerPropertyListener(property: DriveProperty, listener: VehicleListener): Boolean
    fun unregisterPropertyListener(listener: VehicleListener)
    fun readUx(): UxReading?
    fun readProperty(property: DriveProperty): VehicleReading?
}

internal interface CarConnectionListener {
    fun onReady(access: CarAccess)
    fun onDisconnected()
    fun onFailure(reason: DriveReadinessReason)
}

internal fun interface CarConnector {
    fun connect(listener: CarConnectionListener): AutoCloseable
}

/**
 * Public AAOS observations only; no transport-health inference or timestamp-age timeout.
 * A short lock owns state. A single I/O queue owns every external call and resource lifetime.
 * Invalidating events publish UNKNOWN immediately; cleanup drains asynchronously, including
 * resources acquired by an already-running registration/connect after invalidation.
 */
class AaosDriveStateSource internal constructor(
    private val connector: CarConnector,
    dispatcher: CoroutineDispatcher,
    private val elapsedRealtimeNanos: () -> Long,
    deadlineDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DriveStateSource {
    constructor(context: Context) : this(
        AndroidCarConnector(context.applicationContext),
        Dispatchers.IO,
        SystemClock::elapsedRealtimeNanos,
    )

    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + dispatcher)
    // Deadline execution cannot queue behind a blocking Car/Binder operation.
    private val deadlineScope = CoroutineScope(SupervisorJob() + deadlineDispatcher)
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)
    private val mutableSnapshot = MutableStateFlow(DriveSnapshot())
    override val snapshot: StateFlow<DriveSnapshot> = mutableSnapshot.asStateFlow()
    private var connection: ConnectionAttempt? = null
    private var connectionEpoch = 0L
    private var session: Session? = null
    private var refreshJob: Job? = null
    private var started = false
    private var closed = false
    private var retryNeeded = false
    private var cleanupFailed = false
    private var pendingCleanups = 0

    init {
        ioScope.launch {
            try {
                for (command in commands) {
                    // Enqueuing is part of a state transition. Wait for its publication to finish
                    // before starting I/O, then release the state lock for the entire external call.
                    synchronized(lock) { Unit }
                    command()
                }
            } finally {
                ioScope.cancel()
            }
        }
    }

    override fun start() = synchronized(lock) {
        if (closed || (started && !retryNeeded)) return@synchronized
        started = true
        retryNeeded = false
        val previousConnection = connection
        val previousSession = detachSessionLocked()
        val current = ConnectionAttempt(++connectionEpoch)
        connection = current
        queueCleanupLocked(previousSession, previousConnection)
        enqueueIo { connect(current) }
        unknownLocked(DriveReadinessReason.CONNECTING)
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        ++connectionEpoch
        val previous = connection
        connection = null
        queueCleanupLocked(detachSessionLocked(), previous)
        // The actor must drain late-acquired resources before its scope is cancelled.
        enqueueIo { commands.close() }
        unknownLocked(DriveReadinessReason.CLOSED)
        deadlineScope.cancel()
    }

    private fun enqueueIo(command: () -> Unit) {
        check(commands.trySend(command).isSuccess)
    }

    private fun isConnectionCurrentLocked(candidate: ConnectionAttempt) =
        started && !closed && connection === candidate && candidate.epoch == connectionEpoch

    private fun isCurrentLocked(candidate: Session) =
        session === candidate && isConnectionCurrentLocked(candidate.connection)

    private fun canInitializeLocked(current: ConnectionAttempt): Boolean {
        if (!isConnectionCurrentLocked(current)) return false
        if (cleanupFailed || pendingCleanups != 0) {
            retryNeeded = true
            unknownLocked(if (cleanupFailed) DriveReadinessReason.CLEANUP_FAILED else DriveReadinessReason.CONNECTING)
            return false
        }
        return true
    }

    private fun connect(current: ConnectionAttempt) {
        if (!synchronized(lock) { canInitializeLocked(current) }) return
        val observer = object : CarConnectionListener {
            override fun onReady(access: CarAccess) = synchronized(lock) {
                if (isConnectionCurrentLocked(current)) {
                    val previous = detachSessionLocked()
                    val next = Session(access, current)
                    session = next
                    retryNeeded = false
                    queueCleanupLocked(previous)
                    enqueueIo { initialize(next) }
                    unknownLocked(DriveReadinessReason.CONNECTING)
                }
            }

            override fun onDisconnected() = synchronized(lock) {
                if (isConnectionCurrentLocked(current)) {
                    queueCleanupLocked(detachSessionLocked())
                    retryNeeded = true
                    unknownLocked(DriveReadinessReason.DISCONNECTED)
                }
            }

            override fun onFailure(reason: DriveReadinessReason) = synchronized(lock) {
                if (isConnectionCurrentLocked(current)) failLocked(reason)
            }
        }
        try {
            // The actor always retains a returned handle, even when close/retry happened in flight.
            current.handle = connector.connect(observer)
        } catch (failure: RuntimeException) {
            connectFailed(current, readFailureReason(failure))
        } catch (_: LinkageError) {
            connectFailed(current, DriveReadinessReason.UNSUPPORTED)
        }
    }

    private fun connectFailed(current: ConnectionAttempt, reason: DriveReadinessReason) = synchronized(lock) {
        if (isConnectionCurrentLocked(current)) {
            ++connectionEpoch
            connection = null
            queueCleanupLocked(detachSessionLocked(), current)
            retryNeeded = true
            unknownLocked(reason)
        }
    }

    private fun initialize(current: Session) {
        if (!synchronized(lock) { isCurrentLocked(current) && canInitializeLocked(current.connection) }) return
        try {
            for (property in DriveProperty.entries) {
                val supported = current.access.isSupported(property)
                if (!synchronized(lock) {
                    if (!isCurrentLocked(current)) false
                    else if (!supported) { failLocked(DriveReadinessReason.UNSUPPORTED); false }
                    else true
                }) return
            }
            // Resource fields belong only to this actor. Invalidation queues cleanup behind the
            // current command, so even an API that allocates after close is fully accounted for.
            current.uxRegistrationAttempted = true
            current.access.registerUxListener { reading ->
                synchronized(lock) {
                    if (isCurrentLocked(current)) {
                        ++current.uxRevision
                        current.ux = reading
                        publishLocked(current)
                    }
                }
            }
            if (!synchronized(lock) { isCurrentLocked(current) }) return
            for (property in DriveProperty.entries) {
                val listener = object : VehicleListener {
                    override fun onChange(reading: VehicleReading) = synchronized(lock) {
                        if (isCurrentLocked(current)) {
                            current.revisions[property] = current.revision(property) + 1
                            acceptReadingLocked(current, property, reading)
                            publishLocked(current)
                        }
                    }

                    override fun onError() = synchronized(lock) {
                        if (isCurrentLocked(current)) failLocked(DriveReadinessReason.PROPERTY_ERROR)
                    }
                }
                current.listeners[property] = listener
                val registered = current.access.registerPropertyListener(property, listener)
                if (!synchronized(lock) {
                    if (!isCurrentLocked(current)) false
                    else if (!registered) { failLocked(DriveReadinessReason.LISTENER_REGISTRATION_FAILED); false }
                    else true
                }) return
            }
        } catch (failure: RuntimeException) {
            synchronized(lock) {
                if (isCurrentLocked(current)) {
                    failLocked(readFailureReason(failure, DriveReadinessReason.LISTENER_REGISTRATION_FAILED))
                }
            }
            return
        } catch (_: LinkageError) {
            synchronized(lock) { if (isCurrentLocked(current)) failLocked(DriveReadinessReason.UNSUPPORTED) }
            return
        }
        readCurrent(current)
        synchronized(lock) {
            if (isCurrentLocked(current)) {
                current.initialized = true
                publishLocked(current)
                if (isCurrentLocked(current)) scheduleRefreshLocked(current)
            }
        }
    }

    private fun readCurrent(current: Session) {
        try {
            val uxRevision = synchronized(lock) {
                if (!isCurrentLocked(current)) return
                current.uxRevision
            }
            val ux = current.access.readUx()
            synchronized(lock) {
                if (!isCurrentLocked(current)) return
                if (uxRevision == current.uxRevision) current.ux = ux
                publishLocked(current)
            }
            for (property in DriveProperty.entries) {
                val revision = synchronized(lock) {
                    if (!isCurrentLocked(current)) return
                    current.revision(property)
                }
                val reading = current.access.readProperty(property)
                synchronized(lock) {
                    if (!isCurrentLocked(current)) return
                    // A newer callback wins over stale good data, but explicit getter failure wins.
                    if ((reading != null && reading.status != VehicleStatus.AVAILABLE) ||
                        revision == current.revision(property)
                    ) acceptReadingLocked(current, property, reading)
                    publishLocked(current)
                }
            }
        } catch (failure: RuntimeException) {
            synchronized(lock) { if (isCurrentLocked(current)) failLocked(readFailureReason(failure)) }
        } catch (_: LinkageError) {
            synchronized(lock) { if (isCurrentLocked(current)) failLocked(DriveReadinessReason.UNSUPPORTED) }
        }
    }

    private fun scheduleRefreshLocked(current: Session) {
        refreshJob = ioScope.launch {
            delay(1_000)
            synchronized(lock) {
                if (isCurrentLocked(current)) enqueueIo {
                    readCurrent(current)
                    synchronized(lock) {
                        if (isCurrentLocked(current)) scheduleRefreshLocked(current)
                    }
                }
            }
        }
    }

    private fun acceptReadingLocked(current: Session, property: DriveProperty, reading: VehicleReading?) {
        if (reading == null) {
            current.readings[property] = null
            return
        }
        when (reading.status) {
            VehicleStatus.UNAVAILABLE -> { failLocked(DriveReadinessReason.PROPERTY_UNAVAILABLE); return }
            VehicleStatus.ERROR -> { failLocked(DriveReadinessReason.PROPERTY_ERROR); return }
            VehicleStatus.AVAILABLE -> Unit
        }
        val previous = current.readings[property]
        if (previous != null && reading.timestampNanos > 0) {
            if (reading.timestampNanos < previous.timestampNanos) return
            if (reading.timestampNanos == previous.timestampNanos && reading.value != previous.value) {
                failLocked(DriveReadinessReason.INVALID_TIMESTAMP)
                return
            }
        }
        current.readings[property] = reading
    }

    private fun publishLocked(current: Session) {
        if (!isCurrentLocked(current)) return
        val now = elapsedRealtimeNanos()
        val evaluated = DriveReadiness.evaluate(
            true, current.ux, current.readings[DriveProperty.GEAR], current.readings[DriveProperty.SPEED],
            now,
        )
        if (!current.initialized) {
            mutableSnapshot.value = if (evaluated.state != DriveState.UNKNOWN) {
                evaluated.copy(state = DriveState.UNKNOWN, readinessReason = DriveReadinessReason.CONNECTING)
            } else evaluated
            return
        }
        if (evaluated.state == DriveState.PARKED || evaluated.state == DriveState.DRIVING) {
            clearTransitionLocked(current)
            current.transitionArmed = true
            current.transitionExpired = false
            mutableSnapshot.value = evaluated
            return
        }
        val candidate = DriveReadiness.isTransitionCandidate(
            current.ux, current.readings[DriveProperty.GEAR], current.readings[DriveProperty.SPEED], now,
        )
        if (candidate && (current.transitionArmed || current.transitionToken != null)) {
            if (current.transitionToken == null) {
                current.transitionArmed = false
                val token = Any()
                current.transitionToken = token
                current.transitionJob = deadlineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    delay(500L)
                    synchronized(lock) {
                        if (isCurrentLocked(current) && current.transitionToken === token) {
                            current.transitionExpired = true
                            clearTransitionLocked(current)
                            unknownLocked(DriveReadinessReason.TRANSITION_TIMEOUT, current.ux)
                        }
                    }
                }
            }
            mutableSnapshot.value = evaluated.copy(state = DriveState.TRANSITION, readinessReason = DriveReadinessReason.TRANSITION)
        } else {
            clearTransitionLocked(current)
            current.transitionArmed = false
            mutableSnapshot.value = if (candidate && current.transitionExpired) {
                evaluated.copy(readinessReason = DriveReadinessReason.TRANSITION_TIMEOUT)
            } else evaluated
        }
    }

    private fun clearTransitionLocked(current: Session) {
        current.transitionToken = null
        current.transitionJob?.cancel()
        current.transitionJob = null
    }

    private fun failLocked(reason: DriveReadinessReason) {
        val ux = session?.ux
        queueCleanupLocked(detachSessionLocked())
        retryNeeded = true
        unknownLocked(reason, ux)
    }

    private fun unknownLocked(reason: DriveReadinessReason, ux: UxReading? = null) {
        mutableSnapshot.value = DriveSnapshot(
            requiresDistractionOptimization = ux?.requiresDistractionOptimization,
            activeRestrictions = ux?.activeRestrictions,
            readinessReason = reason,
            cleanupFailed = cleanupFailed,
        )
    }

    private fun detachSessionLocked(): Session? {
        refreshJob?.cancel()
        refreshJob = null
        val previous = session
        previous?.let(::clearTransitionLocked)
        session = null
        return previous
    }

    private fun queueCleanupLocked(previousSession: Session?, previousConnection: ConnectionAttempt? = null) {
        val cleanupSession = previousSession?.takeUnless { it.cleanupScheduled }
        val cleanupConnection = previousConnection?.takeUnless { it.cleanupScheduled }
        if (cleanupSession == null && cleanupConnection == null) return
        cleanupSession?.cleanupScheduled = true
        cleanupConnection?.cleanupScheduled = true
        ++pendingCleanups
        enqueueIo {
            var failed = false
            if (cleanupSession != null) {
                for (listener in cleanupSession.listeners.values.toList().asReversed()) {
                    if (!cleanup { cleanupSession.access.unregisterPropertyListener(listener) }) failed = true
                }
                if (cleanupSession.uxRegistrationAttempted &&
                    !cleanup { cleanupSession.access.unregisterUxListener() }
                ) failed = true
            }
            cleanupConnection?.handle?.let { if (!cleanup { it.close() }) failed = true }
            synchronized(lock) {
                --pendingCleanups
                if (failed) {
                    cleanupFailed = true
                    retryNeeded = true
                    val active = detachSessionLocked()
                    queueCleanupLocked(active)
                    if (closed) unknownLocked(DriveReadinessReason.CLOSED)
                    else if (active != null) unknownLocked(DriveReadinessReason.CLEANUP_FAILED)
                    else mutableSnapshot.value = mutableSnapshot.value.copy(cleanupFailed = true)
                }
            }
        }
    }

    private inline fun cleanup(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private class ConnectionAttempt(val epoch: Long) {
        // Handle is I/O-actor owned; scheduling metadata is state-lock owned.
        var handle: AutoCloseable? = null
        var cleanupScheduled = false
    }

    private class Session(val access: CarAccess, val connection: ConnectionAttempt) {
        // Input/identity fields are state-lock owned.
        var initialized = false
        var ux: UxReading? = null
        var uxRevision = 0L
        val readings = mutableMapOf<DriveProperty, VehicleReading?>()
        val revisions = mutableMapOf<DriveProperty, Long>()
        fun revision(property: DriveProperty) = revisions[property] ?: 0L
        var cleanupScheduled = false
        var transitionArmed = false
        var transitionExpired = false
        var transitionToken: Any? = null
        var transitionJob: Job? = null
        // Only the I/O actor reads/writes registration resources.
        var uxRegistrationAttempted = false
        val listeners = linkedMapOf<DriveProperty, VehicleListener>()
    }
}

internal class CarReadFailure(val reason: DriveReadinessReason) : RuntimeException()

internal fun readFailureReason(
    failure: RuntimeException,
    fallback: DriveReadinessReason = DriveReadinessReason.READ_FAILED,
): DriveReadinessReason = when (failure) {
    is CarReadFailure -> failure.reason
    is SecurityException -> DriveReadinessReason.PERMISSION_DENIED
    is UnsupportedOperationException, is IllegalArgumentException -> DriveReadinessReason.UNSUPPORTED
    else -> fallback
}
