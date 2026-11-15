package app.memoryvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import app.memoryvault.R
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveReadinessReason

@Composable
fun VoicePanelScreen(ui: MemoryVaultUi, onOpenParkedSettings: () -> Unit) {
    val snapshot by ui.driveSnapshot.collectAsStateWithLifecycle()
    val voice by ui.voiceState.collectAsStateWithLifecycle()
    val timing by ui.voiceTiming.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    VoiceControls(snapshot.state, voice, onStart = {
                        // Rendering can lag a signal or service update; check authoritative values at the click.
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                            ui.driveSnapshot.value.state != DriveState.TRANSITION && !ui.voiceState.value.isBusy) {
                            ui.startVoice()
                        }
                    }, onCancel = { if (ui.voiceState.value.isBusy) ui.cancelVoice() },
                        onFinish = { if (ui.voiceState.value == app.memoryvault.service.VoiceServiceState.Listening) ui.finishVoice() },
                        timing = timing)
                    if (snapshot.state == DriveState.UNKNOWN && snapshot.readinessReason == DriveReadinessReason.PERMISSION_DENIED) {
                        Text(stringResource(R.string.p2_drive_permission_missing))
                    }
                    if (snapshot.state == DriveState.PARKED) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                            onClick = {
                                if (ui.driveSnapshot.value.state == DriveState.PARKED) onOpenParkedSettings()
                            },
                        ) { Text(stringResource(R.string.p2_record_parked_settings)) }
                    }
                }
            }
            if (snapshot.state == DriveState.DRIVING) {
                Text(stringResource(R.string.p2_driving_record_only),
                    Modifier.padding(top = 16.dp).testTag("p2-driving-record-only"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
