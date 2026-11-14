package app.memoryvault.share.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.share.ShareExportReceipt
import app.memoryvault.share.ShareExportReceiptStore
import app.memoryvault.share.SharedPreferencesShareReceiptStore
import app.memoryvault.ui.MemoryVaultTheme
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ShareSavedReceiptNoticeTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "share-receipt-ui-test-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val snapshots = MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))
    private val receipt = ShareExportReceipt("content://media/external/images/media/41", "saved.png", "2026-11-08")
    @After fun cleanupOnlyThisTestPreferences() { context.deleteSharedPreferences(name) }

    @Test fun savedReceiptIsDiscoverableAfterDayViewRecreationWithoutAChooserResult() {
        runBlocking { SharedPreferencesShareReceiptStore(preferences).save(receipt) }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MemoryVaultTheme { Column {
            Text("하루 초안")
            val restored = remember { SharedPreferencesShareReceiptStore(preferences) }
            ShareSavedReceiptNotice(snapshots, "2026-11-08", restored)
        } } }
        compose.onNodeWithTag("share-saved-receipt").assertExists()
        compose.onNodeWithText("saved.png").assertExists()
        compose.onNodeWithTag("share-saved-location").assertTextContains("기억저장소", substring = true)
        compose.onNodeWithText(receipt.uri).assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("share-saved-receipt").assertExists()
        compose.onNodeWithText("saved.png").assertExists()
    }

    @Test fun receiptFromCompletedExportAppearsInAnAlreadyComposedParkedDayView() {
        val store = SharedPreferencesShareReceiptStore(preferences)
        compose.setContent { MemoryVaultTheme { Column {
            Text("하루 초안")
            ShareSavedReceiptNotice(snapshots, "2026-11-08", store)
        } } }
        compose.onNodeWithTag("share-saved-receipt").assertDoesNotExist()
        runBlocking { store.save(receipt) }
        compose.onNodeWithTag("share-saved-receipt").assertExists()
        compose.onNodeWithText("saved.png").assertExists()
    }

    @Test fun lateReceiptStaysHiddenForEveryNonParkedStateAndAddsNoUnsafeActions() {
        val store = object : ShareExportReceiptStore {
            override val latest = MutableStateFlow<ShareExportReceipt?>(null)
            override suspend fun save(receipt: ShareExportReceipt) { latest.value = receipt }
        }
        compose.setContent { MemoryVaultTheme { Column {
            Text("하루 초안")
            ShareSavedReceiptNotice(snapshots, "2026-11-08", store)
        } } }
        for (state in listOf(DriveState.DRIVING, DriveState.UNKNOWN, DriveState.TRANSITION)) {
            compose.runOnIdle {
                snapshots.value = DriveSnapshot(state = state, invalidationGeneration = 1)
                store.latest.value = receipt
            }
            compose.onNodeWithTag("share-saved-receipt").assertDoesNotExist()
        }
        compose.runOnIdle { snapshots.value = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 1) }
        compose.onNodeWithTag("share-saved-receipt").assertExists()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }
}
