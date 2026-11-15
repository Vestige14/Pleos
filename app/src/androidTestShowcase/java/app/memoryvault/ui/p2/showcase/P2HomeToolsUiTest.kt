package app.memoryvault.ui.p2.showcase

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.R
import app.memoryvault.TypedMemoResult
import app.memoryvault.data.DayDraft
import app.memoryvault.data.LinkState
import app.memoryvault.data.Photo
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceCaptureTiming
import app.memoryvault.domain.VoiceFailure
import app.memoryvault.domain.VoiceMemoOutcome
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.platform.PhotoAccess
import app.memoryvault.platform.PhotoPermissions
import app.memoryvault.service.VoiceServiceResult
import app.memoryvault.service.VoiceServiceState
import app.memoryvault.service.VoiceStartRequest
import app.memoryvault.ui.DayDraftState
import app.memoryvault.ui.MemoryVaultScreen
import app.memoryvault.ui.MemoryVaultTheme
import app.memoryvault.ui.MemoryVaultUi
import app.memoryvault.ui.PhotoReviewResult
import app.memoryvault.ui.PhotoReviewState
import app.memoryvault.ui.PhotoThumbnailEpoch
import app.memoryvault.ui.VoiceControls
import app.memoryvault.ui.VoicePanelScreen
import app.memoryvault.ui.components.P2VoiceNoticeWindow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Product composables with in-memory data only; no product services, media, or real permissions. */
class P2HomeToolsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ShowcaseHostActivity>()
    private val isolated = ShowcaseContext(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun albumShowsUnlinkedThumbnailsWithoutInventingACandidateOrDecision() {
        val ui = HomeToolsUi()
        home(ui)
        compose.onNodeWithTag("open-photo-album").performScrollTo().performClick()
        compose.onNodeWithTag("p2-unlinked-photo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("사진 후보").assertDoesNotExist()
        compose.onNodeWithText("사진 확정됨").assertDoesNotExist()
        compose.onNodeWithText("사진 확정").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(ui.thumbnailReads.contains("synthetic://unlinked"))
            assertEquals(LinkState.UNLINKED, ui.photo.linkState)
            assertEquals(0, ui.decisions)
        }
    }

    @Test fun deniedAlbumDoesNotRenderOrLoadUnlinkedPhotos() {
        val ui = HomeToolsUi()
        ui.photoReview.value = PhotoReviewState(PhotoPermissions(PhotoAccess.DENIED, false))
        home(ui)
        compose.onNodeWithTag("open-photo-album").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.photo_access_denied)).assertIsDisplayed()
        compose.onAllNodesWithTag("p2-unlinked-photo").assertCountEquals(0)
        compose.runOnIdle { assertTrue(ui.thumbnailReads.isEmpty()); assertEquals(0, ui.decisions) }
    }

    @Test fun staleGenerationInvalidEpochAndStopCannotRestoreTheAlbum() {
        val ui = HomeToolsUi()
        home(ui)
        val oldOpen = requireNotNull(compose.onNodeWithTag("open-photo-album")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.onNodeWithTag("open-photo-album").performScrollTo().performClick()
        compose.onNodeWithTag("p2-unlinked-photo").assertExists()
        compose.runOnIdle {
            ui.allowImages = false
            ui.photoReview.value = ui.photoReview.value.copy(thumbnailEpoch = PhotoThumbnailEpoch(2, 0))
        }
        compose.onNodeWithTag("p2-unlinked-photo").assertDoesNotExist()
        compose.runOnIdle {
            ui.driveSnapshot.value = ui.driveSnapshot.value.copy(invalidationGeneration = 1)
            oldOpen()
        }
        compose.onNodeWithTag("p2-photo-album-page").assertDoesNotExist()
        compose.onNodeWithTag("open-photo-album").performScrollTo().performClick()
        compose.onNodeWithTag("p2-photo-album-page").assertExists()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("p2-photo-album-page").assertDoesNotExist()
        compose.onNodeWithTag("open-photo-album").assertExists()
    }

    @Test fun settingsUsesExistingCallbacksAndReturnsHomeWithoutAStalePermissionRequest() {
        val ui = HomeToolsUi()
        var voicePermissions = 0
        var photoPermissions = 0
        content { MemoryVaultScreen(ui, { voicePermissions++ }, onRequestPhotoPermissions = { photoPermissions++ }) }
        compose.onNodeWithTag("open-settings").performScrollTo().performClick()
        val permission = compose.onNodeWithText(text(R.string.voice_permissions_setup))
        permission.performScrollTo().performClick()
        val oldPermission = requireNotNull(permission.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.onNodeWithText(text(R.string.voice_ready_enable_button)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.photo_permissions_setup)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.photo_rescan)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ai_enter_setup)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, voicePermissions); assertEquals(1, photoPermissions)
            assertEquals(1, ui.readyChanges); assertEquals(1, ui.refreshes)
            // The existing intent callback is observed; ShowcaseContext blocks actual forwarding.
            assertEquals(1, isolated.launchAttempts.get())
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("p2-settings-page").assertDoesNotExist()
        compose.runOnIdle { oldPermission(); assertEquals(1, voicePermissions) }
        compose.onNodeWithTag("open-settings").performScrollTo().performClick()
        compose.onNodeWithTag("p2-settings-home").performScrollTo().performClick()
        compose.onNodeWithTag("p2-settings-page").assertDoesNotExist()
    }

    @Test fun successExpiresAfterTwoSecondsAndReentryKeepsServiceAndTimingUntouched() {
        compose.mainClock.autoAdvance = false
        val ui = HomeToolsUi()
        val saved = VoiceServiceState.Finished(VoiceServiceResult.Completed(VoiceMemoOutcome.Saved(1001)))
        val timing = VoiceCaptureTiming(100, 900)
        ui.voiceState.value = saved; ui.voiceTiming.value = timing
        val shown = mutableStateOf(true)
        val window = P2VoiceNoticeWindow()
        content {
            val state by ui.voiceState.collectAsState()
            val marks by ui.voiceTiming.collectAsState()
            if (shown.value) VoiceControls(DriveState.PARKED, state, { ui.startVoice() }, ui::cancelVoice,
                onFinish = { ui.finishVoice() }, timing = marks, noticeWindow = window,
                nowElapsedMs = { compose.mainClock.currentTime })
            else Text("CLOSED")
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.voice_saved)).assertIsDisplayed()
        compose.onNodeWithTag("p2-record-elapsed").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2_001)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.voice_saved)).assertDoesNotExist()
        compose.onNodeWithTag("p2-record-elapsed").assertDoesNotExist()
        compose.onNodeWithTag("p2-record-start").assertIsDisplayed()
        compose.runOnIdle { shown.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { shown.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.voice_saved)).assertDoesNotExist()
        compose.onNodeWithTag("p2-record-elapsed").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(saved, ui.voiceState.value); assertEquals(timing, ui.voiceTiming.value)
            assertEquals(0, ui.starts); assertEquals(0, ui.cancels); assertEquals(0, ui.finishes)
        }
    }

    @Test fun oldSuccessDelayCannotEraseNewListeningOrAStorageFailure() {
        compose.mainClock.autoAdvance = false
        val voice = mutableStateOf<VoiceServiceState>(VoiceServiceState.Finished(
            VoiceServiceResult.Completed(VoiceMemoOutcome.Saved(1002))))
        val timing = mutableStateOf(VoiceCaptureTiming(100, 900))
        val window = P2VoiceNoticeWindow()
        content { VoiceControls(DriveState.PARKED, voice.value, {}, {}, timing = timing.value,
            noticeWindow = window, nowElapsedMs = { compose.mainClock.currentTime }) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { voice.value = VoiceServiceState.Listening; timing.value = VoiceCaptureTiming(1_000, 1_500) }
        compose.mainClock.advanceTimeBy(2_001)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("p2-record-finish").assertIsDisplayed()
        compose.onNodeWithTag("p2-record-elapsed").assertIsDisplayed()
        compose.runOnIdle { voice.value = VoiceServiceState.Finished(VoiceServiceResult.Completed(VoiceMemoOutcome.Failed(VoiceFailure.STORAGE))) }
        compose.mainClock.advanceTimeBy(5_000)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.p2_record_storage_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.p2_record_idle)).assertDoesNotExist()
    }

    @Test fun drivingFooterIsAbsentFromParkedAndUnknownPanels() {
        val ui = HomeToolsUi()
        ui.driveSnapshot.value = DriveSnapshot(state = DriveState.DRIVING)
        content { VoicePanelScreen(ui, {}) }
        compose.onNodeWithTag("p2-driving-record-only").assertIsDisplayed()
        compose.onNodeWithText("메인 화면으로").assertDoesNotExist()
        compose.runOnIdle { ui.driveSnapshot.value = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 1) }
        compose.onNodeWithTag("p2-driving-record-only").assertDoesNotExist()
        compose.onNodeWithText("메인 화면으로").assertExists()
        compose.runOnIdle { ui.driveSnapshot.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 2) }
        compose.onNodeWithTag("p2-driving-record-only").assertDoesNotExist()
    }

    private fun home(ui: HomeToolsUi) = content { MemoryVaultScreen(ui, {}) }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun content(body: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalContext provides isolated) { MemoryVaultTheme(body) }
    }
}

