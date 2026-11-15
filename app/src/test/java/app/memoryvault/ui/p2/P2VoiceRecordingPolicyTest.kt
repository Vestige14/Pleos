package app.memoryvault.ui.p2

import app.memoryvault.domain.DrivePolicy
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceDriveSafety
import app.memoryvault.domain.VoiceDriveSnapshot
import app.memoryvault.domain.VoiceMemoOutcome
import app.memoryvault.domain.VoiceMemoUseCase
import app.memoryvault.domain.VoiceState
import app.memoryvault.platform.SpeechEvent
import app.memoryvault.platform.SpeechInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class P2VoiceRecordingPolicyTest {
    @Test fun explicitParkedDrivingAndUnknownSessionsSaveTheExactFinalText() = runTest {
        for (state in listOf(DriveState.PARKED, DriveState.DRIVING, DriveState.UNKNOWN)) {
            val drive = MutableStateFlow(state)
            val snapshot = FakeDriveSafety(state)
            val input = Input()
            val saves = mutableListOf<Triple<String, String, Long>>()
            val voice = VoiceMemoUseCase(drive, { id, text, time ->
                saves += Triple(id, text, time)
                41L
            }, driveSafety = snapshot)

            val active = async { voice.record("session-${state.name}", 1234L, input) }
            runCurrent()
            assertEquals("$state must enter capture preparation", VoiceState.Preparing, voice.state.value)
            input.emit(SpeechEvent.Started)
            runCurrent()
            assertEquals(VoiceState.Listening, voice.state.value)
            input.emit(SpeechEvent.Final("  변경하지 않은 원문  "))
            assertEquals(VoiceMemoOutcome.Saved(41L), active.await())
            assertEquals(listOf(Triple("session-${state.name}", "  변경하지 않은 원문  ", 1234L)), saves)
            assertEquals(1, input.eventsCalls)
        }
    }

    @Test fun transitionRejectsNewSessionWithoutTouchingTheMicrophoneInput() = runTest {
        val input = Input()
        val saves = mutableListOf<String>()
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.TRANSITION), { _, text, _ ->
            saves += text
            41L
        })

        assertEquals(VoiceMemoOutcome.Blocked, voice.record("transition", 1234L, input))
        assertEquals(VoiceState.Idle, voice.state.value)
        assertEquals(0, input.eventsCalls)
        assertEquals(0, input.stopCalls)
        assertTrue(saves.isEmpty())
    }

    @Test fun acceptedSessionContinuesThroughVehicleStateAndGenerationChanges() = runTest {
        val drive = MutableStateFlow(DriveState.PARKED)
        val safety = FakeDriveSafety(DriveState.PARKED)
        val input = Input()
        val saves = mutableListOf<String>()
        val voice = VoiceMemoUseCase(drive, { _, text, _ -> saves += text; 41L }, driveSafety = safety)
        val active = async { voice.record("one-session", 1234L, input) }
        runCurrent()
        input.emit(SpeechEvent.Started)
        runCurrent()
        assertEquals(VoiceState.Listening, voice.state.value)

        for ((state, generation) in listOf(
            DriveState.TRANSITION to 0L,
            DriveState.DRIVING to 0L,
            DriveState.UNKNOWN to 1L,
            DriveState.PARKED to 1L,
        )) {
            safety.set(state, generation)
            drive.value = state
            runCurrent()
            assertFalse("A vehicle-only change must not cancel a user-started session", active.isCompleted)
        }

        input.emit(SpeechEvent.Final("same session"))
        assertEquals(VoiceMemoOutcome.Saved(41L), active.await())
        assertEquals(listOf("same session"), saves)
        assertEquals(1, input.eventsCalls)
    }

    @Test fun recordingAdmissionDoesNotUnlockPrivateReviewEditOrExport() {
        for (state in listOf(DriveState.DRIVING, DriveState.UNKNOWN, DriveState.TRANSITION)) {
            assertFalse(DrivePolicy.canEdit(state))
            assertFalse(DrivePolicy.canExport(state))
        }
        assertTrue(DrivePolicy.canEdit(DriveState.PARKED))
        assertTrue(DrivePolicy.canExport(DriveState.PARKED))
    }

    private class FakeDriveSafety(initial: DriveState) : VoiceDriveSafety {
        private val snapshots = MutableStateFlow(VoiceDriveSnapshot(initial, 0L))
        override fun current(): VoiceDriveSnapshot = snapshots.value
        override suspend fun awaitInvalidation(generation: Long) {
            snapshots.first { it.state == DriveState.UNKNOWN || it.invalidationGeneration != generation }
        }
        fun set(state: DriveState, generation: Long) { snapshots.value = VoiceDriveSnapshot(state, generation) }
    }

    private class Input : SpeechInput {
        private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)
        var eventsCalls = 0
        var stopCalls = 0
        override fun events(): Flow<SpeechEvent> { eventsCalls++; return channel.receiveAsFlow() }
        fun emit(event: SpeechEvent) { check(channel.trySend(event).isSuccess) }
        override fun stopCapture() { stopCalls++ }
        override fun cancel() = Unit
        override fun close() = Unit
    }
}
