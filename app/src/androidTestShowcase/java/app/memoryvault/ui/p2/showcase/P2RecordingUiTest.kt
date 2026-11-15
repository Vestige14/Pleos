package app.memoryvault.ui.p2.showcase

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.TypedMemoResult
import app.memoryvault.data.DayDraft
import app.memoryvault.data.LinkState
import app.memoryvault.data.Note
import app.memoryvault.data.NoteKind
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceCancelReason
import app.memoryvault.domain.VoiceCaptureTiming
import app.memoryvault.domain.VoiceFailure
import app.memoryvault.domain.VoiceMemoOutcome
import app.memoryvault.platform.DriveReadinessReason
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.ui.DayDraftState
import app.memoryvault.ui.MemoryVaultScreen
import app.memoryvault.ui.MemoryVaultTheme
import app.memoryvault.ui.MemoryVaultUi
import app.memoryvault.ui.PhotoReviewResult
import app.memoryvault.ui.PhotoReviewState
import app.memoryvault.ui.PhotoThumbnailEpoch
import app.memoryvault.ui.VoicePanelScreen
import app.memoryvault.service.VoiceServiceResult
import app.memoryvault.service.VoiceServiceState
import app.memoryvault.service.VoiceStartRequest
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Isolated showcase APK: product composables, synthetic state, and no product service calls. */
class P2RecordingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ShowcaseHostActivity>()

    @Test fun parkedHomePlacesRecordAndShareAboveLongRecords() {
        val ui = RecordingUi(DriveState.PARKED)
        var shareOpens = 0
        home(ui, onShare = { shareOpens++ })

        val start = compose.onNodeWithTag("p2-record-start").assertIsDisplayed().getUnclippedBoundsInRoot()
        compose.onNodeWithText("녹음 시작").assertIsDisplayed()
        val share = compose.onNodeWithTag("open-share").assertIsDisplayed().getUnclippedBoundsInRoot()
        val records = compose.onNodeWithText("기록과 검토").getUnclippedBoundsInRoot()
        assertTrue("Record must be the first action", start.top <= share.top)
        assertTrue("Both actions must precede records", share.bottom <= records.top)
        assertTrue("Recording target must be at least 64dp", start.bottom - start.top >= 64.dp)
        compose.onNodeWithTag("p2-drive-badge").assertTextContains("주/정차")
        compose.onNodeWithTag("open-share").performClick()
        compose.runOnIdle { assertEquals(1, shareOpens) }
    }

    @Test fun drivingHomeShowsOnlyRecordingCardAndHidesPrivateContent() {
        val ui = RecordingUi(DriveState.DRIVING)
        home(ui)

        compose.onNodeWithTag("p2-record-card").assertIsDisplayed()
        compose.onNodeWithTag("p2-record-start").assertIsDisplayed()
        compose.onNodeWithText("녹음 시작").assertIsDisplayed()
        compose.onNodeWithText("PRIVATE_MARKER_SYNTHETIC").assertDoesNotExist()
        compose.onNodeWithText("기록과 검토").assertDoesNotExist()
        compose.onNodeWithText("권한 및 연결 설정").assertDoesNotExist()
        compose.onNodeWithText("시연 녹음 시각", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("open-share").assertDoesNotExist()
        compose.onNodeWithTag("p2-drive-badge").assertTextContains("주행")
    }

    @Test fun unknownCanStartButStaleTransitionClickCannot() {
        val ui = RecordingUi(DriveState.UNKNOWN)
        home(ui)

        val start = compose.onNodeWithTag("p2-record-start").assertIsDisplayed()
        compose.onNodeWithTag("p2-drive-badge").assertDoesNotExist()
        compose.onNodeWithText("PRIVATE_MARKER_SYNTHETIC").assertDoesNotExist()
        compose.onNodeWithTag("open-share").assertDoesNotExist()
        val staleAction = requireNotNull(start.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle {
            ui.driveSnapshot.value = DriveSnapshot(state = DriveState.TRANSITION, invalidationGeneration = 1)
            staleAction()
            assertEquals(0, ui.starts)
            ui.driveSnapshot.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 2)
        }
        compose.onNodeWithTag("p2-record-start").performClick()
        compose.runOnIdle { assertEquals(1, ui.starts) }
    }

    @Test fun staleBusyClickCannotDispatchAnotherStart() {
        val ui = RecordingUi(DriveState.PARKED)
        home(ui)
        val start = compose.onNodeWithTag("p2-record-start").assertIsDisplayed()
        val staleAction = requireNotNull(start.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle {
            ui.voiceState.value = VoiceServiceState.Preparing
            staleAction()
            assertEquals(0, ui.starts)
        }
        compose.onNodeWithTag("p2-record-start").assertDoesNotExist()
    }

    @Test fun listeningFinishAndCancelAreSeparateCommands() {
        val ui = RecordingUi(DriveState.DRIVING)
        ui.voiceState.value = VoiceServiceState.Listening
        panel(ui)

        compose.onNodeWithText("녹음 종료").assertIsDisplayed()
        compose.onNodeWithTag("p2-record-finish").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, ui.finishes); assertEquals(0, ui.cancels) }
        compose.onNodeWithText("메모가 저장되었습니다.").assertDoesNotExist()
        compose.onNodeWithText("녹음 취소").assertIsDisplayed()
        compose.onNodeWithTag("p2-record-cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, ui.finishes); assertEquals(1, ui.cancels) }
    }

    @Test fun preparingAndProcessingOfferCancelWithoutFinish() {
        val ui = RecordingUi(DriveState.DRIVING)
        ui.voiceState.value = VoiceServiceState.Preparing
        panel(ui)
        compose.onNodeWithTag("p2-record-cancel").assertIsDisplayed()
        compose.onNodeWithTag("p2-record-finish").assertDoesNotExist()
        compose.runOnIdle { ui.voiceState.value = VoiceServiceState.Processing }
        compose.onNodeWithTag("p2-record-cancel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("p2-record-finish").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, ui.cancels); assertEquals(0, ui.finishes) }
    }

    @Test fun activeCaptureKeepsFinishOnTransitionWithoutOpeningPrivateUi() {
        val ui = RecordingUi(DriveState.TRANSITION)
        ui.voiceState.value = VoiceServiceState.Listening
        home(ui)
        compose.onNodeWithTag("p2-record-start").assertDoesNotExist()
        compose.onNodeWithTag("p2-record-finish").assertIsDisplayed().performClick()
        compose.onNodeWithTag("open-share").assertDoesNotExist()
        compose.onNodeWithText("PRIVATE_MARKER_SYNTHETIC").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, ui.finishes) }
    }

    @Test fun terminalMessagesSeparateSavedCancelledAndStorageFailure() {
        val ui = RecordingUi(DriveState.UNKNOWN)
        ui.voiceState.value = VoiceServiceState.Finished(
            VoiceServiceResult.Completed(VoiceMemoOutcome.Saved(1)))
        panel(ui)
        compose.onNodeWithText("메모가 저장되었습니다.").assertIsDisplayed()
        compose.runOnIdle { ui.voiceState.value = VoiceServiceState.Finished(
            VoiceServiceResult.Completed(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER))) }
        compose.onNodeWithText("녹음이 중단되었습니다.").assertIsDisplayed()
        compose.runOnIdle { ui.voiceState.value = VoiceServiceState.Finished(
            VoiceServiceResult.Completed(VoiceMemoOutcome.Failed(VoiceFailure.STORAGE))) }
        compose.onNodeWithText("메모 저장을 완료하지 못했습니다.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("p2-drive-badge").assertDoesNotExist()
    }

    @Test fun elapsedTimeFreezesAfterCaptureAndSurvivesRecomposition() {
        val ui = RecordingUi(DriveState.PARKED)
        ui.voiceState.value = VoiceServiceState.Listening
        ui.voiceTiming.value = VoiceCaptureTiming(startedAtElapsedMs = 2_000, endedAtElapsedMs = 10_000)
        panel(ui)
        compose.onNodeWithTag("p2-record-elapsed").assertTextContains("00:08")
        compose.runOnIdle { ui.voiceState.value = VoiceServiceState.Processing }
        compose.onNodeWithTag("p2-record-elapsed").assertTextContains("00:08")
        compose.runOnIdle {
            ui.driveSnapshot.value = ui.driveSnapshot.value.copy(invalidationGeneration = 1)
        }
        compose.onNodeWithTag("p2-record-elapsed").assertTextContains("00:08")
    }

    @Test fun narrowLargeTextKeepsBothPrimaryActionsReachable() {
        val ui = RecordingUi(DriveState.PARKED)
        home(ui, widthDp = 320, fontScale = 1.6f)
        val start = compose.onNodeWithTag("p2-record-start").assertIsDisplayed().getUnclippedBoundsInRoot()
        compose.onNodeWithTag("open-share").performScrollTo().assertIsDisplayed()
        val share = compose.onNodeWithTag("open-share").getUnclippedBoundsInRoot()
        assertTrue(start.bottom - start.top >= 64.dp)
        assertTrue(share.bottom - share.top >= 64.dp)
        assertTrue(start.top < share.top)
    }

    @Test fun parkedPanelUsesParkingCopyAndSameRecordCommand() {
        val ui = RecordingUi(DriveState.PARKED)
        panel(ui)
        compose.onNodeWithText("주/정차 중 설정").assertIsDisplayed()
        compose.onNodeWithTag("p2-record-start").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, ui.starts) }
    }

    private fun home(ui: RecordingUi, widthDp: Int? = null, fontScale: Float? = null,
        onShare: () -> Unit = {}) {
        val isolated = ShowcaseContext(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides isolated,
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                MemoryVaultTheme {
                    Box(if (widthDp == null) Modifier.fillMaxSize() else Modifier.width(widthDp.dp)) {
                        MemoryVaultScreen(ui, onRequestPermissions = {}, onShareDay = onShare)
                    }
                }
            }
        }
    }

    private fun panel(ui: RecordingUi) {
        val isolated = ShowcaseContext(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setContent { CompositionLocalProvider(LocalContext provides isolated) {
            MemoryVaultTheme { VoicePanelScreen(ui, onOpenParkedSettings = {}) }
        } }
    }
}

