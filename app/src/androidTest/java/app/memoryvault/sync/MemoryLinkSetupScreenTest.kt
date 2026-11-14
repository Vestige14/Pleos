package app.memoryvault.sync

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.sync.ui.*
import app.memoryvault.ui.MemoryVaultTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryLinkSetupScreenTest {
    @get:Rule val compose = createComposeRule()
    private var snapshot = DriveSnapshot(DriveState.PARKED)
    private val controller = MemoryLinkSetupController({ snapshot }) { _, _, allowed -> if (!allowed()) throw LinkSetupBlocked() }
    private fun show() {
        controller.onStart(); controller.open()
        compose.setContent {
            val state by controller.state.collectAsState()
            MemoryVaultTheme {
                MemoryLinkSetupScreen(state, snapshot.state == DriveState.PARKED, LinkSessionSummary(false, null),
                    { controller.open() }, controller::updateEndpoint, controller::updateToken, { controller.submit() }, {})
            }
        }
    }
    @Test fun sessionInputHasPasswordSemanticsAndNoRevealControl() {
        show()
        compose.onNodeWithTag("ai-endpoint").assertExists()
        compose.onNodeWithTag("ai-session").assertExists().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("ai-session").performTextInput("synthetic_" + "x".repeat(40))
        compose.onNodeWithTag("ai-save").assertExists()
        compose.onNodeWithTag("ai-reveal-token").assertDoesNotExist()
    }
    @Test fun drivingAndStopRemoveInputsAndParkedReturnRequiresManualReopen() {
        show()
        compose.onNodeWithTag("ai-session").assertExists()
        compose.runOnIdle { snapshot = DriveSnapshot(DriveState.DRIVING); controller.onDriveChanged() }
        compose.onNodeWithTag("ai-session").assertDoesNotExist()
        compose.runOnIdle { snapshot = DriveSnapshot(DriveState.PARKED); controller.onDriveChanged() }
        compose.onNodeWithTag("ai-session").assertDoesNotExist()
        compose.runOnIdle { controller.open() }
        compose.onNodeWithTag("ai-session").assertExists()
        compose.runOnIdle { controller.onStop() }
        compose.onNodeWithTag("ai-session").assertDoesNotExist()
        compose.runOnIdle { controller.onStart() }
        compose.onNodeWithTag("ai-session").assertDoesNotExist()
    }
}
