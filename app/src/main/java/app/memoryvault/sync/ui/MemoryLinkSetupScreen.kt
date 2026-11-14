package app.memoryvault.sync.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.memoryvault.R
import app.memoryvault.sync.LinkSessionSummary

@Composable
fun MemoryLinkSetupScreen(state: SetupInputState, parked: Boolean, summary: LinkSessionSummary,
    onOpen: () -> Unit, onEndpoint: (String) -> Unit, onToken: (String) -> Unit, onSave: () -> Unit, onBack: () -> Unit) {
    Scaffold { insets ->
        Column(Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.ai_setup_title), style = MaterialTheme.typography.titleLarge)
            if (!parked) {
                Text(stringResource(R.string.ai_park_required))
            } else {
                Text(stringResource(R.string.ai_setup_intro))
                Text(stringResource(R.string.ai_privacy))
                Text(stringResource(R.string.ai_local_only))
                Text(stringResource(if (summary.configured) R.string.ai_configured else R.string.ai_not_configured))
                summary.endpoint?.let { Text(stringResource(R.string.ai_endpoint_saved, it)) }
                if (state.editing) {
                    OutlinedTextField(value = state.endpoint, onValueChange = onEndpoint,
                        label = { Text(stringResource(R.string.ai_endpoint_label)) },
                        placeholder = { Text(stringResource(R.string.ai_endpoint_hint)) },
                        singleLine = true, enabled = !state.saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag("ai-endpoint"))
                    OutlinedTextField(value = state.token, onValueChange = onToken,
                        label = { Text(stringResource(R.string.ai_session_label)) },
                        singleLine = true, enabled = !state.saving,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag("ai-session"))
                    Button(onClick = onSave, enabled = !state.saving && state.endpoint.isNotBlank() && state.token.isNotBlank(),
                        modifier = Modifier.heightIn(min = 64.dp).testTag("ai-save")) {
                        Text(stringResource(if (state.saving) R.string.ai_saving else R.string.ai_save))
                    }
                } else {
                    Button(onClick = onOpen, modifier = Modifier.heightIn(min = 64.dp).testTag("ai-open")) {
                        Text(stringResource(if (summary.configured) R.string.ai_change_setup else R.string.ai_enter_setup))
                    }
                }
                noticeResource(state.notice)?.let { Text(stringResource(it)) }
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.ai_back)) }
            }
        }
    }
}

private fun noticeResource(notice: SetupNotice): Int? = when (notice) {
    SetupNotice.NONE -> null
    SetupNotice.SAVED -> R.string.ai_saved
    SetupNotice.BLOCKED -> R.string.ai_park_required
    SetupNotice.INVALID_ENDPOINT -> R.string.ai_invalid_endpoint
    SetupNotice.INVALID_SESSION -> R.string.ai_invalid_session
    SetupNotice.STORAGE_ERROR -> R.string.ai_storage_error
}
