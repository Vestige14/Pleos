package app.memoryvault.share.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.memoryvault.ui.MemoryVaultTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

class SharePhotoThumbnailStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingThumbnailIsLoadingUntilTheRequestFails() {
        val pending = CompletableDeferred<ImageBitmap?>()
        compose.setContent {
            MemoryVaultTheme { SharePhotoThumbnail("content://p2-test/photo", { pending.await() }) }
        }

        compose.onNodeWithText("사진 불러오는 중").assertExists()
        compose.onNodeWithText("사진을 열 수 없습니다. 권한을 확인하고 다시 읽어 주세요.").assertDoesNotExist()
        compose.runOnIdle { pending.complete(null) }
        compose.onNodeWithText("사진을 열 수 없습니다. 권한을 확인하고 다시 읽어 주세요.").assertExists()
    }
}
