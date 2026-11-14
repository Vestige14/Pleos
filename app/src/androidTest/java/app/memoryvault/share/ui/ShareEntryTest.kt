package app.memoryvault.share.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.ui.FixtureUi
import app.memoryvault.ui.MemoryVaultScreen
import app.memoryvault.ui.MemoryVaultTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Observes the coordinator-owned entry point without editing its implementation. */
class ShareEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun parkedEntryNeedsExplicitClickAndEveryUnsafeStateHidesIt() {
        val ui = FixtureUi(DriveState.PARKED)
        var opens = 0
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, {}, onShareDay = { opens++ }) } }
        compose.runOnIdle { assertEquals(0, opens) }
        compose.onNodeWithTag("open-share").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        for (state in listOf(DriveState.DRIVING, DriveState.UNKNOWN, DriveState.TRANSITION)) {
            compose.runOnIdle { ui.driveSnapshot.value = DriveSnapshot(state = state) }
            compose.onNodeWithTag("open-share").assertDoesNotExist()
        }
        assertEquals(1, opens)
    }

    @Test fun staleEntryCallbackRechecksCurrentStateAndUnknownGeneration() {
        val ui = FixtureUi(DriveState.PARKED)
        var opens = 0
        compose.setContent { MemoryVaultTheme { MemoryVaultScreen(ui, {}, onShareDay = { opens++ }) } }
        val click = requireNotNull(compose.onNodeWithTag("open-share").performScrollTo()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        for (state in listOf(DriveState.DRIVING, DriveState.UNKNOWN, DriveState.TRANSITION, DriveState.PARKED)) {
            compose.runOnIdle { ui.driveSnapshot.value = DriveSnapshot(state = state, invalidationGeneration = 1); click() }
        }
        compose.runOnIdle { assertEquals(0, opens) }
    }
}
