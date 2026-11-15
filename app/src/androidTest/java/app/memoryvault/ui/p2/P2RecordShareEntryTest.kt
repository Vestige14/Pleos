package app.memoryvault.ui.p2

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.ui.FixtureUi
import app.memoryvault.ui.MemoryVaultScreen
import app.memoryvault.ui.MemoryVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** UX contract for the product home. Service permission and microphone effects are tested by their owners. */
class P2RecordShareEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun parkedRecordStartIsVisibleBeforeTheLongDayDraft() {
        val ui = FixtureUi(DriveState.PARKED)
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, onRequestPermissions = {}) } }

        val record = compose.onNodeWithTag("p2-record-start").assertIsDisplayed().getUnclippedBoundsInRoot()
        val records = compose.onNodeWithText("기록과 검토").getUnclippedBoundsInRoot()
        assertTrue("Recording must precede the long records list", record.bottom <= records.top)
        assertTrue("The primary recording target must remain 64dp tall", record.bottom - record.top >= 64.dp)
    }

    @Test fun parkedShareEntryPrecedesRecordsAndOpensTheExistingRoute() {
        val ui = FixtureUi(DriveState.PARKED)
        var opens = 0
        compose.setContent { MemoryVaultTheme {
            MemoryVaultScreen(ui, onRequestPermissions = {}, onShareDay = { opens++ })
        } }

        val share = compose.onNodeWithTag("open-share").assertIsDisplayed().getUnclippedBoundsInRoot()
        val records = compose.onNodeWithText("기록과 검토").getUnclippedBoundsInRoot()
        assertTrue("Sharing must be discoverable before the records list", share.bottom <= records.top)
        compose.onNodeWithTag("open-share").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun drivingIdleShowsOnlyTheRecordCard() {
        val ui = FixtureUi(DriveState.DRIVING)
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, onRequestPermissions = {}) } }

        compose.onNodeWithText("녹음 시작").assertIsDisplayed()
        compose.onNodeWithText("시연 녹음 시각", substring = true).assertDoesNotExist()
        compose.onNodeWithText("기록과 검토").assertDoesNotExist()
        compose.onNodeWithTag("open-share").assertDoesNotExist()
        compose.onNodeWithText("권한 및 연결 설정").assertDoesNotExist()
    }

    @Test fun unknownCanExplicitlyStartButCannotReadRecordsOrShareAndStaleTransitionClickIsBlocked() {
        val ui = FixtureUi(DriveState.UNKNOWN)
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, onRequestPermissions = {}) } }

        val startNode = compose.onNodeWithTag("p2-record-start").assertIsDisplayed()
        compose.onNodeWithText("  저장된 음성 원문  ").assertDoesNotExist()
        compose.onNodeWithTag("open-share").assertDoesNotExist()
        compose.onNodeWithTag("p2-drive-badge").assertDoesNotExist()
        val staleClick = requireNotNull(startNode.fetchSemanticsNode().config[SemanticsActions.OnClick].action)

        compose.runOnIdle {
            ui.driveSnapshot.value = DriveSnapshot(state = DriveState.TRANSITION)
            staleClick()
            assertEquals(0, ui.starts)
            ui.driveSnapshot.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 1)
        }
        compose.onNodeWithTag("p2-record-start").performClick()
        compose.runOnIdle { assertEquals(1, ui.starts) }
    }
}
