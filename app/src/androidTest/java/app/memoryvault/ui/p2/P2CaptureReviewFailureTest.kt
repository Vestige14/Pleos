package app.memoryvault.ui.p2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.memoryvault.platform.capture.CaptureReviewContent
import app.memoryvault.platform.capture.CaptureReviewState
import app.memoryvault.ui.MemoryVaultTheme
import org.junit.Rule
import org.junit.Test

class P2CaptureReviewFailureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedLoadDoesNotClaimThereAreNoRecordings() {
        compose.setContent { MemoryVaultTheme {
            CaptureReviewContent(CaptureReviewState(visible = true, failed = true), true,
                onOpen = {}, onPlay = {}, onStop = {}, onBack = {})
        } }
        compose.onNodeWithText("기록을 읽거나 재생하지 못했습니다. 원본 파일은 유지됩니다.").assertIsDisplayed()
        compose.onNodeWithText("아직 원음 기록이 없습니다. 기존 STT 메모는 하루 초안에서 확인합니다.").assertDoesNotExist()
    }
}
