package app.memoryvault.domain

import app.memoryvault.platform.SpeechEvent
import app.memoryvault.platform.SpeechInput
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

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
) {
    init {
        require(captureTimeoutMs > 0L) { "Capture timeout must be positive" }
        require(finalTimeoutMs > captureTimeoutMs) { "Final deadline must follow capture timeout" }
    }

    private val active = AtomicReference<VoiceMemoSession?>(null)
    private val mutableState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = mutableState.asStateFlow()

    /** Blocked/busy/invalid calls never acquire or release [input] or change active UI state. */
    suspend fun record(sessionId: String, capturedAt: Long, input: SpeechInput): VoiceMemoOutcome {
        require(sessionId.isNotBlank()) { "Voice session ID is blank" }
        currentCoroutineContext().ensureActive()
        val session = VoiceMemoSession()
        if (!active.compareAndSet(null, session)) return VoiceMemoOutcome.Busy
        val acceptedDrive = currentDriveSnapshot()
        if (!DrivePolicy.canStartVoice(acceptedDrive.state)) {
            active.compareAndSet(session, null)
            return VoiceMemoOutcome.Blocked
        }

        mutableState.value = VoiceState.Preparing
        try {
            var outcome: VoiceMemoOutcome? = null
            var failure: Throwable? = null
            try {
                outcome = runSession(session, sessionId, capturedAt, input, acceptedDrive.invalidationGeneration)
            } catch (thrown: Throwable) {
                // Preserve coroutine cancellation/fatal failures until resources have been attempted.
                failure = thrown
            }

            val cleanup = releaseInput(input)
            if (failure == null) failure = cleanup.propagate
            val result = when {
                failure is CancellationException -> VoiceMemoOutcome.Cancelled(VoiceCancelReason.CALLER)
                failure != null -> VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION)
                else -> checkNotNull(outcome)
            }.withCleanupFailure(cleanup.failed)
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
        acceptedGeneration: Long,
    ): VoiceMemoOutcome = supervisorScope {
        val safety = launch(start = CoroutineStart.UNDISPATCHED) {
            if (driveSafety != null) driveSafety.awaitInvalidation(acceptedGeneration)
            else driveState.first { it == DriveState.UNKNOWN }
            session.cancel(VoiceCancelReason.UNKNOWN)
        }
        try {
            val work = async(start = CoroutineStart.UNDISPATCHED) {
                session.attach(currentCoroutineContext().job)
                checkCancellation(session, acceptedGeneration)
                when (val recognition = awaitFinal(input)) {
                    is Recognition.Unsuccessful -> recognition.outcome
                    is Recognition.Final -> {
                        checkCancellation(session, acceptedGeneration)
                        mutableState.value = VoiceState.Processing
                        try {
                            val noteId = saveVoiceMemo(sessionId, recognition.text, capturedAt)
                            checkCancellation(session, acceptedGeneration)
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
                if (cancelled is VoiceSessionCancellation) {
                    VoiceMemoOutcome.Cancelled(cancelled.reason)
                } else {
                    throw cancelled
                }
            }
        } finally {
            safety.cancel()
        }
    }

    private fun currentDriveSnapshot(): VoiceDriveSnapshot =
        driveSafety?.current() ?: VoiceDriveSnapshot(driveState.value, 0L)

    private suspend fun checkCancellation(session: VoiceMemoSession, acceptedGeneration: Long) {
        val current = currentDriveSnapshot()
        if (current.state == DriveState.UNKNOWN || current.invalidationGeneration != acceptedGeneration) {
            session.cancel(VoiceCancelReason.UNKNOWN)
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun awaitFinal(input: SpeechInput): Recognition = try {
        withTimeoutOrNull(finalTimeoutMs) {
            coroutineScope {
                val recognitionScope = this
                val captureEnded = AtomicBoolean(false)
                val captureDeadline = launch(start = CoroutineStart.UNDISPATCHED) {
                    delay(captureTimeoutMs)
                    if (captureEnded.compareAndSet(false, true)) {
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
                        if (it == SpeechEvent.Started && !captureEnded.get()) {
                            // Atomic transition cannot overwrite a racing capture deadline/end.
                            mutableState.compareAndSet(VoiceState.Preparing, VoiceState.Listening)
                        }
                        if (it == SpeechEvent.RecognitionEnded) {
                            captureEnded.set(true)
                            mutableState.value = VoiceState.Processing
                        }
                    }.firstOrNull { it is SpeechEvent.Final || it == SpeechEvent.Error }
                    when (event) {
                        is SpeechEvent.Final -> if (event.text.isBlank()) {
                            Recognition.Unsuccessful(VoiceMemoOutcome.NothingHeard())
                        } else {
                            Recognition.Final(event.text)
                        }
                        SpeechEvent.Error -> Recognition.Unsuccessful(VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION))
                        else -> Recognition.Unsuccessful(VoiceMemoOutcome.NothingHeard())
                    }
                } finally {
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
