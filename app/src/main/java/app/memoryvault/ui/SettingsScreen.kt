package app.memoryvault.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.memoryvault.R
import app.memoryvault.ui.components.P2ScreenColumn

/** Slots retain the existing permission, notification, photo, and AI actions. */
@Composable
internal fun SettingsScreen(
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
    voice: @Composable () -> Unit,
    photos: @Composable () -> Unit,
    ai: @Composable () -> Unit,
) {
    P2ScreenColumn(modifier.testTag("p2-settings-page")) {
        Text(stringResource(R.string.p2_settings), style = MaterialTheme.typography.headlineLarge)
        OutlinedButton(onClick = onHome,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("p2-settings-home")) {
            Text(stringResource(R.string.p2_back_home))
        }
        voice()
        photos()
        ai()
    }
}