private class HomeToolsUi : MemoryVaultUi {
    override val driveSnapshot = MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))
    override val voiceState = MutableStateFlow<VoiceServiceState>(VoiceServiceState.Idle)
    override val voiceTiming = MutableStateFlow(VoiceCaptureTiming())
    override val readyNotificationEnabled = MutableStateFlow(false)
    override val photoReview = MutableStateFlow(PhotoReviewState(PhotoPermissions(PhotoAccess.FULL, true),
        thumbnailEpoch = PhotoThumbnailEpoch(1, 0)))
    val photo = Photo("synthetic://unlinked", 0, null, null, null, LinkState.UNLINKED)
    override val day = MutableStateFlow<DayDraftState>(DayDraftState.Ready(
        DayDraft("2026-11-08", emptyList(), unlinkedPhotos = listOf(photo))))
    val thumbnailReads = mutableListOf<String>()
    var allowImages = true
    var decisions = 0
    var refreshes = 0
    var readyChanges = 0
    var starts = 0
    var cancels = 0
    var finishes = 0
    override fun thumbnailAllowed(epoch: PhotoThumbnailEpoch) = allowImages &&
        driveSnapshot.value.state == DriveState.PARKED && photoReview.value.permissions.access != PhotoAccess.DENIED &&
        photoReview.value.thumbnailEpoch == epoch && driveSnapshot.value.invalidationGeneration == epoch.driveGeneration
    override suspend fun loadPhotoThumbnail(uri: String, epoch: PhotoThumbnailEpoch): ImageBitmap? {
        if (!thumbnailAllowed(epoch)) return null
        thumbnailReads += uri
        return ImageBitmap(2, 2)
    }
    override fun refreshPhotos() { refreshes++ }
    override suspend fun setPhotoState(uri: String, state: LinkState): PhotoReviewResult { decisions++; return PhotoReviewResult.BLOCKED }
    override suspend fun addTypedNote(visitId: String, text: String) = TypedMemoResult.NOT_EDITABLE
    override suspend fun updateTypedNote(noteId: Long, text: String) = TypedMemoResult.NOT_EDITABLE
    override fun setReadyNotificationEnabled(enabled: Boolean): Boolean {
        readyChanges++; readyNotificationEnabled.value = enabled; return true
    }
    override fun retryVehicleConnection() = Unit
    override fun startVoice(): VoiceStartRequest { starts++; return VoiceStartRequest.Dispatched }
    override fun cancelVoice() { cancels++ }
    override fun finishVoice(): Boolean { finishes++; return true }
}
