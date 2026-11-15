package app.memoryvault.ui.p2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.memoryvault.platform.capture.CaptureArtifact
import app.memoryvault.platform.capture.CaptureReviewContent
import app.memoryvault.platform.capture.CaptureReviewState
import app.memoryvault.platform.capture.CaptureState
import app.memoryvault.ui.MemoryVaultTheme
import org.junit.Rule
import org.junit.Test

class P2CaptureReviewLoadingContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingReadShowsProgressAndDisablesAnotherOpenWithoutFalseEmpty() {
        compose.setContent { MemoryVaultTheme {
            CaptureReviewContent(CaptureReviewState(loading = true), true,
                onOpen = {}, onPlay = {}, onStop = {}, onBack = {})
        } }
        compose.onNodeWithText("원음 기록을 불러오는 중입니다.").assertIsDisplayed()
        compose.onNodeWithText("기록 불러오는 중").assertIsNotEnabled()
        compose.onNodeWithText("아직 원음 기록이 없습니다. 기존 STT 메모는 하루 초안에서 확인합니다.").assertDoesNotExist()
    }

    @Test fun loadingDoesNotShowStaleItemsEvenIfStateContainsThem() {
        val synthetic = CaptureArtifact("fixture", "fixture-only-no-audio", 1794105000000, 1794105000000,
            state = CaptureState.COMMITTED, transcript = "이전 합성 원문")
        compose.setContent { MemoryVaultTheme {
            CaptureReviewContent(CaptureReviewState(visible = true, items = listOf(synthetic), loading = true), true,
                onOpen = {}, onPlay = {}, onStop = {}, onBack = {})
        } }
        compose.onNodeWithText("원음 기록을 불러오는 중입니다.").assertIsDisplayed()
        compose.onNodeWithText("이전 합성 원문").assertDoesNotExist()
    }
}
