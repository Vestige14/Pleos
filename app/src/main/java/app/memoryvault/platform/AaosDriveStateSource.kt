package app.memoryvault.platform

import android.content.Context
import android.os.SystemClock
import app.memoryvault.domain.DriveState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
 * Public AAOS observations only. No browser/transport-health inference or timestamp-age timeout.
 * All reads, callbacks and lifetime changes share one lock; each connection gets fresh inputs.
 * Read/status/registration failures latch UNKNOWN until a new connection or explicit start retry.
 */
class AaosDriveStateSource internal constructor(
    private val connector: CarConnector,
    private val dispatcher: CoroutineDispatcher,
    private val elapsedRealtimeNanos: () -> Long,
) : DriveStateSource {
    constructor(context: Context) : this(
        AndroidCarConnector(context.applicationContext),
        Dispatchers.IO,
        SystemClock::elapsedRealtimeNanos,
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableSnapshot = MutableStateFlow(DriveSnapshot())
    override val snapshot: StateFlow<DriveSnapshot> = mutableSnapshot.asStateFlow()
    private var connection: AutoCloseable? = null
    private var connectionEpoch = 0L
    private var session: Session? = null
    private var refreshJob: Job? = null
    private var started = false
    private var closed = false
    private var retryNeeded = false
    private var cleanupFailed = false

    override fun start() = synchronized(lock) {
        if (closed || (started && !retryNeeded)) return@synchronized
        started = true
        retryNeeded = false
        val epoch = ++connectionEpoch
        disposeSession()
        disconnect()
        if (cleanupFailed) {
            unknown(DriveReadinessReason.CLEANUP_FAILED)
            return@synchronized
        }
        unknown(DriveReadinessReason.CONNECTING)
        if (!isConnectionCurrent(epoch)) return@synchronized
        val observer = object : CarConnectionListener {
            override fun onReady(access: CarAccess) = synchronized(lock) {
                if (isConnectionCurrent(epoch)) establishSession(access)
            }

            override fun onDisconnected() = synchronized(lock) {
                if (isConnectionCurrent(epoch)) {
                    disposeSession()
                    retryNeeded = true
                    unknown(DriveReadinessReason.DISCONNECTED)
                }
            }

            override fun onFailure(reason: DriveReadinessReason) = synchronized(lock) {
                if (isConnectionCurrent(epoch)) fail(reason)
            }
        }
        try {
            val created = connector.connect(observer)
            // A synchronous callback can close/retry before connect returns its handle.
            if (isConnectionCurrent(epoch)) connection = created else closeResource(created)
        } catch (failure: RuntimeException) {
            if (isConnectionCurrent(epoch)) {
                ++connectionEpoch
                fail(readFailureReason(failure))
            }
        } catch (_: LinkageError) {
            if (isConnectionCurrent(epoch)) {
                ++connectionEpoch
                fail(DriveReadinessReason.UNSUPPORTED)
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        ++connectionEpoch
        disposeSession()
        disconnect()
        scope.cancel()
        unknown(DriveReadinessReason.CLOSED)
    }

    private fun isConnectionCurrent(epoch: Long) = started && !closed && connectionEpoch == epoch

    private fun isCurrent(candidate: Session) = !closed && session === candidate

    private fun establishSession(access: CarAccess) {
        disposeSession()
        if (cleanupFailed) {
            retryNeeded = true
            unknown(DriveReadinessReason.CLEANUP_FAILED)
            return
        }
        retryNeeded = false
        val current = Session(access)
        session = current
        unknown(DriveReadinessReason.CONNECTING)
        if (!isCurrent(current)) return
        try {
            if (DriveProperty.entries.any { !access.isSupported(it) }) {
                fail(DriveReadinessReason.UNSUPPORTED)
                return
            }
            if (!isCurrent(current)) return
            // Record each attempted registration before calling out: APIs may throw after allocation.
            current.uxRegistrationAttempted = true
            access.registerUxListener { reading ->
                synchronized(lock) {
                    if (isCurrent(current)) {
                        ++current.uxRevision
                        current.ux = reading
                        publish(current)
                    }
                }
            }
            if (!isCurrent(current)) return
            for (property in DriveProperty.entries) {
                val listener = object : VehicleListener {
                    override fun onChange(reading: VehicleReading) = synchronized(lock) {
                        if (isCurrent(current)) {
                            current.revisions[property] = current.revision(property) + 1
                            acceptReading(current, property, reading)
                            publish(current)
                        }
                    }

                    override fun onError() = synchronized(lock) {
                        if (isCurrent(current)) fail(DriveReadinessReason.PROPERTY_ERROR)
                    }
                }
                current.listeners[property] = listener
                if (!access.registerPropertyListener(property, listener)) {
                    fail(DriveReadinessReason.LISTENER_REGISTRATION_FAILED)
                    return
                }
                if (!isCurrent(current)) return
            }
        } catch (failure: RuntimeException) {
            if (isCurrent(current)) fail(readFailureReason(failure, DriveReadinessReason.LISTENER_REGISTRATION_FAILED))
            return
        } catch (_: LinkageError) {
            if (isCurrent(current)) fail(DriveReadinessReason.UNSUPPORTED)
            return
        }
        readCurrent(current)
        if (!isCurrent(current)) return
        current.initialized = true
        publish(current)
        if (!isCurrent(current)) return
        refreshJob = scope.launch {
            while (isActive) {
                delay(1_000)
                synchronized(lock) {
                    if (isCurrent(current)) {
                        readCurrent(current)
                        publish(current)
                    }
                }
            }
        }
    }

    private fun readCurrent(current: Session) {
        try {
            val uxRevision = current.uxRevision
            val ux = current.access.readUx()
            if (!isCurrent(current)) return
            if (uxRevision == current.uxRevision) current.ux = ux
            for (property in DriveProperty.entries) {
                val revision = current.revision(property)
                val reading = current.access.readProperty(property)
                if (!isCurrent(current)) return
                // A getter can deliver a newer reentrant callback before returning cached data.
                // Its explicit failure still invalidates this connection, even after a good callback.
                if ((reading != null && reading.status != VehicleStatus.AVAILABLE) ||
                    revision == current.revision(property)
                ) acceptReading(current, property, reading)
                if (!isCurrent(current)) return
            }
        } catch (failure: RuntimeException) {
            if (isCurrent(current)) fail(readFailureReason(failure))
        } catch (_: LinkageError) {
            if (isCurrent(current)) fail(DriveReadinessReason.UNSUPPORTED)
        }
    }

    private fun acceptReading(current: Session, property: DriveProperty, reading: VehicleReading?) {
        if (reading == null) {
            current.readings[property] = null
            return
        }
        when (reading.status) {
            VehicleStatus.UNAVAILABLE -> { fail(DriveReadinessReason.PROPERTY_UNAVAILABLE); return }
            VehicleStatus.ERROR -> { fail(DriveReadinessReason.PROPERTY_ERROR); return }
            VehicleStatus.AVAILABLE -> Unit
        }
        val previous = current.readings[property]
        if (previous != null && reading.timestampNanos > 0) {
            if (reading.timestampNanos < previous.timestampNanos) return
            if (reading.timestampNanos == previous.timestampNanos && reading.value != previous.value) {
                fail(DriveReadinessReason.INVALID_TIMESTAMP)
                return
            }
        }
        current.readings[property] = reading
    }

    private fun publish(current: Session) {
        if (!isCurrent(current)) return
        val evaluated = DriveReadiness.evaluate(
            true, current.ux, current.readings[DriveProperty.GEAR], current.readings[DriveProperty.SPEED],
            elapsedRealtimeNanos(),
        )
        mutableSnapshot.value = if (!current.initialized && evaluated.state != DriveState.UNKNOWN) {
            evaluated.copy(state = DriveState.UNKNOWN, readinessReason = DriveReadinessReason.CONNECTING)
        } else evaluated
    }

    private fun fail(reason: DriveReadinessReason) {
        val ux = session?.ux
        disposeSession()
        retryNeeded = true
        unknown(reason, ux)
    }

    private fun unknown(reason: DriveReadinessReason, ux: UxReading? = null) {
        mutableSnapshot.value = DriveSnapshot(
            requiresDistractionOptimization = ux?.requiresDistractionOptimization,
            activeRestrictions = ux?.activeRestrictions,
            readinessReason = reason,
            cleanupFailed = cleanupFailed,
        )
    }

    private fun disposeSession() {
        refreshJob?.cancel()
        refreshJob = null
        val previous = session ?: return
        session = null // Reject reentrant and queued callbacks before unregistering anything.
        for (listener in previous.listeners.values.toList().asReversed()) {
            cleanup { previous.access.unregisterPropertyListener(listener) }
        }
        if (previous.uxRegistrationAttempted) cleanup { previous.access.unregisterUxListener() }
    }

    private fun disconnect() {
        val previous = connection
        connection = null
        if (previous != null) closeResource(previous)
    }

    private fun closeResource(resource: AutoCloseable) {
        cleanup { resource.close() }
        if (closed) unknown(DriveReadinessReason.CLOSED)
    }

    private inline fun cleanup(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            cleanupFailed = true
        } catch (_: LinkageError) {
            cleanupFailed = true
        }
    }

    private class Session(val access: CarAccess) {
        var initialized = false
        var ux: UxReading? = null
        var uxRevision = 0L
        var uxRegistrationAttempted = false
        val listeners = linkedMapOf<DriveProperty, VehicleListener>()
        val readings = mutableMapOf<DriveProperty, VehicleReading?>()
        val revisions = mutableMapOf<DriveProperty, Long>()
        fun revision(property: DriveProperty) = revisions[property] ?: 0L
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
