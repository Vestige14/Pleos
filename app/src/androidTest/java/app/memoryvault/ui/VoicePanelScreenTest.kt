package app.memoryvault.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceMemoOutcome
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.service.VoiceServiceResult
import app.memoryvault.service.VoiceServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoicePanelScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun drivingRequiresExplicitStart_thenRejectsDuplicateClickAndAllowsCancel() {
        val ui = FixtureUi(DriveState.DRIVING)
        compose.setContent { MemoryVaultTheme { VoicePanelScreen(ui, onOpenParkedSettings = {}) } }
        val start = compose.onNodeWithText("한마디 남기기")
        start.assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        val click = requireNotNull(start.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle {
            assertEquals(0, ui.starts)
            click()
            click()
            assertEquals(1, ui.starts)
        }
        compose.onNodeWithText("한마디 남기기").assertDoesNotExist()
        compose.onNodeWithText("녹음 취소").assertIsDisplayed().assertHeightIsAtLeast(64.dp).performClick()
        compose.runOnIdle { assertEquals(1, ui.cancellations) }
    }

    @Test fun unknownTransitionAndParked_neverOfferNewRecording() {
        val ui = FixtureUi()
        compose.setContent { MemoryVaultTheme { VoicePanelScreen(ui, onOpenParkedSettings = {}) } }
        mapOf(
            DriveState.UNKNOWN to "차량 상태를 확인할 수 없습니다",
            DriveState.TRANSITION to "차량 상태 변경 확인 중",
            DriveState.PARKED to "주차 상태입니다. 앱에서 메모와 음성 권한을 설정해 주세요.",
        ).forEach { (drive, status) ->
            compose.runOnIdle { ui.driveSnapshot.value = DriveSnapshot(state = drive) }
            compose.onNodeWithText(status).assertIsDisplayed()
            compose.onNodeWithText("한마디 남기기").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(0, ui.starts) }
    }

    @Test fun staleStartClick_rechecksLatestVehicleState() {
        val ui = FixtureUi(DriveState.DRIVING)
        compose.setContent { MemoryVaultTheme { VoicePanelScreen(ui, onOpenParkedSettings = {}) } }
        listOf(DriveState.UNKNOWN, DriveState.TRANSITION, DriveState.PARKED).forEach { drive ->
            compose.runOnIdle { ui.driveSnapshot.value = DriveSnapshot(state = DriveState.DRIVING) }
            val click = requireNotNull(compose.onNodeWithText("한마디 남기기")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            compose.runOnIdle {
                ui.driveSnapshot.value = DriveSnapshot(state = drive)
                click()
                assertEquals(0, ui.starts)
            }
        }
    }

    @Test fun staleStartClick_rechecksEveryBusyPhase() {
        val ui = FixtureUi(DriveState.DRIVING)
        compose.setContent { MemoryVaultTheme { VoicePanelScreen(ui, onOpenParkedSettings = {}) } }
        listOf(VoiceServiceState.CheckingVehicle, VoiceServiceState.Preparing,
            VoiceServiceState.Listening, VoiceServiceState.Processing).forEach { phase ->
            compose.runOnIdle { ui.voiceState.value = VoiceServiceState.Idle }
            val click = requireNotNull(compose.onNodeWithText("한마디 남기기")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            compose.runOnIdle {
                ui.voiceState.value = phase
                click()
                assertEquals(0, ui.starts)
            }
            compose.onNodeWithText("한마디 남기기").assertDoesNotExist()
            compose.onNodeWithText("녹음 취소").assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(4, ui.cancellations) }
    }

    @Test fun recordingStatusAppearsOnlyWhileListening_andSaveIsSeparateFromProcessing() {
        val ui = FixtureUi(DriveState.DRIVING)
        compose.setContent { MemoryVaultTheme { VoicePanelScreen(ui, onOpenParkedSettings = {}) } }
        compose.onNodeWithText("기억 저장소 · 메모 녹음 중").assertDoesNotExist()
        compose.runOnIdle { ui.voiceState.value = VoiceServiceState.Preparing }
        compose.onNodeWithText("메모 녹음 준비 중").assertIsDisplayed()
        compose.onNodeWithText("기억 저장소 · 메모 녹음 중").assertDoesNotExist()
        compose.runOnIdle { ui.voiceState.value = VoiceServiceState.Listening }
        compose.onNodeWithText("기억 저장소 · 메모 녹음 중").assertIsDisplayed()
        compose.runOnIdle { ui.voiceState.value = VoiceServiceState.Processing }
        compose.onNodeWithText("메모 처리 중").assertIsDisplayed()
        compose.onNodeWithText("기억 저장소 · 메모 녹음 중").assertDoesNotExist()
        compose.onNodeWithText("메모가 저장되었습니다.").assertDoesNotExist()
        compose.runOnIdle {
            ui.voiceState.value = VoiceServiceState.Finished(VoiceServiceResult.Completed(VoiceMemoOutcome.Saved(9)))
        }
        compose.onNodeWithText("메모가 저장되었습니다.").assertIsDisplayed()
        compose.onNodeWithText("녹음 취소").assertDoesNotExist()
        compose.onNodeWithText("한마디 남기기").assertIsDisplayed()
    }

    @Test fun parkedSettingsNeedExplicitClick_andRecheckParkAtTheClick() {
        val ui = FixtureUi(DriveState.PARKED)
        var settings = 0
        compose.setContent { MemoryVaultTheme { VoicePanelScreen(ui, onOpenParkedSettings = { settings++ }) } }
        val click = requireNotNull(compose.onNodeWithText("주차 중 설정")
            .assertIsDisplayed().assertHeightIsAtLeast(64.dp).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle { assertEquals(0, settings) }
        listOf(DriveState.DRIVING, DriveState.UNKNOWN, DriveState.TRANSITION).forEach { drive ->
            compose.runOnIdle {
                ui.driveSnapshot.value = DriveSnapshot(state = drive)
                click()
                assertEquals(0, settings)
            }
            compose.onNodeWithText("주차 중 설정").assertDoesNotExist()
        }
        compose.runOnIdle { ui.driveSnapshot.value = DriveSnapshot(state = DriveState.PARKED) }
        compose.onNodeWithText("주차 중 설정").performClick()
        compose.runOnIdle { assertEquals(1, settings) }
    }

    @Test fun allDriveStatesHidePrivateRecordsAndTextInput() {
        val ui = FixtureUi(DriveState.DRIVING)
        compose.setContent { MemoryVaultTheme { VoicePanelScreen(ui, onOpenParkedSettings = {}) } }
        DriveState.entries.forEach { drive ->
            compose.runOnIdle { ui.driveSnapshot.value = DriveSnapshot(state = drive) }
            compose.onNodeWithText("기억 저장소 · 녹음").assertIsDisplayed()
            compose.onNodeWithText("  저장된 음성 원문  ").assertDoesNotExist()
            compose.onNodeWithText("방문 장소").assertDoesNotExist()
            compose.onNodeWithText("메모 추가").assertDoesNotExist()
            compose.onNodeWithText("메모 수정").assertDoesNotExist()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertCountEquals(0)
        }
        compose.runOnIdle { assertTrue(ui.saved.isEmpty()) }
    }

    @Test fun recreationDoesNotStartOrCancel_andKeepsCurrentSessionControls() {
        val ui = FixtureUi(DriveState.DRIVING)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MemoryVaultTheme { VoicePanelScreen(ui, onOpenParkedSettings = {}) } }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(0, ui.starts)
            ui.voiceState.value = VoiceServiceState.Listening
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("기억 저장소 · 메모 녹음 중").assertIsDisplayed()
        compose.onNodeWithText("녹음 취소").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, ui.starts)
            assertEquals(0, ui.cancellations)
        }
    }
}
