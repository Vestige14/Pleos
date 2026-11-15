package app.memoryvault.ui.p2

import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceCancelReason
import app.memoryvault.domain.VoiceFailure
import app.memoryvault.domain.VoiceMemoOutcome
import app.memoryvault.domain.VoiceMemoUseCase
import app.memoryvault.domain.VoiceState
import app.memoryvault.platform.SpeechEvent
import app.memoryvault.platform.SpeechInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class P2VoiceFinishCaptureTest {
    @Test fun listeningFinishStopsCaptureOnceThenWaitsForFinalAndSavesVerbatim() = runTest {
        val input = Input()
        val saves = mutableListOf<String>()
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.PARKED), { _, text, _ ->
            saves += text
            41L
        }, elapsedRealtimeMs = { testScheduler.currentTime })
        val active = async { voice.record("manual-finish", 1234L, input) }
        runCurrent()
        assertEquals(VoiceState.Preparing, voice.state.value)
        assertEquals(null, voice.timing.value.startedAtElapsedMs)

        advanceTimeBy(350L)
        runCurrent()
        input.emit(SpeechEvent.Started)
        runCurrent()
        assertEquals(VoiceState.Listening, voice.state.value)
        assertEquals(350L, voice.timing.value.startedAtElapsedMs)
        assertEquals(null, voice.timing.value.endedAtElapsedMs)

        advanceTimeBy(1_250L)
        runCurrent()
        assertTrue("Finish acknowledges capture stop, not a completed save", voice.finishCapture())
        runCurrent()
        assertEquals(1, input.stopCalls)
        assertEquals(1_600L, voice.timing.value.endedAtElapsedMs)
        assertEquals(VoiceState.Processing, voice.state.value)
        assertTrue("Final recognition has not arrived", saves.isEmpty())

        input.emit(SpeechEvent.Final("  원문 그대로  "))
        assertEquals(VoiceMemoOutcome.Saved(41L), active.await())
        assertEquals(listOf("  원문 그대로  "), saves)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1_600L, voice.timing.value.endedAtElapsedMs)
        assertFalse(voice.finishCapture())
    }

    @Test fun finishIsUnavailableInIdlePreparingAndProcessing() = runTest {
        val input = Input()
        val save = CompletableDeferred<Long>()
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.UNKNOWN), { _, _, _ -> save.await() })
        assertFalse(voice.finishCapture())
        val active = async { voice.record("phases", 1234L, input) }
        runCurrent()
        assertEquals(VoiceState.Preparing, voice.state.value)
        assertFalse(voice.finishCapture())
        input.emit(SpeechEvent.Started)
        runCurrent()
        assertEquals(VoiceState.Listening, voice.state.value)
        input.emit(SpeechEvent.Final("final"))
        runCurrent()
        assertEquals(VoiceState.Processing, voice.state.value)
        assertFalse(voice.finishCapture())
        assertEquals(0, input.stopCalls)
        save.complete(41L)
        assertEquals(VoiceMemoOutcome.Saved(41L), active.await())
        assertFalse(voice.finishCapture())
    }

    @Test fun manualFinishRacingFifteenSecondDeadlineDoesNotStopTwice() = runTest {
        val input = Input()
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> 41L },
            elapsedRealtimeMs = { testScheduler.currentTime })
        val active = async { voice.record("race", 1234L, input) }
        runCurrent()
        input.emit(SpeechEvent.Started)
        runCurrent()
        advanceTimeBy(14_999L)
        runCurrent()
        assertTrue(voice.finishCapture())
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, input.stopCalls)
        assertEquals(14_999L, voice.timing.value.endedAtElapsedMs)
        assertFalse(voice.finishCapture())
        input.emit(SpeechEvent.Final("final"))
        assertEquals(VoiceMemoOutcome.Saved(41L), active.await())
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(14_999L, voice.timing.value.endedAtElapsedMs)
        assertEquals(1, input.stopCalls)
    }

    @Test fun cancellationAfterManualFinishDiscardsIncompleteCaptureAndIgnoresLateFinal() = runTest {
        val input = Input()
        val saves = mutableListOf<String>()
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, text, _ ->
            saves += text
            41L
        })
        val active = async { voice.record("cancel", 1234L, input) }
        runCurrent()
        input.emit(SpeechEvent.Started)
        runCurrent()
        assertTrue(voice.finishCapture())
        runCurrent()
        voice.cancel()
        assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER), active.await())
        input.emit(SpeechEvent.Final("late final"))
        runCurrent()
        assertEquals(1, input.discardCalls)
        assertEquals(1, input.stopCalls)
        assertTrue(saves.isEmpty())
    }

    @Test fun explicitCancelAfterStartedFreezesTerminalElapsedTime() = runTest {
        val input = Input()
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> 41L },
            elapsedRealtimeMs = { testScheduler.currentTime })
        val active = async { voice.record("cancel-timing", 1234L, input) }
        runCurrent()
        advanceTimeBy(250L)
        runCurrent()
        input.emit(SpeechEvent.Started)
        runCurrent()
        assertEquals(250L, voice.timing.value.startedAtElapsedMs)
        assertEquals(null, voice.timing.value.endedAtElapsedMs)

        advanceTimeBy(1_000L)
        runCurrent()
        voice.cancel()
        assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER), active.await())
        assertEquals(1_250L, voice.timing.value.endedAtElapsedMs)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1_250L, voice.timing.value.endedAtElapsedMs)
    }

    @Test fun callerCancellationAfterStartedFreezesTerminalElapsedTime() = runTest {
        val input = Input()
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.UNKNOWN), { _, _, _ -> 41L },
            elapsedRealtimeMs = { testScheduler.currentTime })
        val active = async { voice.record("caller-timing", 1234L, input) }
        runCurrent()
        input.emit(SpeechEvent.Started)
        runCurrent()
        advanceTimeBy(625L)
        runCurrent()

        active.cancel()
        active.join()
        assertTrue(active.isCancelled)
        assertEquals(0L, voice.timing.value.startedAtElapsedMs)
        assertEquals(625L, voice.timing.value.endedAtElapsedMs)
        assertEquals(VoiceState.Result(VoiceMemoOutcome.Cancelled(VoiceCancelReason.CALLER)), voice.state.value)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(625L, voice.timing.value.endedAtElapsedMs)
    }

    @Test fun eventFailureAfterStartedFreezesTerminalElapsedTime() = runTest {
        val input = object : SpeechInput {
            override fun events(): Flow<SpeechEvent> = flow {
                emit(SpeechEvent.Started)
                throw IllegalStateException("synthetic recognition failure")
            }
            override fun stopCapture() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.PARKED), { _, _, _ -> 41L },
            elapsedRealtimeMs = { testScheduler.currentTime })

        assertEquals(VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION),
            voice.record("error-timing", 1234L, input))
        assertEquals(0L, voice.timing.value.startedAtElapsedMs)
        assertEquals(0L, voice.timing.value.endedAtElapsedMs)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(0L, voice.timing.value.endedAtElapsedMs)
    }

    @Test fun cancellationBeforeStartedNeverCreatesElapsedTime() = runTest {
        val input = Input()
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> 41L },
            elapsedRealtimeMs = { testScheduler.currentTime })
        val active = async { voice.record("prestart-timing", 1234L, input) }
        runCurrent()
        assertEquals(VoiceState.Preparing, voice.state.value)

        voice.cancel()
        assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER), active.await())
        assertEquals(null, voice.timing.value.startedAtElapsedMs)
        assertEquals(null, voice.timing.value.endedAtElapsedMs)
    }

    private class Input : SpeechInput {
        private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)
        var stopCalls = 0
        var discardCalls = 0
        override fun events(): Flow<SpeechEvent> = channel.receiveAsFlow()
        fun emit(event: SpeechEvent) { check(channel.trySend(event).isSuccess) }
        override fun stopCapture() { stopCalls++; emit(SpeechEvent.RecognitionEnded) }
        override suspend fun discardIncomplete() { discardCalls++ }
        override fun cancel() = Unit
        override fun close() = Unit
    }
}
