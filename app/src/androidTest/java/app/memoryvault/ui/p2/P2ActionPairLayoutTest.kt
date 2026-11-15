package app.memoryvault.ui.p2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.memoryvault.ui.MemoryVaultTheme
import app.memoryvault.ui.components.P2ActionPair
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class P2ActionPairLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun splitWidthKeepsBothActionsOnSeparateFullSizeRows() {
        compose.setContent {
            MemoryVaultTheme {
                Box(Modifier.width(320.dp)) {
                    P2ActionPair(first = { actionModifier ->
                        Button(onClick = {}, modifier = actionModifier.heightIn(min = 64.dp).testTag("first")) {
                            Text("첫 번째 긴 동작")
                        }
                    }, second = { actionModifier ->
                        OutlinedButton(onClick = {}, modifier = actionModifier.heightIn(min = 64.dp).testTag("second")) {
                            Text("두 번째 긴 동작")
                        }
                    })
                }
            }
        }

        val first = compose.onNodeWithTag("first").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("second").getUnclippedBoundsInRoot()
        assertTrue(second.top >= first.bottom)
        assertTrue(first.bottom - first.top >= 64.dp)
        assertTrue(second.bottom - second.top >= 64.dp)
    }
}