private class RecordingUi(initialDrive: DriveState) : MemoryVaultUi {
    override val driveSnapshot = MutableStateFlow(DriveSnapshot(state = initialDrive,
        readinessReason = if (initialDrive == DriveState.UNKNOWN) DriveReadinessReason.PERMISSION_DENIED
            else DriveReadinessReason.READY))
    override val voiceState = MutableStateFlow<VoiceServiceState>(VoiceServiceState.Idle)
    override val voiceTiming = MutableStateFlow(VoiceCaptureTiming())
    override val readyNotificationEnabled = MutableStateFlow(false)
    override val photoReview = MutableStateFlow(PhotoReviewState())
    override val day = MutableStateFlow<DayDraftState>(DayDraftState.Ready(DayDraft(
        "2026-11-08", emptyList(), (1L..16L).map { id ->
            Note(id, null, if (id == 1L) "PRIVATE_MARKER_SYNTHETIC" else "합성 음성 메모 $id",
                NoteKind.VOICE, id, sourceDate = "2026-11-08")
        })))
    var starts = 0
    var cancels = 0
    var finishes = 0
    override fun thumbnailAllowed(epoch: PhotoThumbnailEpoch) = false
    override suspend fun loadPhotoThumbnail(uri: String, epoch: PhotoThumbnailEpoch): ImageBitmap? = null
    override fun refreshPhotos() = Unit
    override suspend fun setPhotoState(uri: String, state: LinkState) = PhotoReviewResult.BLOCKED
    override suspend fun addTypedNote(visitId: String, text: String) = TypedMemoResult.NOT_EDITABLE
    override suspend fun updateTypedNote(noteId: Long, text: String) = TypedMemoResult.NOT_EDITABLE
    override fun setReadyNotificationEnabled(enabled: Boolean) = false
    override fun retryVehicleConnection() = Unit
    override fun startVoice(): VoiceStartRequest {
        starts++
        voiceState.value = VoiceServiceState.CheckingVehicle
        return VoiceStartRequest.Dispatched
    }
    override fun cancelVoice() { cancels++ }
    override fun finishVoice(): Boolean { finishes++; return true }
}
