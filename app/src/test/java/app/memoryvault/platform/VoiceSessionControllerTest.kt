package app.memoryvault.platform

import app.memoryvault.CaptureClock
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceCancelReason
import app.memoryvault.domain.VoiceMemoOutcome
import app.memoryvault.domain.VoiceMemoUseCase
import app.memoryvault.service.ReadyNotificationPolicy
import app.memoryvault.service.ReadyNotificationState
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
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionControllerTest {
    @Test fun transitionDuringReadiness_blocksTheNewCommandImmediately() = runTest {
        val fixture = Fixture(DriveState.UNKNOWN)
        val run = async { fixture.controller.record() }
        runCurrent()
        fixture.drive.value = DriveState.TRANSITION
        runCurrent()
        assertTrue("Transition must block the new command, including cold wait", run.isCompleted)
        assertEquals(VoiceServiceResult.NotStarted(VoiceStartBlock.UNKNOWN), run.await())
        assertTrue(fixture.inputs.isEmpty())
    }
    @Test fun coldUnknown_waitsWithoutSpeech_andTimesOutAtFiveSeconds() = runTest {
        val fixture = Fixture(DriveState.UNKNOWN)
        val run = async { fixture.controller.record() }
        runCurrent()
        assertEquals(VoiceServiceState.CheckingVehicle, fixture.controller.state.value)
        assertEquals(0, fixture.inputs.size)
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse(run.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(VoiceServiceResult.NotStarted(VoiceStartBlock.UNKNOWN), run.await())
        assertTrue(fixture.inputs.isEmpty())
    }

    @Test fun coldReadinessAndStorage_shareFiveSecondBudget_thenCaptureHasItsOwnDeadline() = runTest {
        val fixture = Fixture(DriveState.UNKNOWN, StorageReadiness.LOADING)
        val run = async { fixture.controller.record() }
        runCurrent()
        advanceTimeBy(2_000)
        fixture.drive.value = DriveState.DRIVING
        runCurrent()
        assertTrue(fixture.inputs.isEmpty())
        advanceTimeBy(2_000)
        fixture.storage.value = StorageReadiness.READY
        runCurrent()
        assertEquals(1, fixture.inputs.size)
        assertEquals(VoiceServiceState.Preparing, fixture.controller.state.value)
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(0, fixture.inputs.single().stopCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, fixture.inputs.single().stopCalls)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(VoiceServiceResult.Completed(VoiceMemoOutcome.TimedOut()), run.await())
    }

    @Test fun parkedDuringColdWait_blocksImmediatelyWithoutWaitingForStorage() = runTest {
        val fixture = Fixture(DriveState.UNKNOWN, StorageReadiness.LOADING)
        val run = async { fixture.controller.record() }
        runCurrent()
        fixture.drive.value = DriveState.PARKED
        runCurrent()
        assertEquals(VoiceServiceResult.NotStarted(VoiceStartBlock.PARKED), run.await())
        assertTrue(fixture.inputs.isEmpty())
    }

    @Test fun storageLoadingOrFailure_neverAcquiresSpeechOrClaimsSaved() = runTest {
        for (state in listOf(StorageReadiness.LOADING, StorageReadiness.FAILED)) {
            val fixture = Fixture(DriveState.DRIVING, state)
            val run = async { fixture.controller.record() }
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(VoiceServiceResult.NotStarted(VoiceStartBlock.STORAGE_UNAVAILABLE), run.await())
            assertTrue(fixture.inputs.isEmpty())
            assertTrue(fixture.saved.isEmpty())
        }
    }

    @Test fun eachCurrentPermissionGate_rejectsWithoutCreatingSpeech() = runTest {
        val fixtures = listOf(
            VoiceAvailability(microphone = false) to VoiceStartBlock.MICROPHONE,
            VoiceAvailability(notificationPermission = false) to VoiceStartBlock.NOTIFICATIONS,
            VoiceAvailability(notificationsEnabled = false) to VoiceStartBlock.NOTIFICATIONS,
            VoiceAvailability(recordingChannelEnabled = false) to VoiceStartBlock.RECORDING_CHANNEL,
            VoiceAvailability(sdkAvailable = false) to VoiceStartBlock.SDK_UNAVAILABLE,
        )
        for ((availability, reason) in fixtures) {
            val fixture = Fixture(DriveState.DRIVING)
            fixture.availability = availability
            assertEquals(VoiceServiceResult.NotStarted(reason), fixture.controller.record())
            assertTrue(fixture.inputs.isEmpty())
        }
    }

    @Test fun staleReadyAction_rechecksCurrentParkedStateAtInvocation() = runTest {
        val fixture = Fixture(DriveState.DRIVING)
        assertEquals(ReadyNotificationState.READY,
            ReadyNotificationPolicy.state(true, fixture.drive.value, fixture.availability, false))
        fixture.drive.value = DriveState.PARKED
        assertEquals(VoiceServiceResult.NotStarted(VoiceStartBlock.PARKED), fixture.controller.record())
        assertTrue(fixture.inputs.isEmpty())
        assertEquals(ReadyNotificationState.PARKED,
            ReadyNotificationPolicy.state(true, fixture.drive.value, fixture.availability, false))
        assertEquals(ReadyNotificationState.HIDDEN,
            ReadyNotificationPolicy.state(false, DriveState.DRIVING, fixture.availability, false))
        assertEquals(ReadyNotificationState.UNAVAILABLE,
            ReadyNotificationPolicy.state(true, DriveState.UNKNOWN, fixture.availability, false))
    }

    @Test fun cancellationAndDuplicateDuringReadiness_leaveNoOrphanOrSpeech() = runTest {
        val fixture = Fixture(DriveState.UNKNOWN)
        val run = async { fixture.controller.record() }
        runCurrent()
        assertEquals(VoiceServiceResult.NotStarted(VoiceStartBlock.BUSY), fixture.controller.record())
        fixture.controller.cancel()
        assertEquals(VoiceServiceResult.Completed(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER)), run.await())
        fixture.drive.value = DriveState.DRIVING
        runCurrent()
        assertTrue(fixture.inputs.isEmpty())
        val next = async { fixture.controller.record() }
        runCurrent()
        assertEquals(1, fixture.inputs.size)
        fixture.controller.cancel()
        next.await()
    }

    @Test fun notificationHiddenDuringCapture_cancelsWithinOneSecond_andTimerStops() = runTest {
        val fixture = Fixture(DriveState.DRIVING)
        val run = async { fixture.controller.record() }
        runCurrent()
        fixture.availability = VoiceAvailability(recordingChannelEnabled = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(VoiceServiceResult.Interrupted(VoiceStartBlock.RECORDING_CHANNEL), run.await())
        assertEquals(listOf("cancel", "close"), fixture.inputs.single().cleanup)
        val checks = fixture.gateChecks
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(checks, fixture.gateChecks)
        assertTrue(fixture.saved.isEmpty())
    }

    @Test fun revokedMicrophoneDuringReadiness_cancelsWithoutAcquiringSpeech() = runTest {
        val fixture = Fixture(DriveState.UNKNOWN)
        val run = async { fixture.controller.record() }
        runCurrent()
        fixture.availability = VoiceAvailability(microphone = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(VoiceServiceResult.Interrupted(VoiceStartBlock.MICROPHONE), run.await())
        assertTrue(fixture.inputs.isEmpty())
    }

    @Test fun actualSdkStartedAndCommittedSave_driveStatus_andUseInjectedCaptureClock() = runTest {
        val fixture = Fixture(DriveState.DRIVING)
        val run = async { fixture.controller.record() }
        runCurrent()
        assertEquals(VoiceServiceState.Preparing, fixture.controller.state.value)
        val input = fixture.inputs.single()
        input.events.trySend(SpeechEvent.Started)
        runCurrent()
        assertEquals(VoiceServiceState.Listening, fixture.controller.state.value)
        fixture.drive.value = DriveState.PARKED
        input.events.trySend(SpeechEvent.Final("  raw final  "))
        assertEquals(VoiceServiceResult.Completed(VoiceMemoOutcome.Saved(23L)), run.await())
        assertEquals(listOf(Triple("session-uuid", "  raw final  ", 1794117480000L)), fixture.saved)
    }

    @Test fun callerDestruction_cancelsReadinessAndCaptureAndClearsBusy() = runTest {
        for (drive in listOf(DriveState.UNKNOWN, DriveState.DRIVING)) {
            val fixture = Fixture(drive)
            val run = async { fixture.controller.record() }
            runCurrent()
            run.cancel()
            run.join()
            assertTrue(run.isCancelled)
            if (drive == DriveState.DRIVING) assertEquals(listOf("cancel", "close"), fixture.inputs.single().cleanup)
            fixture.drive.value = DriveState.PARKED
            assertEquals(VoiceServiceResult.NotStarted(VoiceStartBlock.PARKED), fixture.controller.record())
        }
    }

    private class Fixture(initialDrive: DriveState, initialStorage: StorageReadiness = StorageReadiness.READY) {
        val drive = MutableStateFlow(initialDrive)
        val storage = MutableStateFlow(initialStorage)
        var availability = VoiceAvailability()
        var gateChecks = 0
        val inputs = mutableListOf<Input>()
        val saved = mutableListOf<Triple<String, String, Long>>()
        val useCase = VoiceMemoUseCase(drive, { id, text, time -> saved += Triple(id, text, time); 23L })
        val controller = VoiceSessionController(
            drive, storage, useCase,
            availability = { gateChecks++; availability },
            startDrive = {},
            speechFactory = { Input().also(inputs::add) },
            captureClock = CaptureClock { 1794117480000L },
            newSessionId = { "session-uuid" },
        )
    }

    private class Input : SpeechInput {
        val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        var stopCalls = 0
        val cleanup = mutableListOf<String>()
        override fun events(): Flow<SpeechEvent> = events.receiveAsFlow()
        override fun stopCapture() { stopCalls++ }
        override fun cancel() { cleanup += "cancel" }
        override fun close() { cleanup += "close" }
    }
}
