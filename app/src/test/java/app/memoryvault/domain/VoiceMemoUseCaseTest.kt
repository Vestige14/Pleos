package app.memoryvault.domain

import app.memoryvault.platform.SpeechEvent
import app.memoryvault.platform.SpeechInput
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceMemoUseCaseTest {
    @Test fun acceptedInput_isPreparingUntilTheSdkActuallyStarts() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(autoStarted = false)
        val active = async { useCase.record("initializing", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Preparing, useCase.state.value)
        input.emit(SpeechEvent.Started)
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        useCase.cancel()
        assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER), active.await())
        assertTrue(store.calls.isEmpty())
    }

    @Test fun startedAfterRecognitionEnded_cannotRevertProcessing() = runTest {
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), Store()::save)
        val input = Input(autoStarted = false)
        val active = async { useCase.record("ended", 7L, input) }
        runCurrent()
        input.emit(SpeechEvent.RecognitionEnded)
        input.emit(SpeechEvent.Started)
        runCurrent()
        assertEquals(VoiceState.Processing, useCase.state.value)
        input.emit(SpeechEvent.Final("raw"))
        assertEquals(VoiceMemoOutcome.Saved(41L), active.await())
    }

    @Test fun initializationConsumesCaptureDeadline_andLateStartedCannotRevertProcessing() = runTest {
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), Store()::save)
        val input = Input(autoStarted = false)
        val active = async { useCase.record("late-init", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Preparing, useCase.state.value)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(1, input.stopCalls)
        input.emit(SpeechEvent.Started)
        runCurrent()
        assertEquals(VoiceState.Processing, useCase.state.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(VoiceMemoOutcome.TimedOut(), active.await())
    }

    // Each test names a broken coordinator branch/side effect; only speech and persistence are fake.
    @Test fun transitionStart_isBlockedWithoutAcquiringOrClosingInput() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.TRANSITION), store::save)
        val input = Input()

        assertEquals(VoiceMemoOutcome.Blocked, useCase.record("s", 7L, input))
        assertEquals(VoiceState.Idle, useCase.state.value)
        assertEquals(0, input.eventsCalls)
        assertEquals(emptyList<String>(), input.cleanup)
        assertTrue(store.calls.isEmpty())
    }

    @Test fun blankSessionId_isRejectedBeforeAcquiringInput() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input()
        listOf("", " \n\t").forEach { sessionId ->
            try {
                useCase.record(sessionId, 7L, input)
                fail("A blank session ID must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(VoiceState.Idle, useCase.state.value)
        assertEquals(0, input.eventsCalls)
        assertTrue(input.cleanup.isEmpty())
        assertTrue(store.calls.isEmpty())
    }

    @Test fun busyStart_doesNotChangeActiveStateOrTouchEitherRejectedInput() = runTest {
        val store = Store()
        val drive = MutableStateFlow(DriveState.DRIVING)
        val useCase = VoiceMemoUseCase(drive, store::save)
        val input = Input()
        val active = async { useCase.record("first", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)

        drive.value = DriveState.PARKED
        val rejected = Input()
        assertEquals(VoiceMemoOutcome.Busy, useCase.record("second", 8L, rejected))
        assertEquals(VoiceMemoOutcome.Busy, useCase.record("third", 9L, input))
        assertEquals(VoiceState.Listening, useCase.state.value)
        assertEquals(0, rejected.eventsCalls)
        assertTrue(rejected.cleanup.isEmpty())
        assertEquals(1, input.eventsCalls)
        assertTrue(input.cleanup.isEmpty())
        input.emit(SpeechEvent.Final("first text"))
        assertEquals(VoiceMemoOutcome.Saved(41L), active.await())
        assertEquals(listOf(SaveCall("first", "first text", 7L)), store.calls)
    }

    @Test fun partialsNeverStore_andFirstFinalPreservesRawTextSessionAndCaptureTime() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input()
        val active = async { useCase.record(" raw-session ", 123_456L, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        input.emit(SpeechEvent.Partial("부분"))
        runCurrent()
        assertTrue(store.calls.isEmpty())
        val raw = "  기억\n다음 줄\t "
        input.emit(SpeechEvent.Final(raw))
        input.emit(SpeechEvent.Final("duplicate must be ignored"))

        assertEquals(VoiceMemoOutcome.Saved(41L), active.await())
        assertEquals(listOf(SaveCall(" raw-session ", raw, 123_456L)), store.calls)
        assertEquals(VoiceState.Result(VoiceMemoOutcome.Saved(41L)), useCase.state.value)
        assertEquals(listOf("cancel", "close"), input.cleanup)
        assertEquals(0, input.listeners)
    }

    @Test fun recognitionEnded_keepsFinalListenerAndSkipsRedundantCaptureStop() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input()
        val active = async { useCase.record("s", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        advanceTimeBy(1_000)
        input.emit(SpeechEvent.RecognitionEnded)
        runCurrent()
        assertEquals(VoiceState.Processing, useCase.state.value)
        assertFalse(active.isCompleted)
        assertEquals(1, input.listeners)

        advanceTimeBy(18_999)
        runCurrent()
        assertEquals(0, input.stopCalls)
        assertFalse(active.isCompleted)
        input.emit(SpeechEvent.Final("late final"))
        assertEquals(VoiceMemoOutcome.Saved(41L), active.await())
        assertEquals(listOf(SaveCall("s", "late final", 7L)), store.calls)
    }

    @Test fun blankFirstFinal_isNothingHeardAndLaterFinalCannotRescueIt() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(flowOf(SpeechEvent.Final(" \n\t"), SpeechEvent.Final("too late")))
        assertEquals(VoiceMemoOutcome.NothingHeard(), useCase.record("s", 7L, input))
        assertTrue(store.calls.isEmpty())
        assertEquals(listOf("cancel", "close"), input.cleanup)
    }

    @Test fun emptyCompletedStream_isNothingHeard() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(emptyFlow())
        assertEquals(VoiceMemoOutcome.NothingHeard(), useCase.record("s", 7L, input))
        assertTrue(store.calls.isEmpty())
        assertEquals(listOf("cancel", "close"), input.cleanup)
    }

    @Test fun completedStreamWithOnlyPartial_isNothingHeard() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(flowOf(SpeechEvent.Partial("not final")))
        assertEquals(VoiceMemoOutcome.NothingHeard(), useCase.record("s", 7L, input))
        assertTrue(store.calls.isEmpty())
    }

    @Test fun recognitionErrorEvent_isBoundedFailureAndStoresNothing() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(flowOf(SpeechEvent.Error))
        assertEquals(VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION), useCase.record("s", 7L, input))
        assertTrue(store.calls.isEmpty())
        assertEquals(listOf("cancel", "close"), input.cleanup)
    }

    @Test fun thrownRecognitionFailure_doesNotExposePayloadOrSucceed() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(flow { throw IllegalStateException("private boundary payload") })
        val outcome = useCase.record("s", 7L, input)
        assertEquals(VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION), outcome)
        assertFalse(outcome.toString().contains("private boundary payload"))
        assertTrue(store.calls.isEmpty())
        assertEquals(listOf("cancel", "close"), input.cleanup)
    }

    @Test fun captureStopsAt15SecondsOnce_andNoFinalTimesOutAt20Seconds() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input()
        val active = async { useCase.record("s", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(0, input.stopCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, input.stopCalls)
        assertEquals(VoiceState.Processing, useCase.state.value)
        assertEquals(1, input.listeners)
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse(active.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(VoiceMemoOutcome.TimedOut(), active.await())
        assertEquals(1, input.stopCalls)
        assertTrue(store.calls.isEmpty())
        assertEquals(listOf("cancel", "close"), input.cleanup)
        assertEquals(0, input.listeners)
    }

    @Test fun finalAt19999ms_canFinishSavingAfter20Seconds() = runTest {
        val saveMayReturn = CompletableDeferred<Unit>()
        val store = Store { saveMayReturn.await(); 41L }
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input()
        val active = async { useCase.record("s", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        advanceTimeBy(19_999)
        runCurrent()
        input.emit(SpeechEvent.Final("valid before deadline"))
        runCurrent()
        assertEquals(VoiceState.Processing, useCase.state.value)
        assertEquals(listOf(SaveCall("s", "valid before deadline", 7L)), store.calls)
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(active.isCompleted)
        saveMayReturn.complete(Unit)
        assertEquals(VoiceMemoOutcome.Saved(41L), active.await())
    }

    @Test fun finalAfterTimeout_isIgnoredAndNewSessionIsIndependent() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val oldInput = Input()
        val timedOut = async { useCase.record("old", 7L, oldInput) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(VoiceMemoOutcome.TimedOut(), timedOut.await())
        oldInput.emit(SpeechEvent.Final("expired"))

        val nextInput = Input()
        val next = async { useCase.record("next", 8L, nextInput) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        oldInput.emit(SpeechEvent.Final("old callback during next session"))
        runCurrent()
        assertTrue(store.calls.isEmpty())
        assertFalse(next.isCompleted)
        nextInput.emit(SpeechEvent.Final("new"))
        assertEquals(VoiceMemoOutcome.Saved(41L), next.await())
        assertEquals(listOf(SaveCall("next", "new", 8L)), store.calls)
    }

    @Test fun userCancel_finishesPromptlyAndAllowsAValidNewSession() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input()
        val active = async { useCase.record("old", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        useCase.cancel()
        runCurrent()
        assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER), active.await())
        assertTrue(store.calls.isEmpty())
        assertEquals(listOf("cancel", "close"), input.cleanup)
        assertEquals(0, input.listeners)
        assertEquals(VoiceMemoOutcome.Saved(41L), useCase.record("next", 8L, Input(flowOf(SpeechEvent.Final("new")))))
    }

    @Test fun callerCancellation_isNotSwallowedAndReleasesTheSession() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input()
        val active = async { useCase.record("old", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        active.cancel()
        active.join()
        assertTrue(active.isCancelled)
        try { active.await(); fail("Caller cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(VoiceState.Result(VoiceMemoOutcome.Cancelled(VoiceCancelReason.CALLER)), useCase.state.value)
        assertTrue(store.calls.isEmpty())
        assertEquals(listOf("cancel", "close"), input.cleanup)
        assertEquals(0, input.listeners)
        assertEquals(VoiceMemoOutcome.Saved(41L), useCase.record("next", 8L, Input(flowOf(SpeechEvent.Final("new")))))
    }

    @Test fun explicitCancelAfterUnknown_andLateCallbacksCannotAffectTheNextSession() = runTest {
        val drive = MutableStateFlow(DriveState.DRIVING)
        val store = Store()
        val useCase = VoiceMemoUseCase(drive, store::save)
        val oldInput = Input()
        val active = async { useCase.record("old", 7L, oldInput) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        drive.value = DriveState.UNKNOWN
        runCurrent()
        assertFalse("Vehicle uncertainty cannot cancel an accepted recording", active.isCompleted)
        useCase.cancel()
        assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER), active.await())
        assertEquals(listOf("cancel", "close"), oldInput.cleanup)
        drive.value = DriveState.DRIVING
        val input = Input()
        val next = async { useCase.record("next", 8L, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        oldInput.emit(SpeechEvent.Final("late old final"))
        runCurrent()
        assertTrue(store.calls.isEmpty())
        assertFalse(next.isCompleted)
        input.emit(SpeechEvent.Final("next text"))
        assertEquals(VoiceMemoOutcome.Saved(41L), next.await())
        assertEquals(listOf(SaveCall("next", "next text", 8L)), store.calls)
    }

    @Test fun drivingParkedChanges_doNotCancelAnAcceptedSession() = runTest {
        val drive = MutableStateFlow(DriveState.DRIVING)
        val store = Store()
        val useCase = VoiceMemoUseCase(drive, store::save)
        val input = Input()
        val active = async { useCase.record("s", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        listOf(DriveState.TRANSITION, DriveState.PARKED, DriveState.TRANSITION, DriveState.DRIVING, DriveState.PARKED).forEach {
            drive.value = it
            runCurrent()
            assertFalse(active.isCompleted)
            assertTrue(input.cleanup.isEmpty())
        }
        input.emit(SpeechEvent.Final("while parked"))
        assertEquals(VoiceMemoOutcome.Saved(41L), active.await())
    }

    @Test fun storageFailure_isNotSavedAndDoesNotPreventTheNextSession() = runTest {
        var failSave = true
        val store = Store { if (failSave) throw IllegalStateException("private storage failure"); 41L }
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(flowOf(SpeechEvent.Final("text")))
        assertEquals(VoiceMemoOutcome.Failed(VoiceFailure.STORAGE), useCase.record("s", 7L, input))
        assertEquals(VoiceState.Result(VoiceMemoOutcome.Failed(VoiceFailure.STORAGE)), useCase.state.value)
        assertEquals(listOf("cancel", "close"), input.cleanup)
        failSave = false
        assertEquals(VoiceMemoOutcome.Saved(41L), useCase.record("next", 8L, Input(flowOf(SpeechEvent.Final("new")))))
    }

    @Test fun storageCancellationException_remainsCoroutineCancellation() = runTest {
        val store = Store { throw CancellationException("storage cancellation") }
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(flowOf(SpeechEvent.Final("text")))
        try {
            useCase.record("s", 7L, input)
            fail("Storage cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("storage cancellation", expected.message)
        }
        assertEquals(listOf("cancel", "close"), input.cleanup)
        assertFalse(useCase.state.value is VoiceState.Processing)
    }

    @Test fun recognitionCancellationException_remainsCoroutineCancellation() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(flow { throw CancellationException("recognition cancellation") })
        try {
            useCase.record("s", 7L, input)
            fail("Recognition cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("recognition cancellation", expected.message)
        }
        assertTrue(store.calls.isEmpty())
        assertEquals(listOf("cancel", "close"), input.cleanup)
    }

    @Test fun captureStopFailure_isBoundedFailureAndStillClosesTheInput() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(stopFailure = IllegalStateException("private capture failure"))
        val active = async { useCase.record("s", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(VoiceMemoOutcome.Failed(VoiceFailure.CAPTURE), active.await())
        assertEquals(1, input.stopCalls)
        assertTrue(store.calls.isEmpty())
        assertEquals(listOf("cancel", "close"), input.cleanup)
    }

    @Test fun cleanupFailure_preservesTheRecognitionFailureAndAllowsNextSession() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(
            flowOf(SpeechEvent.Error),
            cancelFailure = IllegalStateException("private cancel failure"),
            closeFailure = IllegalStateException("private close failure"),
        )
        val expected = VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION, cleanupFailed = true)
        assertEquals(expected, useCase.record("old", 7L, input))
        assertEquals(VoiceState.Result(expected), useCase.state.value)
        assertEquals(listOf("cancel", "close"), input.cleanup)
        assertEquals(VoiceMemoOutcome.Saved(41L), useCase.record("next", 8L, Input(flowOf(SpeechEvent.Final("new")))))
    }

    @Test fun cleanupFailureAfterSave_preservesSavedIdWithWarningAndAllowsNextSession() = runTest {
        listOf(true, false).forEach { failCancel ->
            val store = Store()
            val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
            val failure = IllegalStateException("private cleanup failure")
            val input = Input(
                flowOf(SpeechEvent.Final("saved")),
                cancelFailure = if (failCancel) failure else null,
                closeFailure = if (failCancel) null else failure,
            )
            val expected = VoiceMemoOutcome.Saved(41L, cleanupFailed = true)
            assertEquals(expected, useCase.record("s", 7L, input))
            assertEquals(VoiceState.Result(expected), useCase.state.value)
            assertEquals(listOf("cancel", "close"), input.cleanup)
            assertEquals(listOf(SaveCall("s", "saved", 7L)), store.calls)
            assertEquals(VoiceMemoOutcome.Saved(41L), useCase.record("next", 8L, Input(flowOf(SpeechEvent.Final("new")))))
        }
    }

    @Test fun cancelBeforeSaveReturns_abortsSuspendSaveAndNeverReportsSaved() = runTest {
        var saveCancelled = false
        val store = Store {
            try { awaitCancellation() } finally { saveCancelled = true }
        }
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(flowOf(SpeechEvent.Final("text")))
        val active = async { useCase.record("s", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Processing, useCase.state.value)
        assertEquals(listOf(SaveCall("s", "text", 7L)), store.calls)
        useCase.cancel()
        runCurrent()
        assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER), active.await())
        assertTrue(saveCancelled)
        assertEquals(VoiceState.Result(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER)), useCase.state.value)
    }

    @Test fun unknownWhileSaving_doesNotInterruptTheAcceptedCommit() = runTest {
        val mayReturn = CompletableDeferred<Unit>()
        val drive = MutableStateFlow(DriveState.DRIVING)
        val store = Store { mayReturn.await(); 41L }
        val useCase = VoiceMemoUseCase(drive, store::save)
        val active = async { useCase.record("s", 7L, Input(flowOf(SpeechEvent.Final("text")))) }
        runCurrent()
        assertEquals(VoiceState.Processing, useCase.state.value)
        drive.value = DriveState.UNKNOWN
        runCurrent()
        assertFalse(active.isCompleted)
        mayReturn.complete(Unit)
        assertEquals(VoiceMemoOutcome.Saved(41L), active.await())
        assertEquals(listOf(SaveCall("s", "text", 7L)), store.calls)
    }

    @Test fun callerCancellationWhileSaving_propagatesAndDoesNotReportSaved() = runTest {
        var saveCancelled = false
        val store = Store { try { awaitCancellation() } finally { saveCancelled = true } }
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(flowOf(SpeechEvent.Final("text")))
        val active = async { useCase.record("s", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Processing, useCase.state.value)
        active.cancel()
        active.join()
        assertTrue(active.isCancelled)
        assertTrue(saveCancelled)
        assertEquals(VoiceState.Result(VoiceMemoOutcome.Cancelled(VoiceCancelReason.CALLER)), useCase.state.value)
        assertEquals(listOf("cancel", "close"), input.cleanup)
    }

    @Test fun cancelAfterCommitBeforeSaveReturns_neverDeletesCommittedRecordOrClaimsSaved() = runTest {
        val committed = mutableListOf<SaveCall>()
        val store = Store { call -> committed += call; awaitCancellation() }
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val active = async { useCase.record("s", 7L, Input(flowOf(SpeechEvent.Final("committed")))) }
        runCurrent()
        assertEquals(VoiceState.Processing, useCase.state.value)
        assertEquals(listOf(SaveCall("s", "committed", 7L)), committed)
        useCase.cancel()
        runCurrent()
        assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER), active.await())
        assertEquals(listOf(SaveCall("s", "committed", 7L)), committed)
    }

    @Test fun cancelAfterSuccessfulSave_doesNotRetroactivelyChangeResultOrStore() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        assertEquals(VoiceMemoOutcome.Saved(41L), useCase.record("s", 7L, Input(flowOf(SpeechEvent.Final("text")))))
        useCase.cancel()
        runCurrent()
        assertEquals(VoiceState.Result(VoiceMemoOutcome.Saved(41L)), useCase.state.value)
        assertEquals(listOf(SaveCall("s", "text", 7L)), store.calls)
    }

    @Test fun injectedTimeouts_boundCaptureAndFinalWaiting() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(
            MutableStateFlow(DriveState.DRIVING), store::save, captureTimeoutMs = 10L, finalTimeoutMs = 30L,
        )
        val input = Input()
        val active = async { useCase.record("s", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        advanceTimeBy(10)
        runCurrent()
        assertEquals(1, input.stopCalls)
        assertFalse(active.isCompleted)
        advanceTimeBy(20)
        runCurrent()
        assertEquals(VoiceMemoOutcome.TimedOut(), active.await())
        assertTrue(store.calls.isEmpty())
    }

    @Test fun cleanupCancellationAfterSave_propagatesButKeepsCommittedRecordAndFreesBusy() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(
            flowOf(SpeechEvent.Final("saved")),
            cancelFailure = CancellationException("cleanup cancellation"),
            closeFailure = IllegalStateException("also close fails"),
        )
        try {
            useCase.record("s", 7L, input)
            fail("Cleanup cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cleanup cancellation", expected.message)
        }
        assertEquals(listOf(SaveCall("s", "saved", 7L)), store.calls)
        assertEquals(listOf("cancel", "close"), input.cleanup)
        assertEquals(
            VoiceState.Result(VoiceMemoOutcome.Cancelled(VoiceCancelReason.CALLER, cleanupFailed = true)),
            useCase.state.value,
        )
        assertEquals(VoiceMemoOutcome.Saved(41L), useCase.record("next", 8L, Input(flowOf(SpeechEvent.Final("new")))))
    }

    @Test fun callerCancellation_takesPrecedenceOverCleanupCancellation() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(cancelFailure = CancellationException("cleanup cancellation"))
        val active = async { useCase.record("s", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        active.cancel(CancellationException("caller cancellation"))
        active.join()
        try {
            active.await()
            fail("Caller cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("caller cancellation", expected.message)
        }
        assertEquals(listOf("cancel", "close"), input.cleanup)
        assertTrue(store.calls.isEmpty())
    }

    @Test fun captureStopCancellation_remainsCoroutineCancellation() = runTest {
        val store = Store()
        val useCase = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), store::save)
        val input = Input(stopFailure = CancellationException("capture cancellation"))
        val active = async { useCase.record("s", 7L, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, useCase.state.value)
        advanceTimeBy(15_000)
        runCurrent()
        try {
            active.await()
            fail("Capture cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("capture cancellation", expected.message)
        }
        assertTrue(store.calls.isEmpty())
        assertEquals(listOf("cancel", "close"), input.cleanup)
    }

    @Test fun invalidTimeouts_areRejectedBeforeAnySessionCanStart() {
        val drive = MutableStateFlow(DriveState.DRIVING)
        val store = Store()
        listOf(0L to 20L, -1L to 20L, 20L to 20L, 20L to 10L).forEach { (capture, final) ->
            assertThrows(IllegalArgumentException::class.java) {
                VoiceMemoUseCase(drive, store::save, captureTimeoutMs = capture, finalTimeoutMs = final)
            }
        }
    }

    // Exercises the production primitive used by record/cancel, not a copied algorithm.
    // A shared start flag aligns real threads after the barrier. The old code can read
    // job=null, attach can publish the job and see no reason, then cancel can publish
    // its reason while still holding its stale null job. There is no sleep or product hook.
    @Test(timeout = 30_000L)
    fun sessionCancellationRacingAttachment_isDeliveredToTheActualWorkJob() {
        val rounds = 100_000
        val barrier = CyclicBarrier(3)
        val current = AtomicReference<SessionRace>()
        val workers = Executors.newFixedThreadPool(2)
        val attach = workers.submit {
            repeat(rounds) {
                barrier.await(5, TimeUnit.SECONDS)
                val race = current.get()
                race.ready.incrementAndGet()
                spinUntil { race.start.get() }
                race.session.attach(race.work)
                barrier.await(5, TimeUnit.SECONDS)
            }
        }
        val cancel = workers.submit {
            repeat(rounds) {
                barrier.await(5, TimeUnit.SECONDS)
                val race = current.get()
                race.ready.incrementAndGet()
                spinUntil { race.start.get() }
                race.session.cancel(VoiceCancelReason.USER)
                barrier.await(5, TimeUnit.SECONDS)
            }
        }
        var lost = 0
        try {
            repeat(rounds) {
                val race = SessionRace()
                current.set(race)
                barrier.await(5, TimeUnit.SECONDS)
                spinUntil { race.ready.get() == 2 }
                race.start.set(true)
                barrier.await(5, TimeUnit.SECONDS)
                if (!race.work.isCancelled) lost++
                race.work.cancel()
            }
            attach.get(5, TimeUnit.SECONDS)
            cancel.get(5, TimeUnit.SECONDS)
            assertEquals("Lost cancellations in $rounds real attach/cancel races", 0, lost)
        } finally {
            workers.shutdownNow()
            assertTrue("Race workers must stop", workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun sessionCancelBeforeAttach_deliversTheFirstReasonWhenTheJobArrives() {
        val session = VoiceMemoSession()
        val work = Job()
        var cancellation: Throwable? = null
        work.invokeOnCompletion { cancellation = it }
        session.cancel(VoiceCancelReason.USER)
        session.cancel(VoiceCancelReason.UNKNOWN)
        session.attach(work)
        assertTrue(work.isCancelled)
        assertEquals(VoiceCancelReason.USER, (cancellation as VoiceSessionCancellation).reason)
    }

    @Test fun sessionAttachBeforeCancel_deliversTheFirstReasonToTheJob() {
        val session = VoiceMemoSession()
        val work = Job()
        var cancellation: Throwable? = null
        work.invokeOnCompletion { cancellation = it }
        session.attach(work)
        session.cancel(VoiceCancelReason.UNKNOWN)
        session.cancel(VoiceCancelReason.USER)
        assertTrue(work.isCancelled)
        assertEquals(VoiceCancelReason.UNKNOWN, (cancellation as VoiceSessionCancellation).reason)
    }

    @Test fun sessionCancellationAfterCompletedWork_doesNotTurnCompletionIntoCancellation() {
        val session = VoiceMemoSession()
        val work = Job()
        session.attach(work)
        work.complete()
        session.cancel(VoiceCancelReason.USER)
        session.cancel(VoiceCancelReason.UNKNOWN)
        assertTrue(work.isCompleted)
        assertFalse(work.isCancelled)
    }

    private class SessionRace {
        val session = VoiceMemoSession()
        val work = Job()
        val ready = AtomicInteger(0)
        val start = AtomicBoolean(false)
    }

    private fun spinUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) {
                "Race rendezvous failed to make progress"
            }
            Thread.onSpinWait()
        }
    }

    private data class SaveCall(val sessionId: String, val text: String, val capturedAt: Long)

    private class Store(private val persist: suspend (SaveCall) -> Long = { 41L }) {
        val calls = mutableListOf<SaveCall>()
        suspend fun save(sessionId: String, text: String, capturedAt: Long): Long {
            val call = SaveCall(sessionId, text, capturedAt)
            calls += call
            return persist(call)
        }
    }

    private class Input(
        private val source: Flow<SpeechEvent>? = null,
        private val stopFailure: Exception? = null,
        private val cancelFailure: Exception? = null,
        private val closeFailure: Exception? = null,
        private val autoStarted: Boolean = true,
    ) : SpeechInput {
        private val callbacks = Channel<SpeechEvent>(Channel.UNLIMITED)
        var eventsCalls = 0
        var listeners = 0
        var stopCalls = 0
        val cleanup = mutableListOf<String>()

        override fun events(): Flow<SpeechEvent> {
            eventsCalls++
            return flow {
                listeners++
                try {
                    if (autoStarted) emit(SpeechEvent.Started)
                    emitAll(source ?: callbacks.receiveAsFlow())
                } finally { listeners-- }
            }
        }
        fun emit(event: SpeechEvent) { callbacks.trySend(event).getOrThrow() }
        override fun stopCapture() { stopCalls++; stopFailure?.let { throw it } }
        override fun cancel() { cleanup += "cancel"; cancelFailure?.let { throw it } }
        override fun close() { cleanup += "close"; closeFailure?.let { throw it } }
    }
}
