package app.memoryvault.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.memoryvault.R
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceCaptureTiming
import app.memoryvault.service.VoiceNotice
import app.memoryvault.service.VoiceServiceState
import app.memoryvault.service.notice
import app.memoryvault.ui.components.P2VoiceNoticeWindow
import java.util.Locale
import kotlinx.coroutines.delay

internal val VoiceServiceState.isBusy: Boolean
    get() = this == VoiceServiceState.CheckingVehicle || this == VoiceServiceState.Preparing ||
        this == VoiceServiceState.Listening || this == VoiceServiceState.Processing

// Keep a completed session's deadline when switching between the home and recording Activity.
private val successNotices = P2VoiceNoticeWindow()

@Composable
internal fun VoiceControls(
    drive: DriveState,
    voice: VoiceServiceState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onFinish: () -> Unit = {},
    timing: VoiceCaptureTiming = VoiceCaptureTiming(),
    modifier: Modifier = Modifier,
    noticeWindow: P2VoiceNoticeWindow = successNotices,
    nowElapsedMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    val busy = voice.isBusy
    val colors = MaterialTheme.colorScheme
    val session = voice to timing
    var expiredSession by remember(noticeWindow) { mutableStateOf<Pair<VoiceServiceState, VoiceCaptureTiming>?>(null) }
    val remaining = noticeWindow.remainingMillis(voice, timing, nowElapsedMs())
    val successExpired = remaining == 0L || expiredSession == session
    LaunchedEffect(voice, timing, noticeWindow) {
        val wait = noticeWindow.remainingMillis(voice, timing, nowElapsedMs())
            ?: return@LaunchedEffect
        delay(wait)
        // A previous session's effect can never reset the presentation of a different session.
        expiredSession = session
    }
    val elapsed by produceState(initialValue = elapsedMillis(timing), key1 = timing, key2 = voice) {
        do {
            value = elapsedMillis(timing)
            if (voice != VoiceServiceState.Listening || timing.startedAtElapsedMs == null || timing.endedAtElapsedMs != null) break
            delay(250)
        } while (true)
    }
    Card(modifier.fillMaxWidth().testTag("p2-record-card"), colors = CardDefaults.cardColors(
        containerColor = colors.primary, contentColor = colors.onPrimary)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.p2_record_eyebrow), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                if (drive != DriveState.UNKNOWN) Surface(
                    color = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
                    shape = MaterialTheme.shapes.large, modifier = Modifier.testTag("p2-drive-badge"),
                ) { Text(stringResource(when (drive) {
                    DriveState.PARKED -> R.string.p2_drive_parked
                    DriveState.DRIVING -> R.string.p2_drive_driving
                    else -> R.string.drive_transition
                }), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) }
            }
            Text(stringResource(if (voice == VoiceServiceState.Listening) R.string.p2_record_listening_title
                else R.string.p2_record_title), style = MaterialTheme.typography.headlineMedium)
            if (timing.startedAtElapsedMs != null && !successExpired &&
                (voice == VoiceServiceState.Listening || voice == VoiceServiceState.Processing || voice is VoiceServiceState.Finished)) {
                val seconds = elapsed / 1_000
                Text(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60),
                    Modifier.testTag("p2-record-elapsed"), style = MaterialTheme.typography.headlineLarge)
            }
            Text(stringResource(when {
                voice == VoiceServiceState.Idle || successExpired -> R.string.p2_record_idle
                voice == VoiceServiceState.Listening -> R.string.p2_record_listening
                voice == VoiceServiceState.CheckingVehicle -> R.string.p2_voice_storage_checking
                else -> voice.notice().textResource()
            }))
            if (!busy) {
                Button(onClick = onStart, enabled = drive != DriveState.TRANSITION,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("p2-record-start")) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(28.dp))
                    Text(stringResource(R.string.voice_start), Modifier.padding(start = 12.dp))
                }
            }
            if (voice == VoiceServiceState.Listening) {
                Button(onClick = onFinish,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("p2-record-finish")) {
                    Text(stringResource(R.string.p2_record_finish))
                }
            }
            if (busy) {
                OutlinedButton(onClick = onCancel,
                    border = BorderStroke(1.dp, colors.onPrimary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onPrimary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("p2-record-cancel")) {
                    Text(stringResource(R.string.voice_cancel_recording))
                }
            }
        }
    }
}

private fun elapsedMillis(timing: VoiceCaptureTiming): Long {
    val start = timing.startedAtElapsedMs ?: return 0L
    val end = timing.endedAtElapsedMs ?: (System.nanoTime() / 1_000_000L)
    return (end - start).coerceAtLeast(0)
}

private fun VoiceNotice.textResource() = when (this) {
    VoiceNotice.CHECKING -> R.string.p2_voice_storage_checking
    VoiceNotice.PREPARING -> R.string.voice_preparing
    VoiceNotice.RECORDING -> R.string.p2_record_listening
    VoiceNotice.PROCESSING -> R.string.p2_record_processing
    VoiceNotice.SAVED -> R.string.voice_saved
    VoiceNotice.SAVED_CLEANUP_WARNING -> R.string.voice_saved_cleanup_warning
    VoiceNotice.NOTHING_HEARD -> R.string.voice_nothing_heard
    VoiceNotice.TIMED_OUT -> R.string.voice_timeout
    VoiceNotice.INTERRUPTED -> R.string.voice_interrupted
    VoiceNotice.STORAGE_FAILED -> R.string.p2_record_storage_failed
    VoiceNotice.FAILED -> R.string.voice_failed
    VoiceNotice.FAILED_CLEANUP_WARNING -> R.string.p2_record_cleanup_failed
    VoiceNotice.UNAVAILABLE -> R.string.p2_record_unavailable
}
