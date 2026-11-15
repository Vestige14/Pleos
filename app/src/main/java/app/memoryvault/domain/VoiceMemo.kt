package app.memoryvault.domain

import app.memoryvault.platform.SpeechEvent
import app.memoryvault.platform.SpeechInput
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

enum class VoiceFailure { RECOGNITION, CAPTURE, STORAGE }

/** CALLER also covers a coroutine cancellation propagated from an external boundary. */
enum class VoiceCancelReason { USER, UNKNOWN, CALLER }

data class VoiceDriveSnapshot(val state: DriveState, val invalidationGeneration: Long)
interface VoiceDriveSafety {
    fun current(): VoiceDriveSnapshot
    suspend fun awaitInvalidation(generation: Long)
}

sealed interface VoiceMemoOutcome {
    val cleanupFailed: Boolean get() = false
    data object Blocked : VoiceMemoOutcome
    data object Busy : VoiceMemoOutcome
    data class Saved(val noteId: Long, override val cleanupFailed: Boolean = false) : VoiceMemoOutcome
    data class NothingHeard(override val cleanupFailed: Boolean = false) : VoiceMemoOutcome
    data class TimedOut(override val cleanupFailed: Boolean = false) : VoiceMemoOutcome
    data class Cancelled(val reason: VoiceCancelReason, override val cleanupFailed: Boolean = false) : VoiceMemoOutcome
    data class Failed(val reason: VoiceFailure, override val cleanupFailed: Boolean = false) : VoiceMemoOutcome
}

sealed interface VoiceState {
    data object Idle : VoiceState
    data object Preparing : VoiceState
    data object Listening : VoiceState
    data object Processing : VoiceState
    data class Result(val outcome: VoiceMemoOutcome) : VoiceState
}

/**
 * Saves the first nonblank final verbatim, without requiring a visit or network access.
 * The final deadline bounds recognition, not a save already in progress. A save remains
 * cancellable: cancellation before it returns cannot report Saved, but this layer never
 * deletes a record that the storage boundary may already have committed.
 *
 * Normal cleanup failures accompany the original outcome as [VoiceMemoOutcome.cleanupFailed].
 * Cleanup CancellationException is propagated after both cleanup calls; a prior caller
 * cancellation takes precedence. Even if a save committed before that exception, no record
 * is deleted. Actual SDK resource release must be verified by the future adapter.
 */
