package app.memoryvault.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.service.VoiceServiceState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryVaultScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initialUnknown_exposesNeitherSavedMemoNorPermissions_andNeverAutoRequests() {
        val ui = FixtureUi()
        var permissionRequests = 0
        compose.setContent {
            MemoryVaultTheme { MemoryVaultScreen(ui, onRequestPermissions = { permissionRequests++ }) }
        }
        compose.onNodeWithText("차량 상태를 확인할 수 없습니다").assertIsDisplayed()
        compose.onNodeWithText("음성 권한 설정").assertDoesNotExist()
        compose.onNodeWithText("  저장된 음성 원문  ").assertDoesNotExist()
        compose.onNodeWithTag("memo-input").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, permissionRequests) }
    }

    @Test fun leavingPark_closesDialogHidesTranscriptAndKeepsCancellation_thenExplicitlyRestoresDraft() {
        val ui = FixtureUi(DriveState.PARKED)
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, onRequestPermissions = {}) } }
        compose.onNodeWithText("메모 추가").performScrollTo().performClick()
        compose.onNodeWithTag("memo-input").performTextInput("보내지 않은 문장")
        compose.runOnIdle {
            ui.voiceState.value = VoiceServiceState.Listening
            ui.driveSnapshot.value = DriveSnapshot(state = DriveState.DRIVING)
        }
        compose.onNodeWithTag("memo-input").assertDoesNotExist()
        compose.onNodeWithText("보내지 않은 문장").assertDoesNotExist()
        compose.onNodeWithText("  저장된 음성 원문  ").assertDoesNotExist()
        compose.onNodeWithText("녹음 취소").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, ui.cancellations)
            assertTrue(ui.saved.isEmpty())
            ui.driveSnapshot.value = DriveSnapshot(state = DriveState.TRANSITION)
        }
        compose.onNodeWithText("한마디 남기기").assertDoesNotExist()
        compose.runOnIdle { ui.driveSnapshot.value = DriveSnapshot(state = DriveState.PARKED) }
        compose.onNodeWithTag("memo-input").assertDoesNotExist()
        compose.onNodeWithText("작성 중인 메모 이어 쓰기").performScrollTo().performClick()
        compose.onNodeWithTag("memo-input").assertTextContains("보내지 않은 문장")
    }

    @Test fun savedStateRestorationWhileUnknown_keepsBufferHiddenUntilParkedResume() {
        val ui = FixtureUi(DriveState.PARKED)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, onRequestPermissions = {}) } }
        compose.onNodeWithText("메모 추가").performScrollTo().performClick()
        compose.onNodeWithTag("memo-input").performTextInput("재생성할 초안")
        compose.runOnIdle { ui.driveSnapshot.value = DriveSnapshot() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("memo-input").assertDoesNotExist()
        compose.onNodeWithText("재생성할 초안").assertDoesNotExist()
        compose.runOnIdle { ui.driveSnapshot.value = DriveSnapshot(state = DriveState.PARKED) }
        compose.onNodeWithText("작성 중인 메모 이어 쓰기").performScrollTo().performClick()
        compose.onNodeWithTag("memo-input").assertTextContains("재생성할 초안")
        compose.runOnIdle { assertTrue(ui.saved.isEmpty()) }
    }

    @Test fun stoppedParkedEditorDoesNotAutomaticallyReopenAfterDrivingAndParkingInBackground() {
        val ui = FixtureUi(DriveState.PARKED)
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MemoryVaultTheme { MemoryVaultScreen(ui, onRequestPermissions = {}) }
            }
        }
        compose.onNodeWithText("메모 추가").performScrollTo().performClick()
        compose.onNodeWithTag("memo-input").performTextInput("백그라운드 전 미전송 메모")
        compose.runOnIdle {
            ui.voiceState.value = VoiceServiceState.Listening
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        compose.onNodeWithTag("memo-input").assertDoesNotExist()
        compose.runOnIdle {
            ui.driveSnapshot.value = DriveSnapshot(state = DriveState.DRIVING)
            ui.driveSnapshot.value = DriveSnapshot(state = DriveState.PARKED)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithTag("memo-input").assertDoesNotExist()
        compose.onNodeWithText("녹음 취소").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(ui.saved.isEmpty())
            assertEquals(0, ui.cancellations)
        }
        compose.onNodeWithText("작성 중인 메모 이어 쓰기").performScrollTo().performClick()
        compose.onNodeWithTag("memo-input").assertTextContains("백그라운드 전 미전송 메모")
    }

    @Test fun pendingVoiceDisplaysExactReadOnlyText_andTypedSaveRequiresAnExplicitConfirmation() {
        val ui = FixtureUi(DriveState.PARKED)
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, onRequestPermissions = {}) } }
        compose.onNodeWithText("연결 분석 대기 · 음성 원문").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("  저장된 음성 원문  ").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("메모 수정").assertDoesNotExist()
        compose.onNodeWithText("메모 추가").performScrollTo().performClick()
        compose.onNodeWithTag("memo-input").performTextInput("  직접 쓴 메모  ")
        compose.runOnIdle { assertTrue(ui.saved.isEmpty()) }
        compose.onNodeWithText("저장").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("v1:  직접 쓴 메모  "), ui.saved) }
        compose.onNodeWithTag("memo-input").assertDoesNotExist()
    }

    @Test fun anotherTargetRequiresExplicitDiscard_andCancelPreservesTheSingleUnsentDraft() {
        val ui = FixtureUi(DriveState.PARKED)
        val firstDay = (ui.day.value as DayDraftState.Ready).draft
        val secondCard = firstDay.cards.single().let { it.copy(visit = it.visit.copy(id = "v2", placeName = "둘째 방문")) }
        ui.day.value = DayDraftState.Ready(firstDay.copy(cards = firstDay.cards + secondCard))
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, onRequestPermissions = {}) } }
        compose.onAllNodesWithText("메모 추가")[0].performScrollTo().performClick()
        compose.onNodeWithTag("memo-input").performTextInput("첫 방문 미전송 초안")
        compose.onNodeWithText("닫기 · 초안 보관").performClick()
        compose.onAllNodesWithText("메모 추가")[1].performScrollTo().performClick()
        compose.onNodeWithTag("discard-draft-confirmation").assertIsDisplayed()
        compose.onNodeWithText("기존 초안 유지").performClick()
        compose.onNodeWithText("작성 중인 메모 이어 쓰기").performScrollTo().performClick()
        compose.onNodeWithTag("memo-input").assertTextContains("첫 방문 미전송 초안")
        compose.onNodeWithText("닫기 · 초안 보관").performClick()
        compose.onAllNodesWithText("메모 추가")[1].performScrollTo().performClick()
        compose.onNodeWithText("초안 버리고 새로 쓰기").performClick()
        compose.onNodeWithTag("memo-input").performTextInput("둘째 방문 메모")
        compose.onNodeWithText("저장").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("v2:둘째 방문 메모"), ui.saved) }
    }

    @Test fun unknownAndParkedInTheSameUiTurnClosesEditorUntilExplicitResume() {
        val ui = FixtureUi(DriveState.PARKED)
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, onRequestPermissions = {}) } }
        compose.onNodeWithText("메모 추가").performScrollTo().performClick()
        compose.onNodeWithTag("memo-input").performTextInput("짧은 연결 손실 전 초안")
        compose.runOnIdle {
            ui.driveSnapshot.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 1)
            ui.driveSnapshot.value = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 1)
        }
        compose.onNodeWithTag("memo-input").assertDoesNotExist()
        compose.onNodeWithText("짧은 연결 손실 전 초안").assertDoesNotExist()
        compose.runOnIdle { assertTrue(ui.saved.isEmpty()) }
        compose.onNodeWithText("작성 중인 메모 이어 쓰기").performScrollTo().performClick()
        compose.onNodeWithTag("memo-input").assertTextContains("짧은 연결 손실 전 초안")
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

}
