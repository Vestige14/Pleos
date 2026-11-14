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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.memoryvault.R
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveReadinessReason

@Composable
fun VoicePanelScreen(ui: MemoryVaultUi, onOpenParkedSettings: () -> Unit) {
    val snapshot by ui.driveSnapshot.collectAsStateWithLifecycle()
    val voice by ui.voiceState.collectAsStateWithLifecycle()

    Scaffold { insets ->
        Box(Modifier.fillMaxSize().padding(insets).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.recording_app_name), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(when (snapshot.state) {
                    DriveState.DRIVING -> R.string.voice_ready_driving
                    DriveState.PARKED -> R.string.voice_ready_parked
                    DriveState.TRANSITION -> R.string.drive_transition
                    DriveState.UNKNOWN -> R.string.drive_unknown
                }))
                if (snapshot.state == DriveState.UNKNOWN && snapshot.readinessReason == DriveReadinessReason.PERMISSION_DENIED) {
                    Text(stringResource(R.string.drive_permission_missing))
                }
                VoiceControls(snapshot.state, voice, onStart = {
                    // Rendering can lag a signal or service update; check authoritative values at the click.
                    if (ui.driveSnapshot.value.state == DriveState.DRIVING && !ui.voiceState.value.isBusy) {
                        ui.startVoice()
                    }
                }, onCancel = ui::cancelVoice)
                if (snapshot.state == DriveState.PARKED) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        onClick = {
                            if (ui.driveSnapshot.value.state == DriveState.PARKED) onOpenParkedSettings()
                        },
                    ) { Text(stringResource(R.string.recording_parked_settings)) }
                }
            }
        }
    }
}
