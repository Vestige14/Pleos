package app.memoryvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.memoryvault.R
import app.memoryvault.domain.DriveState
import app.memoryvault.service.VoiceNotice
import app.memoryvault.service.VoiceServiceState
import app.memoryvault.service.notice

internal val VoiceServiceState.isBusy: Boolean
    get() = this == VoiceServiceState.CheckingVehicle || this == VoiceServiceState.Preparing ||
        this == VoiceServiceState.Listening || this == VoiceServiceState.Processing

@Composable
internal fun VoiceControls(drive: DriveState, voice: VoiceServiceState, onStart: () -> Unit, onCancel: () -> Unit) {
    val busy = voice.isBusy
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (voice == VoiceServiceState.Listening) {
            Text(stringResource(R.string.voice_recording_title))
        }
        Text(stringResource(if (voice == VoiceServiceState.Idle) R.string.voice_idle else voice.notice().textResource()))
        if (drive == DriveState.DRIVING && !busy) {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(28.dp))
                Text(stringResource(R.string.voice_leave_memo), modifier = Modifier.padding(start = 12.dp))
            }
        }
        if (busy) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text(stringResource(R.string.voice_cancel_recording))
            }
        }
    }
}

private fun VoiceNotice.textResource() = when (this) {
    VoiceNotice.CHECKING -> R.string.voice_checking
    VoiceNotice.PREPARING -> R.string.voice_preparing
    VoiceNotice.RECORDING -> R.string.voice_recording
    VoiceNotice.PROCESSING -> R.string.voice_processing
    VoiceNotice.SAVED -> R.string.voice_saved
    VoiceNotice.SAVED_CLEANUP_WARNING -> R.string.voice_saved_cleanup_warning
    VoiceNotice.NOTHING_HEARD -> R.string.voice_nothing_heard
    VoiceNotice.TIMED_OUT -> R.string.voice_timeout
    VoiceNotice.INTERRUPTED -> R.string.voice_interrupted
    VoiceNotice.STORAGE_FAILED -> R.string.voice_storage_failed
    VoiceNotice.FAILED -> R.string.voice_failed
    VoiceNotice.FAILED_CLEANUP_WARNING -> R.string.voice_cleanup_failed
    VoiceNotice.UNAVAILABLE -> R.string.voice_unavailable
}
