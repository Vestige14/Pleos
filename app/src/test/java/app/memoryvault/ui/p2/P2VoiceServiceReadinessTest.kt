package app.memoryvault.ui.p2

import app.memoryvault.CaptureClock
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceCancelReason
import app.memoryvault.domain.VoiceMemoOutcome
import app.memoryvault.domain.VoiceMemoUseCase
import app.memoryvault.platform.SpeechEvent
import app.memoryvault.platform.SpeechInput
import app.memoryvault.service.StorageReadiness
import app.memoryvault.service.VoiceAvailability
import app.memoryvault.service.VoiceServiceResult
import app.memoryvault.service.VoiceServiceState
import app.memoryvault.service.VoiceSessionController
import app.memoryvault.service.VoiceStartBlock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class P2VoiceServiceReadinessTest {
    @Test fun unknownReadyStartsImmediatelyEvenIfVehicleReconnectThrows() = runTest {
        val fixture = Fixture(DriveState.UNKNOWN, startDrive = { error("vehicle disconnected") })
        val active = async { fixture.controller.record() }
        runCurrent()
        assertEquals("Vehicle uncertainty is no longer a recording prerequisite", 1, fixture.inputs.size)
        assertEquals(VoiceServiceState.Preparing, fixture.controller.state.value)
        fixture.inputs.single().emit(SpeechEvent.Started)
        fixture.inputs.single().emit(SpeechEvent.Final("unknown state raw final"))
        assertEquals(VoiceServiceResult.Completed(VoiceMemoOutcome.Saved(41L)), active.await())
        assertEquals(listOf("unknown state raw final"), fixture.saves)
        assertEquals(listOf("cancel", "close"), fixture.inputs.single().cleanup)
    }

    @Test fun parkedLoadingUsesExistingFiveSecondStorageBudgetWithoutAcquiringMicrophone() = runTest {
        val fixture = Fixture(DriveState.PARKED, StorageReadiness.LOADING)
        val active = async { fixture.controller.record() }
        runCurrent()
        assertEquals(VoiceServiceState.CheckingVehicle, fixture.controller.state.value)
        assertTrue(fixture.inputs.isEmpty())
        advanceTimeBy(4_999L)
        runCurrent()
        assertFalse(active.isCompleted)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(VoiceServiceResult.NotStarted(VoiceStartBlock.STORAGE_UNAVAILABLE), active.await())
        assertTrue(fixture.inputs.isEmpty())
    }

    @Test fun failedStorageAndMissingMicrophonePermissionNeverCreateSpeechInput() = runTest {
        for (state in listOf(DriveState.PARKED, DriveState.DRIVING, DriveState.UNKNOWN)) {
            val failedStore = Fixture(state, StorageReadiness.FAILED)
            assertEquals(VoiceServiceResult.NotStarted(VoiceStartBlock.STORAGE_UNAVAILABLE),
                failedStore.controller.record())
            assertTrue(failedStore.inputs.isEmpty())

            val denied = Fixture(state)
            denied.availability = VoiceAvailability(microphone = false)
            assertEquals(VoiceServiceResult.NotStarted(VoiceStartBlock.MICROPHONE), denied.controller.record())
            assertTrue(denied.inputs.isEmpty())
        }
    }

    @Test fun rapidDuplicateRequestAndExplicitCancellationReleaseTheSingleInput() = runTest {
        val fixture = Fixture(DriveState.DRIVING)
        val active = async { fixture.controller.record() }
        runCurrent()
        assertEquals(1, fixture.inputs.size)
        assertEquals(VoiceServiceResult.NotStarted(VoiceStartBlock.BUSY), fixture.controller.record())
        assertEquals(1, fixture.inputs.size)
        fixture.controller.cancel()
        assertEquals(VoiceServiceResult.Completed(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER)), active.await())
        assertEquals(listOf("cancel", "close"), fixture.inputs.single().cleanup)
        assertTrue(fixture.saves.isEmpty())

        val next = async { fixture.controller.record() }
        runCurrent()
        assertEquals("Cancellation must release the single capture slot", 2, fixture.inputs.size)
        fixture.controller.cancel()
        assertEquals(VoiceServiceResult.Completed(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER)), next.await())
    }

    @Test fun permissionLossDuringCaptureInterruptsAndDoesNotSave() = runTest {
        val fixture = Fixture(DriveState.UNKNOWN)
        val active = async { fixture.controller.record() }
        runCurrent()
        assertEquals(1, fixture.inputs.size)
        fixture.availability = VoiceAvailability(microphone = false)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(VoiceServiceResult.Interrupted(VoiceStartBlock.MICROPHONE), active.await())
        assertEquals(listOf("cancel", "close"), fixture.inputs.single().cleanup)
        assertTrue(fixture.saves.isEmpty())
    }

    @Test fun transitionDuringInputCreationReleasesTheAcquiredInputWithoutStarting() = runTest {
        val fixture = Fixture(DriveState.DRIVING)
        fixture.onInputCreated = { fixture.drive.value = DriveState.TRANSITION }

        assertEquals(VoiceServiceResult.NotStarted(VoiceStartBlock.UNKNOWN), fixture.controller.record())
        assertEquals(1, fixture.inputs.size)
        assertEquals(listOf("cancel", "close"), fixture.inputs.single().cleanup)
        assertTrue(fixture.saves.isEmpty())
    }

    @Test fun cancellationDuringInputCreationReleasesOnlyTheUnacceptedInput() = runTest {
        val fixture = Fixture(DriveState.DRIVING)
        fixture.onInputCreated = { fixture.controller.cancel() }

        assertEquals(VoiceServiceResult.Completed(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER)),
            fixture.controller.record())
        assertEquals(1, fixture.inputs.size)
        assertEquals(listOf("cancel", "close"), fixture.inputs.single().cleanup)
        assertTrue(fixture.saves.isEmpty())
    }

    @Test fun busyUseCaseReleasesTheControllerInputWithoutTouchingTheAcceptedSession() = runTest {
        val fixture = Fixture(DriveState.DRIVING)
        val occupied = async { fixture.voice.record("already-accepted", 1234L, Input()) }
        runCurrent()

        assertEquals(VoiceServiceResult.NotStarted(VoiceStartBlock.BUSY), fixture.controller.record())
        assertEquals(1, fixture.inputs.size)
        assertEquals(listOf("cancel", "close"), fixture.inputs.single().cleanup)
        assertFalse(occupied.isCompleted)

        fixture.voice.cancel()
        assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER), occupied.await())
    }

    private class Fixture(
        state: DriveState,
        readiness: StorageReadiness = StorageReadiness.READY,
        startDrive: () -> Unit = {},
    ) {
        val drive = MutableStateFlow(state)
        val storage = MutableStateFlow(readiness)
        var availability = VoiceAvailability()
        var onInputCreated: () -> Unit = {}
        val inputs = mutableListOf<Input>()
        val saves = mutableListOf<String>()
        val voice = VoiceMemoUseCase(drive, { _, text, _ -> saves += text; 41L })
        val controller = VoiceSessionController(drive, storage, voice, { availability }, startDrive,
            speechFactory = { Input().also { inputs += it; onInputCreated() } }, captureClock = CaptureClock { 1234L },
            newSessionId = { "single-command" })
    }

    private class Input : SpeechInput {
        private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)
        val cleanup = mutableListOf<String>()
        override fun events(): Flow<SpeechEvent> = channel.receiveAsFlow()
        fun emit(event: SpeechEvent) { check(channel.trySend(event).isSuccess) }
        override fun stopCapture() = Unit
        override fun cancel() { cleanup += "cancel" }
        override fun close() { cleanup += "close" }
    }
}
