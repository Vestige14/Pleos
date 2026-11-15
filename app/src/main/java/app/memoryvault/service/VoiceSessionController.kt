package app.memoryvault.service

import app.memoryvault.CaptureClock
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceMemoOutcome
import app.memoryvault.domain.VoiceMemoUseCase
import app.memoryvault.domain.VoiceCaptureTiming
import app.memoryvault.domain.VoiceCancelReason
import app.memoryvault.domain.VoiceFailure
import app.memoryvault.domain.VoiceState
import app.memoryvault.platform.SpeechInput
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

enum class StorageReadiness { LOADING, READY, FAILED }
enum class VoiceStartBlock { MICROPHONE, NOTIFICATIONS, RECORDING_CHANNEL, SDK_UNAVAILABLE, STORAGE_UNAVAILABLE, PARKED, UNKNOWN, BUSY, SERVICE_UNAVAILABLE }
data class VoiceAvailability(
    val microphone: Boolean = true,
    val notificationPermission: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val recordingChannelEnabled: Boolean = true,
    val sdkAvailable: Boolean = true,
) {
    fun block(): VoiceStartBlock? = when {
        !microphone -> VoiceStartBlock.MICROPHONE
        !notificationPermission || !notificationsEnabled -> VoiceStartBlock.NOTIFICATIONS
        !recordingChannelEnabled -> VoiceStartBlock.RECORDING_CHANNEL
        !sdkAvailable -> VoiceStartBlock.SDK_UNAVAILABLE
        else -> null
    }
}
sealed interface VoiceServiceResult {
    data class NotStarted(val reason: VoiceStartBlock) : VoiceServiceResult
    data class Interrupted(val reason: VoiceStartBlock) : VoiceServiceResult
    data class Completed(val outcome: VoiceMemoOutcome) : VoiceServiceResult
}
sealed interface VoiceServiceState {
    data object Idle : VoiceServiceState
    data object CheckingVehicle : VoiceServiceState
    data object Preparing : VoiceServiceState
    data object Listening : VoiceServiceState
    data object Processing : VoiceServiceState
    data class Finished(val result: VoiceServiceResult) : VoiceServiceState
}