class VoiceMemoUseCase(
    private val driveState: StateFlow<DriveState>,
    private val saveVoiceMemo: suspend (sessionId: String, text: String, capturedAt: Long) -> Long,
    private val captureTimeoutMs: Long = 15_000L,
    private val finalTimeoutMs: Long = 20_000L,
    private val driveSafety: VoiceDriveSafety? = null,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        require(captureTimeoutMs > 0L) { "Capture timeout must be positive" }
        require(finalTimeoutMs > captureTimeoutMs) { "Final deadline must follow capture timeout" }
    }

    private val active = AtomicReference<VoiceMemoSession?>(null)
    private val activeCapture = AtomicReference<CaptureControl?>(null)
    private val mutableState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = mutableState.asStateFlow()
    private val mutableTiming = MutableStateFlow(VoiceCaptureTiming())
    val timing: StateFlow<VoiceCaptureTiming> = mutableTiming.asStateFlow()

    /** Acknowledges capture stop, not final recognition or a successful save. */
    fun finishCapture(): Boolean = activeCapture.get()?.requestFinish() ?: false

    /** Blocked/busy/invalid calls never acquire or release [input] or change active UI state. */
    suspend fun record(sessionId: String, capturedAt: Long, input: SpeechInput): VoiceMemoOutcome =
        record(sessionId, capturedAt, input, AtomicBoolean())

    /** The controller uses this flag only to know when ownership of an acquired input passed here. */
    internal suspend fun record(
        sessionId: String,
        capturedAt: Long,
        input: SpeechInput,
        inputAccepted: AtomicBoolean,
    ): VoiceMemoOutcome {
        require(sessionId.isNotBlank()) { "Voice session ID is blank" }
        currentCoroutineContext().ensureActive()
        val session = VoiceMemoSession()
        if (!active.compareAndSet(null, session)) return VoiceMemoOutcome.Busy
        try {
            if (!DrivePolicy.canStartVoice(currentDriveSnapshot().state)) return VoiceMemoOutcome.Blocked
            inputAccepted.set(true)
            mutableTiming.value = VoiceCaptureTiming()
            mutableState.value = VoiceState.Preparing
            var outcome: VoiceMemoOutcome? = null
            var failure: Throwable? = null
            try {
                outcome = runSession(session, sessionId, capturedAt, input)
            } catch (thrown: Throwable) {
                // Preserve coroutine cancellation/fatal failures until resources have been attempted.
                failure = thrown
            }

            val discardFailure = if (failure is CancellationException || outcome is VoiceMemoOutcome.Cancelled) {
                withContext(NonCancellable) { runCatching { input.discardIncomplete() }.exceptionOrNull() }
            } else null
            val cleanup = releaseInput(input)
            if (failure == null) failure = cleanup.propagate
            val result = when {
                failure is CancellationException -> VoiceMemoOutcome.Cancelled(VoiceCancelReason.CALLER)
                failure != null -> VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION)
                else -> checkNotNull(outcome)
            }.withCleanupFailure(cleanup.failed || discardFailure != null)
            // Cancellation and event failures can end capture without an end event.
            markCaptureEnded()
            mutableState.value = VoiceState.Result(result)
            failure?.let { throw it }
            return result
        } finally {
            active.compareAndSet(session, null)
        }
    }

    /** Used for explicit user cancellation and Android background/lifecycle cancellation. */
    fun cancel() {
        active.get()?.cancel(VoiceCancelReason.USER)
    }

    private suspend fun runSession(
        session: VoiceMemoSession,
        sessionId: String,
        capturedAt: Long,
        input: SpeechInput,
    ): VoiceMemoOutcome = supervisorScope {
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            session.attach(currentCoroutineContext().job)
            currentCoroutineContext().ensureActive()
            input.bindSession(sessionId, capturedAt)
            when (val recognition = awaitFinal(input)) {
                is Recognition.Unsuccessful -> recognition.outcome
                is Recognition.Final -> {
                    currentCoroutineContext().ensureActive()
                    mutableState.value = VoiceState.Processing
                    try {
                        val noteId = input.persistFinal(recognition.text) {
                            currentCoroutineContext().ensureActive()
                            saveVoiceMemo(sessionId, recognition.text, capturedAt)
                        }
                        currentCoroutineContext().ensureActive()
                        VoiceMemoOutcome.Saved(noteId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        VoiceMemoOutcome.Failed(VoiceFailure.STORAGE)
                    }
                }
            }
        }
        try {
            work.await()
        } catch (cancelled: CancellationException) {
            // Caller cancellation always stays cancellation, even if a session cancel raced it.
            currentCoroutineContext().ensureActive()
            if (cancelled is VoiceSessionCancellation) VoiceMemoOutcome.Cancelled(cancelled.reason)
            else throw cancelled
        }
    }

    private fun currentDriveSnapshot(): VoiceDriveSnapshot =
        driveSafety?.current() ?: VoiceDriveSnapshot(driveState.value, 0L)

    private suspend fun awaitFinal(input: SpeechInput): Recognition = try {
        withTimeoutOrNull(finalTimeoutMs) {
            coroutineScope {
                val recognitionScope = this
                val capture = CaptureControl()
                activeCapture.set(capture)
                val captureDeadline = launch(start = CoroutineStart.UNDISPATCHED) {
                    // The established capture budget starts at awaitFinal, even if Started is late.
                    withTimeoutOrNull(captureTimeoutMs) { capture.manualStop.await() }
                    if (capture.claimStop()) {
                        markCaptureEnded()
                        mutableState.value = VoiceState.Processing
                        try {
                            input.stopCapture()
                        } catch (cancelled: CancellationException) {
                            // Child cancellation alone does not fail its parent scope.
                            recognitionScope.cancel(cancelled)
                        } catch (_: Exception) {
                            throw CaptureFailure()
                        }
                    }
                }
                try {
                    val event = input.events().onEach {
                        if (it == SpeechEvent.Started && capture.started()) {
                            mutableTiming.update { timing -> timing.copy(startedAtElapsedMs = elapsedRealtimeMs()) }
                            // A late Started cannot overwrite an already processed capture end.
                            mutableState.compareAndSet(VoiceState.Preparing, VoiceState.Listening)
                        }
                        if (it == SpeechEvent.RecognitionEnded) {
                            capture.end()
                            markCaptureEnded()
                            mutableState.value = VoiceState.Processing
                        }
                        if (it is SpeechEvent.Final || it == SpeechEvent.Error || it == SpeechEvent.CaptureError) {
                            capture.end()
                            markCaptureEnded()
                        }
                    }.firstOrNull { it is SpeechEvent.Final || it == SpeechEvent.Error || it == SpeechEvent.CaptureError }
                    when (event) {
                        is SpeechEvent.Final -> if (event.text.isBlank()) {
                            Recognition.Unsuccessful(VoiceMemoOutcome.NothingHeard())
                        } else {
                            Recognition.Final(event.text)
                        }
                        SpeechEvent.Error -> Recognition.Unsuccessful(VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION))
                        SpeechEvent.CaptureError -> Recognition.Unsuccessful(VoiceMemoOutcome.Failed(VoiceFailure.CAPTURE))
                        else -> Recognition.Unsuccessful(VoiceMemoOutcome.NothingHeard())
                    }
                } finally {
                    capture.end()
                    activeCapture.compareAndSet(capture, null)
                    captureDeadline.cancel()
                }
            }
        } ?: Recognition.Unsuccessful(VoiceMemoOutcome.TimedOut())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: CaptureFailure) {
        Recognition.Unsuccessful(VoiceMemoOutcome.Failed(VoiceFailure.CAPTURE))
    } catch (_: Exception) {
        Recognition.Unsuccessful(VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION))
    }

    private fun markCaptureEnded() {
        mutableTiming.update { timing ->
            if (timing.startedAtElapsedMs == null || timing.endedAtElapsedMs != null) timing
            else timing.copy(endedAtElapsedMs = elapsedRealtimeMs())
        }
    }

    private fun releaseInput(input: SpeechInput): Cleanup {
        // Capture exceptions only to ensure that failure of cancel never skips close.
        val failures = listOfNotNull(
            runCatching { input.cancel() }.exceptionOrNull(),
            runCatching { input.close() }.exceptionOrNull(),
        )
        return Cleanup(
            failed = failures.isNotEmpty(),
            propagate = failures.firstOrNull { it is CancellationException }
                ?: failures.firstOrNull { it !is Exception },
        )
    }

    private fun VoiceMemoOutcome.withCleanupFailure(failed: Boolean): VoiceMemoOutcome = when (this) {
        is VoiceMemoOutcome.Saved -> copy(cleanupFailed = failed)
        is VoiceMemoOutcome.NothingHeard -> copy(cleanupFailed = failed)
        is VoiceMemoOutcome.TimedOut -> copy(cleanupFailed = failed)
        is VoiceMemoOutcome.Cancelled -> copy(cleanupFailed = failed)
        is VoiceMemoOutcome.Failed -> copy(cleanupFailed = failed)
        VoiceMemoOutcome.Blocked, VoiceMemoOutcome.Busy -> this
    }

    private class CaptureFailure : Exception()
    private class CaptureControl {
        private enum class Phase { PREPARING, LISTENING, STOP_REQUESTED, STOPPING, ENDED }
        private val phase = AtomicReference(Phase.PREPARING)
        val manualStop = CompletableDeferred<Unit>()

        fun started(): Boolean = phase.compareAndSet(Phase.PREPARING, Phase.LISTENING)

        fun requestFinish(): Boolean {
            if (!phase.compareAndSet(Phase.LISTENING, Phase.STOP_REQUESTED)) return false
            manualStop.complete(Unit)
            return true
        }

        fun claimStop(): Boolean {
            while (true) {
                val previous = phase.get()
                if (previous == Phase.STOPPING || previous == Phase.ENDED) return false
                if (phase.compareAndSet(previous, Phase.STOPPING)) return true
            }
        }

        fun end() { phase.set(Phase.ENDED) }
    }
    private data class Cleanup(val failed: Boolean, val propagate: Throwable?)
    private sealed interface Recognition {
        data class Final(val text: String) : Recognition
        data class Unsuccessful(val outcome: VoiceMemoOutcome) : Recognition
    }
}

internal class VoiceMemoSession {
    private val lock = Any()
    private var job: Job? = null
    private var cancellation: VoiceCancelReason? = null

    fun attach(work: Job) {
        val reason = synchronized(lock) {
            job = work
            cancellation
        }
        reason?.let { work.cancel(VoiceSessionCancellation(it)) }
    }

    fun cancel(reason: VoiceCancelReason) {
        val work = synchronized(lock) {
            if (job?.isCompleted == true || cancellation != null) return
            cancellation = reason
            job
        }
        // The lock hands off either the job or its first reason. Invoke external Job
        // cancellation outside the lock so completion callbacks cannot hold the handoff.
        work?.cancel(VoiceSessionCancellation(reason))
    }
}

internal class VoiceSessionCancellation(val reason: VoiceCancelReason) : CancellationException("Voice session cancelled")
