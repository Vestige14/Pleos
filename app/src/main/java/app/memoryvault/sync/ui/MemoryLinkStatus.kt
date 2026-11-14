package app.memoryvault.sync.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.memoryvault.R
import app.memoryvault.domain.AiLinkJobState
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.sync.LinkError
import app.memoryvault.sync.MemoryLinkRuntime
import app.memoryvault.sync.MemoryLinkProgress
import kotlinx.coroutines.flow.StateFlow

/** Minimal stage-6 entry/state; contains no proposal confirmation controls. */
@Composable
fun MemoryLinkEntry(driveSnapshot: StateFlow<DriveSnapshot>, progressState: StateFlow<MemoryLinkProgress>) {
    val drive by driveSnapshot.collectAsStateWithLifecycle()
    val progress by progressState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val generation = drive.invalidationGeneration
    fun allowed() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && driveSnapshot.value.let {
        it.state == DriveState.PARKED && it.invalidationGeneration == generation
    }
    if (drive.state == DriveState.PARKED) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(if (progress.available) R.string.ai_summary else R.string.ai_preparing,
                    progress.waiting, progress.suggested, progress.review, progress.blocked))
                Text(stringResource(R.string.ai_suggestion_notice))
                progress.notes.values.firstOrNull { it.state == AiLinkJobState.BLOCKED }?.error?.let { FixedError(it) }
                Button(onClick = { if (allowed()) context.startActivity(Intent(context, MemoryLinkSetupActivity::class.java)) },
                    modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.ai_enter_setup)) }
                OutlinedButton(onClick = { if (allowed()) MemoryLinkRuntime.reconcilePendingJobs(context) },
                    modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.ai_reconcile)) }
            }
        }
    }
}

@Composable
fun MemoryLinkNoteStatus(noteId: Long, progressState: StateFlow<MemoryLinkProgress>) {
    val progress by progressState.collectAsStateWithLifecycle()
    val note = progress.notes[noteId]
    val label = when (note?.state) {
        AiLinkJobState.RUNNING -> R.string.ai_note_running
        AiLinkJobState.RETRY_WAIT -> R.string.ai_note_retry
        AiLinkJobState.REVIEW_REQUIRED, AiLinkJobState.SUPERSEDED -> R.string.ai_note_review
        AiLinkJobState.BLOCKED -> R.string.ai_note_blocked
        AiLinkJobState.DONE -> R.string.ai_note_suggested
        else -> R.string.memo_voice_pending
    }
    Text(stringResource(label))
    if (note?.state in setOf(AiLinkJobState.REVIEW_REQUIRED, AiLinkJobState.BLOCKED)) note?.error?.let { FixedError(it) }
}

@Composable private fun FixedError(code: LinkError) {
    when (code) {
        LinkError.UNAUTHORIZED -> Text(stringResource(R.string.ai_unauthorized))
        LinkError.OUTCOME_UNKNOWN -> Text(stringResource(R.string.ai_unknown_outcome))
        else -> Text(stringResource(R.string.ai_fixed_error, code.name))
    }
}
