package app.memoryvault.navigation

import android.content.Context
import app.memoryvault.memorycontract.NavigationEvidence
import app.memoryvault.memorycontract.NavigationEvidenceKind
import app.memoryvault.memorycontract.NavigationEvidenceProvider
import app.memoryvault.memorycontract.NavigationEvidenceStatus
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Application-owned, read-only navigation evidence. Construction and snapshot/status do not query
 * the SDK or disk. start() makes one connection attempt and one initial pair of async getters.
 * close() is terminal; instantiate a new source for an intentional later connection attempt.
 */
class PleosNavigationEvidenceSource internal constructor(
    private val sdkFactory: () -> NavigationSdk,
    private val store: NavigationObservationStore,
    private val nowMillis: () -> Long,
    private val executor: ScheduledExecutorService,
    private val connectionTimeoutMillis: Long = 20_000,
    private val responseTimeoutMillis: Long = 5_000,
) : NavigationEvidenceProvider, AutoCloseable {
    constructor(context: Context) : this(
        sdkFactory = { AndroidPleosNavigationSdk(context.applicationContext) },
        store = FileNavigationObservationStore(File(context.applicationContext.noBackupFilesDir, "navigation-evidence")),
        nowMillis = System::currentTimeMillis,
        executor = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "memory-vault-navigation").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        },
    )

    private data class View(
        val rows: List<NavigationEvidence> = emptyList(),
        val status: NavigationEvidenceStatus = NavigationEvidenceStatus.NOT_READY,
    )

    private val view = AtomicReference(View())
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    // Everything below is owned by the single executor, including disk writes and SDK release.
    private var sdk: NavigationSdk? = null
    private var connected = false
    private var hasResponse = false
    private var connectionDeadline: ScheduledFuture<*>? = null
    private var currentDeadline: ScheduledFuture<*>? = null
    private var recentDeadline: ScheduledFuture<*>? = null
    private var currentPending = false
    private var recentPending = false
    private var currentSupported = true
    private var recentSupported = true
    private var acceptCurrent = true
    private var destinationGeneration = 0L
    private var requestedGeneration = 0L
    private var refreshAfterCurrent = false
    private var lastDestination: NavigationPlace? = null
    private var lastArrival: Pair<NavigationPlace, Long>? = null

    override fun snapshot(): List<NavigationEvidence> = NavigationRetention.bound(view.get().rows, nowMillis())
    override fun status(): NavigationEvidenceStatus =
        if (closed.get()) NavigationEvidenceStatus.UNAVAILABLE else view.get().status

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        submit {
            try {
                val loaded = store.load()
                val retained = NavigationRetention.bound(loaded, nowMillis())
                view.set(View(retained))
                if (loaded != retained) store.save(retained)
                if (closed.get()) return@submit
                val created = sdkFactory()
                sdk = created
                connectionDeadline = later(connectionTimeoutMillis) { unavailable() }
                created.connect(listener)
            } catch (_: Exception) { unavailable() }
            catch (_: LinkageError) { unavailable() }
        }
    }

    private val listener = object : NavigationSdk.Listener {
        override fun onConnected() = submit {
            if (sdk == null || connected) return@submit
            connected = true
            connectionDeadline?.cancel(false)
            requestCurrent()
            if (!connected) return@submit
            recentPending = true
            recentDeadline = later(responseTimeoutMillis) {
                if (recentPending) {
                    recentPending = false
                    recentSupported = false
                    if (!hasResponse && !currentPending) unavailable()
                }
            }
            sdkCall { getRecentDestinationInfo() }
        }

        override fun onUnavailable() = submit { unavailable() }

        override fun onDestinationInfo(place: NavigationPlace?) {
            val observedAt = nowMillis()
            submit {
                if (!connected || !currentSupported) return@submit
                currentDeadline?.cancel(false)
                val stale = currentPending && requestedGeneration != destinationGeneration
                currentPending = false
                responseReceived()
                if (stale) {
                    if (refreshAfterCurrent && acceptCurrent) requestCurrent()
                    return@submit
                }
                if (!acceptCurrent) return@submit
                val normalized = NavigationRetention.normalize(place)
                if (normalized != null && normalized != lastDestination) {
                    lastDestination = normalized
                    record(listOf(evidence(NavigationEvidenceKind.DESTINATION_OBSERVED, normalized, observedAt)))
                } else if (normalized == null) lastDestination = null
            }
        }

        override fun onRecentDestinationInfo(places: List<NavigationPlace>) {
            val observedAt = nowMillis()
            val captured = places.take(NavigationRetention.MAX_RECENT).toList()
            submit {
                if (!connected || !recentSupported) return@submit
                recentPending = false
                recentDeadline?.cancel(false)
                responseReceived()
                val recent = captured.mapNotNull(NavigationRetention::normalize).distinct().map {
                    evidence(NavigationEvidenceKind.UNDATED_RECENT, it, observedAt, recentId(it))
                }
                record(recent, replaceRecent = true)
            }
        }

        override fun onDestinationChanged() = submit {
            if (!connected) return@submit
            destinationGeneration++
            acceptCurrent = true
            lastDestination = null
            if (currentPending) refreshAfterCurrent = true else requestCurrent()
        }

        override fun onDestinationArrived(place: NavigationPlace) {
            val observedAt = nowMillis()
            submit {
                if (!connected) return@submit
                val normalized = NavigationRetention.normalize(place.copy(latitude = null, longitude = null))
                    ?: return@submit
                responseReceived()
                invalidateCurrent()
                val previous = lastArrival
                if (previous != null && previous.first == normalized &&
                    observedAt >= previous.second && observedAt - previous.second < 30_000) return@submit
                lastArrival = normalized to observedAt
                record(listOf(evidence(NavigationEvidenceKind.ARRIVAL_OBSERVED, normalized, observedAt)))
            }
        }

        override fun onRouteCancelled() = submit {
            if (connected) invalidateCurrent()
            // Cancellation is an observation, never a command; retain earlier evidence.
        }
    }

    private fun requestCurrent() {
        if (!connected || !currentSupported || currentPending || !acceptCurrent) return
        refreshAfterCurrent = false
        currentPending = true
        requestedGeneration = destinationGeneration
        currentDeadline = later(responseTimeoutMillis) {
            if (currentPending) {
                currentPending = false
                currentSupported = false
                if (!hasResponse && !recentPending) unavailable()
            }
        }
        sdkCall { getDestinationInfo() }
    }

    private fun invalidateCurrent() {
        destinationGeneration++
        acceptCurrent = false
        refreshAfterCurrent = false
        lastDestination = null
    }

    private fun responseReceived() {
        hasResponse = true
        view.updateAndGet { it.copy(status = NavigationEvidenceStatus.AVAILABLE) }
    }

    private fun record(added: List<NavigationEvidence>, replaceRecent: Boolean = false) {
        val previous = view.get()
        val retained = if (replaceRecent) previous.rows.filter { it.kind != NavigationEvidenceKind.UNDATED_RECENT }
            else previous.rows
        val rows = NavigationRetention.bound(added + retained, nowMillis())
        view.set(previous.copy(rows = rows))
        try { store.save(rows) }
        catch (_: Exception) { unavailable() } // Preserve the in-memory observation; never log a place/payload.
    }

    private fun evidence(
        kind: NavigationEvidenceKind, place: NavigationPlace, observedAt: Long,
        id: String = "nav-${UUID.randomUUID()}",
    ) = NavigationEvidence(
        id, kind, place.name, place.address, place.latitude, place.longitude,
        observedAtMillis = observedAt,
        // None of these official DTOs carries an event timestamp, including the arrival DTO.
        eventAtMillis = null,
    )

    private fun recentId(place: NavigationPlace): String {
        val fields = listOf(place.name, place.address, place.latitude?.toString(), place.longitude?.toString())
        val canonical = fields.joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "nav-recent-" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sdkCall(call: NavigationSdk.() -> Unit) {
        try { sdk?.call() }
        catch (_: Exception) { unavailable() }
        catch (_: LinkageError) { unavailable() }
    }

    private fun unavailable() {
        connected = false
        connectionDeadline?.cancel(false)
        currentDeadline?.cancel(false)
        recentDeadline?.cancel(false)
        currentPending = false
        recentPending = false
        view.updateAndGet { it.copy(status = NavigationEvidenceStatus.UNAVAILABLE) }
        val active = sdk
        sdk = null
        try { active?.close() }
        catch (_: Exception) { /* Read-only evidence stays available locally. */ }
        catch (_: LinkageError) { /* A missing platform service never breaks memo storage. */ }
    }

    private fun submit(action: () -> Unit) {
        if (closed.get()) return
        try { executor.execute { if (!closed.get()) action() } }
        catch (_: RejectedExecutionException) {
            view.updateAndGet { it.copy(status = NavigationEvidenceStatus.UNAVAILABLE) }
        }
    }

    private fun later(delayMillis: Long, action: () -> Unit): ScheduledFuture<*> =
        executor.schedule({ if (!closed.get()) action() }, delayMillis, TimeUnit.MILLISECONDS)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        view.updateAndGet { it.copy(status = NavigationEvidenceStatus.UNAVAILABLE) }
        try { executor.execute { unavailable() } }
        catch (_: RejectedExecutionException) { /* Already shut down by its owner. */ }
        finally { executor.shutdown() }
    }
}
