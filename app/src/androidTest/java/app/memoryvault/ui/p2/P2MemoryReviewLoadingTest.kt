package app.memoryvault.ui.p2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.memoryvault.memory.MemoryAssociationProgress
import app.memoryvault.memory.ui.MemoryReviewScreen
import app.memoryvault.ui.MemoryVaultTheme
import org.junit.Rule
import org.junit.Test

class P2MemoryReviewLoadingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun preparationDoesNotClaimThereAreNoVoiceNotes() {
        compose.setContent { MemoryVaultTheme {
            MemoryReviewScreen(MemoryAssociationProgress(preparing = true), true, false, null, null,
                {}, {}, {}, { _, _, _ -> }, {}, { _, _, _ -> }, {}, {})
        } }
        compose.onNodeWithText("연결 준비·분석 중").assertIsDisplayed()
        compose.onNodeWithText("저장된 음성 메모가 없어요.").assertDoesNotExist()
    }
}
