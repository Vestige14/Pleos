package app.memoryvault.share.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.share.*
import app.memoryvault.ui.MemoryVaultTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ShareFlowScreenTest {
    @get:Rule val compose = createComposeRule()
    private val snapshots = MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))
    private val foreground = MutableStateFlow(true)
    private val safety = ShareSessionGate(snapshots).apply { resume() }
    private val controller = ShareFlowController(Store())
    private var exports = 0
    private var exits = 0

    // Rendering the original day or re-defaulting an edit would expose café/p3 in preview.
    @Test fun visitPhotoCaptionChoicesPreviewAndBackKeepOnlyExplicitShareValues() {
        show()
        compose.onNodeWithTag("share-visit-cafe").performScrollTo().performClick()
        compose.onNodeWithTag("share-visit-beach").performScrollTo()
        compose.onNodeWithTag("share-photo-beach-1").performScrollTo().performClick()
        compose.onNodeWithTag("share-caption-beach").performScrollTo().performTextInput("  바다에서 한참 놀았어요  ")
        compose.onNodeWithTag("share-preview-button").performScrollTo().performClick()
        compose.onNodeWithTag("share-preview").assertExists()
        compose.onNodeWithText("카페", substring = true).assertDoesNotExist()
        compose.onNodeWithText("바다에서 한참 놀았어요").assertExists()
        compose.onNodeWithTag("share-preview-photo-2-0").assertExists()
        compose.onNodeWithTag("share-preview-photo-2-1").assertDoesNotExist()
        compose.onNodeWithTag("share-back-select").performScrollTo().performClick()
        compose.onNodeWithTag("share-select").assertExists()
        compose.onNodeWithTag("share-preview-button").performScrollTo().performClick()
        compose.onNodeWithText("카페", substring = true).assertDoesNotExist()
        assertEquals(0, exports)
    }

    @Test fun noIncludedVisitsDisablesPreview() {
        show()
        for (id in listOf("restaurant", "beach", "cafe")) compose.onNodeWithTag("share-visit-$id").performScrollTo().performClick()
        compose.onNodeWithTag("share-preview-button").performScrollTo().assertIsNotEnabled()
    }

    // Stale UI callbacks must check the actual source before exporting, even before recomposition.
    @Test fun departureAndCollapsedUnknownRecoveryHidePreviewAndBlockStaleExport() {
        show()
        compose.onNodeWithTag("share-preview-button").performScrollTo().performClick()
        compose.runOnIdle {
            snapshots.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 1)
            snapshots.value = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 1)
        }
        compose.onNodeWithTag("share-preview").assertDoesNotExist()
        compose.onNodeWithTag("share-select").assertDoesNotExist()
        compose.onNodeWithTag("share-locked").assertExists()
        assertEquals(0, exports)
        assertTrue(exits > 0)
    }

    @Test fun stopHidesTextInputAndPreviewUntilExplicitReentry() {
        show()
        compose.onNodeWithTag("share-caption-beach").performScrollTo().performTextInput("共有용 설명")
        compose.runOnIdle { foreground.value = false; safety.stop() }
        compose.onNodeWithTag("share-select").assertDoesNotExist()
        compose.onNodeWithTag("share-preview").assertDoesNotExist()
        compose.onNodeWithTag("share-locked").assertExists()
    }

    // A cached export callback must refuse the latest unsafe state/generation before any side effect.
    @Test fun staleExportCallbackNeverStartsExportAfterSafetyLoss() {
        show()
        compose.onNodeWithTag("share-preview-button").performScrollTo().performClick()
        val click = requireNotNull(compose.onNodeWithTag("share-export-button").performScrollTo()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        for (state in listOf(DriveState.DRIVING, DriveState.UNKNOWN, DriveState.TRANSITION, DriveState.PARKED)) {
            compose.runOnIdle { snapshots.value = DriveSnapshot(state = state, invalidationGeneration = 1); click() }
        }
        compose.runOnIdle { assertEquals(0, exports) }
    }

    // Opening before repository readiness must show loading instead of a false empty sharing draft.
    @Test fun repositoryLoadingDoesNotOfferAnEmptySelectionOrPreview() {
        show(ready = false)
        compose.onNodeWithTag("share-loading").assertExists()
        compose.onNodeWithTag("share-select").assertDoesNotExist()
        compose.onNodeWithTag("share-preview-button").assertDoesNotExist()
    }

    private fun show(ready: Boolean = true) {
        fun photo(uri: String) = SharePhotoInput(uri, SharePhotoState.CANDIDATE, true)
        if (ready) runBlocking { controller.open(ShareInput("2026-11-08", listOf(
            ShareVisitInput("restaurant", "2026-11-08", "식당", 1, listOf(photo("p1"))),
            ShareVisitInput("beach", "2026-11-08", "해변", 2, listOf(photo("p2"), photo("p3"))),
            ShareVisitInput("cafe", "2026-11-08", "카페", 3, listOf(photo("p5"))),
        ))) }
        val epoch = requireNotNull(safety.capture())
        compose.setContent { MemoryVaultTheme {
            ShareFlowScreen(controller, safety, epoch, snapshots, foreground,
                onClose = { exits++ }, onExport = { exports++; ShareExportResult.Blocked })
        } }
    }
    private class Store : ShareSelectionStore {
        var selected: ShareSelection? = null
        override suspend fun load(date: String) = selected
        override suspend fun save(selection: ShareSelection) { selected = selection }
    }
}
