package app.memoryvault.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.memoryvault.data.LinkState
import app.memoryvault.data.Photo
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.platform.PhotoAccess
import app.memoryvault.platform.PhotoPermissions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoReviewScreenTest {
    @get:Rule val compose = createComposeRule()

    // Saving a candidate on render would turn a machine suggestion into an unrequested user decision.
    @Test fun parkedCandidateConfirmationRejectionAndRescanNeedExplicitButtons() {
        val ui = fixture()
        var permissionRequests = 0
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, {}, onRequestPhotoPermissions = { permissionRequests++ }) } }
        // A tile's closest scroll parent is the horizontal LazyRow. Bring its outer card into view first.
        compose.onNodeWithText("메모 추가").performScrollTo()
        compose.onNodeWithText("사진 후보").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(ui.photoDecisions.isEmpty()); assertEquals(0, ui.photoScans); assertEquals(0, permissionRequests) }
        compose.onNodeWithText("사진 확정").performScrollTo().assertHeightIsAtLeast(64.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("연결 해제").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("사진 다시 읽기").performScrollTo().performClick()
        compose.onNodeWithText("사진 권한 · 다시 선택").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("content://candidate" to LinkState.CONFIRMED, "content://candidate" to LinkState.REJECTED), ui.photoDecisions)
            assertEquals(1, ui.photoScans)
            assertEquals(1, permissionRequests)
        }
    }

    @Test fun everyNonParkedStateHidesPhotosPermissionsAndPhotoActions() {
        val ui = fixture()
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, {}, onRequestPhotoPermissions = { error("must stay hidden") }) } }
        for (state in listOf(DriveState.DRIVING, DriveState.UNKNOWN, DriveState.TRANSITION)) {
            compose.runOnIdle { ui.driveSnapshot.value = DriveSnapshot(state = state) }
            compose.onNodeWithText("사진 후보").assertDoesNotExist()
            compose.onNodeWithText("사진 권한 · 다시 선택").assertDoesNotExist()
            compose.onNodeWithTag("photo-thumbnail").assertDoesNotExist()
            compose.onNodeWithText("연결 해제").assertDoesNotExist()
        }
        compose.runOnIdle { assertTrue(ui.photoDecisions.isEmpty()); assertEquals(0, ui.photoScans) }
    }

    @Test fun staleClicksRecheckLatestDriveStateAndInvalidationGeneration() {
        val ui = fixture()
        var permissions = 0
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, {}, onRequestPhotoPermissions = { permissions++ }) } }
        val confirm = requireNotNull(compose.onNodeWithText("사진 확정").performScrollTo()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val rescan = requireNotNull(compose.onNodeWithText("사진 다시 읽기").performScrollTo()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val permission = requireNotNull(compose.onNodeWithText("사진 권한 · 다시 선택").performScrollTo()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        for (state in listOf(DriveState.DRIVING, DriveState.UNKNOWN, DriveState.TRANSITION, DriveState.PARKED)) {
            compose.runOnIdle {
                ui.driveSnapshot.value = DriveSnapshot(state = state, invalidationGeneration = 1)
                confirm(); rescan(); permission()
            }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertTrue(ui.photoDecisions.isEmpty()); assertEquals(0, ui.photoScans); assertEquals(0, permissions) }
    }

    @Test fun partialPermissionAndMissingLocationAreExplainedAndInaccessibleOrRejectedPhotosStayHidden() {
        val ui = fixture()
        val draft = (ui.day.value as DayDraftState.Ready).draft
        ui.day.value = DayDraftState.Ready(draft.copy(cards = listOf(draft.cards.single().let { card ->
            card.copy(photos = card.photos + card.photos[0].copy(uri = "content://denied", accessible = false) +
                card.photos[0].copy(uri = "content://rejected", linkState = LinkState.REJECTED))
        })))
        ui.photoReview.value = PhotoReviewState(PhotoPermissions(PhotoAccess.PARTIAL, false))
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, {}) } }
        compose.onNodeWithText("선택한 사진만 읽을 수 있습니다.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("촬영 위치 권한이 없으면 새 사진은 방문에 연결되지 않습니다.").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("사진 확정").assertCountEquals(1)
        compose.runOnIdle { ui.photoReview.value = PhotoReviewState(PhotoPermissions(PhotoAccess.DENIED, false)) }
        compose.onNodeWithText("사진 읽기 권한을 허용해 주세요.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("사진 후보").assertDoesNotExist()
        compose.onNodeWithTag("photo-thumbnail").assertDoesNotExist()
    }

    @Test fun explicitRescanRetriesAFailedThumbnailForTheSameUri() {
        val ui = fixture()
        val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
        ui.thumbnailDecoder = { if (ui.thumbnailRequests.size == 1) null else image }
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, {}) } }
        compose.onNodeWithText("메모 추가").performScrollTo()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, ui.thumbnailRequests.size) }
        compose.onNodeWithText("사진을 열 수 없습니다. 권한을 확인하고 다시 읽어 주세요.").assertIsDisplayed()
        compose.onNodeWithText("사진 다시 읽기").performScrollTo().performClick()
        compose.onNodeWithText("메모 추가").performScrollTo()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, ui.thumbnailRequests.size) }
        compose.onNodeWithContentDescription("방문 사진").assertIsDisplayed()
    }

    private fun fixture(): FixtureUi = FixtureUi(DriveState.PARKED).apply {
        val draft = (day.value as DayDraftState.Ready).draft
        day.value = DayDraftState.Ready(draft.copy(cards = listOf(draft.cards.single().copy(photos = listOf(
            Photo("content://candidate", 0, null, null, "v1", LinkState.CANDIDATE),
        )))))
    }
}
