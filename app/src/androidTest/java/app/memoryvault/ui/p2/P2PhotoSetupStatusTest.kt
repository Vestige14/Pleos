package app.memoryvault.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class P2PhotoSetupStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unavailableStorageIsNotDescribedAsLoading() {
        compose.setContent { MemoryVaultTheme {
            PhotoSetup(PhotoReviewState(scanStatus = PhotoScanStatus.STORAGE_UNAVAILABLE),
                notice = null, busy = false, onPermissions = {}, onRefresh = {})
        } }
        compose.onNodeWithText("저장소를 불러오지 못했습니다. 앱을 다시 실행해 주세요. 데이터는 삭제하지 않습니다.").assertIsDisplayed()
        compose.onNodeWithText("저장된 하루 초안을 불러오는 중입니다.").assertDoesNotExist()
    }
}
