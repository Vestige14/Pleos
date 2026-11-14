package app.memoryvault.platform.capture

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.memoryvault.ui.MemoryVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CaptureReviewContentTest {
    @get:Rule val compose = createComposeRule()
    private val item = CaptureArtifact("s", "/private/s.wav", 700, 700, byteCount = 2,
        state = CaptureState.NOTE_PENDING, transcript = "합성 원문")

    @Test fun parkedRawPendingRecordShowsVerbatimTranscriptMissingLocationAndExplicitPlay() {
        var played: String? = null
        compose.setContent { MemoryVaultTheme {
            CaptureReviewContent(CaptureReviewState(visible = true, items = listOf(item)), true,
                onOpen = {}, onPlay = { played = it }, onStop = {}, onBack = {})
        } }
        compose.onNodeWithText("합성 원문").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("위치 정보 없음").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("원음 재생").performScrollTo().performClick()
        assertEquals("s", played)
        compose.onNodeWithText("텍스트 저장 확인 필요").performScrollTo().assertIsDisplayed()
    }

    @Test fun untrustedOrBackgroundContentNeverExposesTranscriptOrPlayback() {
        compose.setContent { MemoryVaultTheme {
            CaptureReviewContent(CaptureReviewState(visible = true, items = listOf(item)), false,
                onOpen = {}, onPlay = {}, onStop = {}, onBack = {})
        } }
        compose.onNodeWithText("합성 원문").assertDoesNotExist()
        compose.onNodeWithText("원음 재생").assertDoesNotExist()
    }
}