class VoiceSessionController(
    private val driveState: StateFlow<DriveState>,
    private val storage: StateFlow<StorageReadiness>,
    private val voice: VoiceMemoUseCase,
    private val availability: () -> VoiceAvailability,
    private val startDrive: () -> Unit,
    private val speechFactory: () -> SpeechInput,
    private val captureClock: CaptureClock,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableState = MutableStateFlow<VoiceServiceState>(VoiceServiceState.Idle)
    val state: StateFlow<VoiceServiceState> = mutableState.asStateFlow()
    val timing: StateFlow<VoiceCaptureTiming> get() = voice.timing
    private val active = AtomicReference<Run?>(null)

    /** The active capture may stop while final recognition and storage continue. */
    fun finishCapture(): Boolean = if (active.get() != null) voice.finishCapture() else false

    suspend fun record(): VoiceServiceResult {
        currentCoroutineContext().ensureActive()
        val run = Run()
        if (!active.compareAndSet(null, run)) return VoiceServiceResult.NotStarted(VoiceStartBlock.BUSY)
        mutableState.value = VoiceServiceState.CheckingVehicle
        try {
            val result = supervisorScope {
                val work = async(start = CoroutineStart.LAZY) { awaitReadyAndRecord() }
                run.attach(work)
                val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
                    while (true) {
                        delay(1_000L)
                        availability().block()?.let { run.cancel(VoiceServiceResult.Interrupted(it)) }
                    }
                }
                try {
                    work.await()
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (cancelled is RunCancellation) cancelled.result else throw cancelled
                } finally {
                    monitor.cancel()
                }
            }
            mutableState.value = VoiceServiceState.Finished(result)
            return result
        } catch (cancelled: CancellationException) {
            mutableState.value = VoiceServiceState.Finished(
                VoiceServiceResult.Completed(VoiceMemoOutcome.Cancelled(VoiceCancelReason.CALLER)),
            )
            throw cancelled
        } finally {
            active.compareAndSet(run, null)
        }
    }

    fun cancel() {
        active.get()?.cancel(VoiceServiceResult.Completed(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER)))
    }

    internal fun interrupt(reason: VoiceStartBlock) {
        active.get()?.cancel(VoiceServiceResult.Interrupted(reason))
    }

    private suspend fun awaitReadyAndRecord(): VoiceServiceResult {
        availability().block()?.let { return VoiceServiceResult.NotStarted(it) }
        if (driveState.value == DriveState.TRANSITION) return VoiceServiceResult.NotStarted(VoiceStartBlock.UNKNOWN)
        if (storage.value == StorageReadiness.FAILED) return VoiceServiceResult.NotStarted(VoiceStartBlock.STORAGE_UNAVAILABLE)
        // Recording waits only for private storage. Vehicle connection is not a microphone prerequisite.
        if (storage.value == StorageReadiness.LOADING) {
            withTimeoutOrNull(5_000L) {
                combine(driveState, storage) { drive, db ->
                    drive == DriveState.TRANSITION || db != StorageReadiness.LOADING
                }.first { it }
            }
        }
        // Check live values at the actual microphone invocation, including stale PendingIntents.
        availability().block()?.let { return VoiceServiceResult.NotStarted(it) }
        if (driveState.value == DriveState.TRANSITION) return VoiceServiceResult.NotStarted(VoiceStartBlock.UNKNOWN)
        if (storage.value != StorageReadiness.READY) return VoiceServiceResult.NotStarted(VoiceStartBlock.STORAGE_UNAVAILABLE)
        currentCoroutineContext().ensureActive()
        val input = try { speechFactory() } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return VoiceServiceResult.NotStarted(VoiceStartBlock.SDK_UNAVAILABLE)
        }
        val transferred = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        fun releaseUnaccepted(): Throwable? {
            if (!released.compareAndSet(false, true)) return null
            val failures = listOfNotNull(
                runCatching { input.cancel() }.exceptionOrNull(),
                runCatching { input.close() }.exceptionOrNull(),
            )
            return failures.firstOrNull { it is CancellationException }
                ?: failures.firstOrNull { it !is Exception }
                ?: failures.firstOrNull()
        }
        fun rejectAfterAcquisition(reason: VoiceStartBlock): VoiceServiceResult {
            return when (val failure = releaseUnaccepted()) {
                null -> VoiceServiceResult.NotStarted(reason)
                is CancellationException -> throw failure
                is Exception -> VoiceServiceResult.NotStarted(VoiceStartBlock.SERVICE_UNAVAILABLE)
                else -> throw failure
            }
        }

        try {
            // The factory can race a new vehicle transition, permission loss, or cancellation.
            currentCoroutineContext().ensureActive()
            availability().block()?.let { return rejectAfterAcquisition(it) }
            if (driveState.value == DriveState.TRANSITION) return rejectAfterAcquisition(VoiceStartBlock.UNKNOWN)
            if (storage.value != StorageReadiness.READY) return rejectAfterAcquisition(VoiceStartBlock.STORAGE_UNAVAILABLE)
            return supervisorScope {
                val states = launch(start = CoroutineStart.UNDISPATCHED) {
                    voice.state.collect { state ->
                        when (state) {
                            VoiceState.Preparing -> mutableState.value = VoiceServiceState.Preparing
                            VoiceState.Listening -> mutableState.value = VoiceServiceState.Listening
                            VoiceState.Processing -> mutableState.value = VoiceServiceState.Processing
                            else -> Unit
                        }
                    }
                }
                try {
                    val outcome = voice.record(newSessionId(), captureClock.now(), input, transferred)
                    if (transferred.get()) VoiceServiceResult.Completed(outcome)
                    else when (outcome) {
                        VoiceMemoOutcome.Blocked -> rejectAfterAcquisition(VoiceStartBlock.UNKNOWN)
                        VoiceMemoOutcome.Busy -> rejectAfterAcquisition(VoiceStartBlock.BUSY)
                        else -> rejectAfterAcquisition(VoiceStartBlock.SERVICE_UNAVAILABLE)
                    }
                } finally {
                    states.cancel()
                }
            }
        } catch (cancelled: CancellationException) {
            if (!transferred.get()) releaseUnaccepted()
            throw cancelled
        } catch (_: Exception) {
            if (transferred.get()) return VoiceServiceResult.Completed(VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION))
            val cleanup = releaseUnaccepted()
            if (cleanup is CancellationException) throw cleanup
            if (cleanup != null && cleanup !is Exception) throw cleanup
            return VoiceServiceResult.NotStarted(VoiceStartBlock.SERVICE_UNAVAILABLE)
        }
    }

    private class Run {
        private val lock = Any()
        private var work: Job? = null
        private var result: VoiceServiceResult? = null
        fun attach(job: Job) {
            val pending = synchronized(lock) { work = job; result }
            pending?.let { job.cancel(RunCancellation(it)) }
        }
        fun cancel(outcome: VoiceServiceResult) {
            val job = synchronized(lock) {
                if (result != null || work?.isCompleted == true) return
                result = outcome
                work
            }
            job?.cancel(RunCancellation(outcome))
        }
    }
    private class RunCancellation(val result: VoiceServiceResult) : CancellationException("Voice command interrupted")
}

enum class ReadyNotificationState { HIDDEN, READY, PARKED, UNAVAILABLE }
object ReadyNotificationPolicy {
    fun state(enabled: Boolean, drive: DriveState, availability: VoiceAvailability, busy: Boolean): ReadyNotificationState =
        when {
            !enabled || busy -> ReadyNotificationState.HIDDEN
            availability.block() != null -> ReadyNotificationState.UNAVAILABLE
            drive == DriveState.DRIVING -> ReadyNotificationState.READY
            drive == DriveState.PARKED -> ReadyNotificationState.PARKED
            else -> ReadyNotificationState.UNAVAILABLE
        }
}
