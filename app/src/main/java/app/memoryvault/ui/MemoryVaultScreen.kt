package app.memoryvault.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.memoryvault.DemoConfig
import app.memoryvault.R
import app.memoryvault.TypedMemoResult
import app.memoryvault.data.DayDraft
import app.memoryvault.data.Note
import app.memoryvault.data.NoteVisitLink
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveReadinessReason
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.service.VoiceNotice
import app.memoryvault.service.VoiceServiceState
import app.memoryvault.service.notice
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val largeBody = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)
private val typography = Typography(
    bodyLarge = largeBody, bodyMedium = largeBody, labelLarge = largeBody,
    titleMedium = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
)

@Composable
fun MemoryVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        typography = typography, content = content,
    )
}

private val editorSaver = listSaver<MemoEditorState, Any>(
    save = { listOf(it.visitId ?: "", it.noteId ?: -1L, it.text, it.isOpen) },
    restore = {
        MemoEditorState((it[0] as String).ifEmpty { null }, (it[1] as Long).takeIf { id -> id >= 0 }, it[2] as String, it[3] as Boolean)
    },
)

@Composable
fun MemoryVaultScreen(
    ui: MemoryVaultUi,
    onRequestPermissions: () -> Unit,
    permissionSummary: Int = R.string.voice_permissions_needed,
) {
    val snapshot by ui.driveSnapshot.collectAsStateWithLifecycle()
    val voice by ui.voiceState.collectAsStateWithLifecycle()
    val readyEnabled by ui.readyNotificationEnabled.collectAsStateWithLifecycle()
    var editor by rememberSaveable(stateSaver = editorSaver) { mutableStateOf(MemoEditorState()) }
    var typedNotice by rememberSaveable { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(snapshot.state) {
        if (snapshot.state != DriveState.PARKED) {
            editor = editor.onDriveChanged(snapshot.state)
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    DisposableEffect(Unit) {
        onDispose { focus.clearFocus(force = true); keyboard?.hide() }
    }

    Scaffold { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.replay_capture_reference))
            if (snapshot.state == DriveState.PARKED) {
                Text(stringResource(R.string.day_draft), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.demo_reference, DemoConfig.DEMO_DATE.toString(), DemoConfig.DEMO_ZONE.id))
                ParkedSetup(
                    readyEnabled, permissionSummary,
                    onPermissions = { if (ui.driveSnapshot.value.state == DriveState.PARKED) onRequestPermissions() },
                    onReady = { ui.setReadyNotificationEnabled(!ui.readyNotificationEnabled.value) },
                )
                VoiceControls(snapshot.state, voice, onStart = {
                    if (ui.driveSnapshot.value.state == DriveState.DRIVING) ui.startVoice()
                }, onCancel = ui::cancelVoice)
                typedNotice?.let { Text(stringResource(it), modifier = Modifier.testTag("typed-result")) }
                if (editor.visitId != null && editor.text.isNotEmpty() && !editor.isOpen) {
                    OutlinedButton(
                        onClick = { editor = editor.resume(ui.driveSnapshot.value.state); typedNotice = null },
                        enabled = !saving, modifier = Modifier.heightIn(min = 64.dp),
                    ) { Text(stringResource(R.string.memo_resume)) }
                }
                val day by ui.day.collectAsStateWithLifecycle(initialValue = DayDraftState.Loading)
                when (val state = day) {
                    DayDraftState.Loading -> Text(stringResource(R.string.storage_loading))
                    DayDraftState.Failed -> Text(stringResource(R.string.storage_unavailable))
                    is DayDraftState.Ready -> DayContent(
                        state.draft, editingEnabled = !saving,
                        onAdd = { id -> editor = editor.openAdd(id, ui.driveSnapshot.value.state); typedNotice = null },
                        onEdit = { note -> editor = editor.openEdit(note, ui.driveSnapshot.value.state); typedNotice = null },
                    )
                }
            } else {
                if (snapshot.state != DriveState.DRIVING) LockedStatus(snapshot, ui::retryVehicleConnection)
                VoiceControls(snapshot.state, voice, onStart = {
                    if (ui.driveSnapshot.value.state == DriveState.DRIVING) ui.startVoice()
                }, onCancel = ui::cancelVoice)
            }
        }
    }

    // Observed state gates rendering; callbacks read the current authoritative source again.
    if (snapshot.state == DriveState.PARKED && editor.isOpen) {
        AlertDialog(
            onDismissRequest = { editor = editor.copy(isOpen = false); focus.clearFocus(force = true); keyboard?.hide() },
            title = { Text(stringResource(if (editor.noteId == null) R.string.memo_add else R.string.memo_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editor.text,
                        onValueChange = { editor = editor.changeText(it, ui.driveSnapshot.value.state) },
                        label = { Text(stringResource(R.string.memo_input_label)) },
                        enabled = !saving, minLines = 3, maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().testTag("memo-input"),
                    )
                    typedNotice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !saving && editor.text.isNotBlank(), modifier = Modifier.heightIn(min = 64.dp),
                    onClick = {
                        val submitted = editor
                        val visitId = submitted.visitId
                        if (visitId != null && !saving) {
                            saving = true
                            scope.launch {
                                try {
                                    val result = if (ui.driveSnapshot.value.state != DriveState.PARKED) TypedMemoResult.BLOCKED
                                    else submitted.noteId?.let { ui.updateTypedNote(it, submitted.text) }
                                        ?: ui.addTypedNote(visitId, submitted.text)
                                    typedNotice = result.textResource()
                                    if (result == TypedMemoResult.SAVED) {
                                        editor = MemoEditorState()
                                        focus.clearFocus(force = true)
                                        keyboard?.hide()
                                    } else editor = editor.onDriveChanged(ui.driveSnapshot.value.state)
                                } finally { saving = false }
                            }
                        }
                    },
                ) { Text(stringResource(if (saving) R.string.memo_saving else R.string.memo_save)) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { editor = editor.copy(isOpen = false); focus.clearFocus(force = true); keyboard?.hide() },
                    enabled = !saving, modifier = Modifier.heightIn(min = 64.dp),
                ) { Text(stringResource(R.string.memo_close)) }
            },
        )
    }
}

@Composable
private fun ParkedSetup(ready: Boolean, permissionSummary: Int, onPermissions: () -> Unit, onReady: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.parked_setup), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(permissionSummary))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onPermissions, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.voice_permissions_setup))
                }
                OutlinedButton(onClick = onReady, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                    Text(stringResource(if (ready) R.string.voice_ready_disable_button else R.string.voice_ready_enable_button))
                }
            }
            Text(stringResource(R.string.voice_ready_help))
        }
    }
}

@Composable
private fun LockedStatus(snapshot: DriveSnapshot, onRetry: () -> Unit) {
    val transition = snapshot.state == DriveState.TRANSITION
    Text(stringResource(if (transition) R.string.drive_transition else R.string.drive_unknown), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(when {
        transition -> R.string.drive_transition_help
        snapshot.readinessReason == DriveReadinessReason.PERMISSION_DENIED -> R.string.drive_permission_missing
        snapshot.readinessReason in setOf(DriveReadinessReason.CONNECTING, DriveReadinessReason.NOT_STARTED,
            DriveReadinessReason.MISSING_GEAR, DriveReadinessReason.MISSING_SPEED, DriveReadinessReason.MISSING_UX) -> R.string.drive_connecting
        else -> R.string.drive_unavailable_help
    }))
    if (!transition) {
        Text(stringResource(R.string.initial_vehicle_permission_help))
        OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 64.dp)) {
            Text(stringResource(R.string.vehicle_retry))
        }
    }
}

@Composable
private fun VoiceControls(drive: DriveState, voice: VoiceServiceState, onStart: () -> Unit, onCancel: () -> Unit) {
    val busy = voice == VoiceServiceState.CheckingVehicle || voice == VoiceServiceState.Preparing ||
        voice == VoiceServiceState.Listening || voice == VoiceServiceState.Processing
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

@Composable
private fun DayContent(draft: DayDraft, editingEnabled: Boolean, onAdd: (String) -> Unit, onEdit: (Note) -> Unit) {
    Text(stringResource(R.string.pending_voice_title), style = MaterialTheme.typography.titleLarge)
    if (draft.pendingNotes.isEmpty()) Text(stringResource(R.string.pending_voice_empty))
    draft.pendingNotes.forEach { note ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoContent(note, emptyList(), editingEnabled, onEdit)
            }
        }
    }
    draft.cards.forEach { card ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(card.visit.placeName, style = MaterialTheme.typography.titleLarge)
                Text("${formatTime(card.visit.arrivedAt)} – ${formatTime(card.visit.leftAt)}")
                if (card.notes.isEmpty()) Text(stringResource(R.string.memo_empty))
                card.notes.forEach { MemoContent(it, card.noteLinks, editingEnabled, onEdit) }
                Text(stringResource(if (card.photos.isEmpty()) R.string.photo_empty else R.string.photo_count, card.photos.size))
                OutlinedButton(onClick = { onAdd(card.visit.id) }, enabled = editingEnabled, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.memo_add))
                }
            }
        }
    }
}

@Composable
private fun MemoContent(note: Note, links: List<NoteVisitLink>, editingEnabled: Boolean, onEdit: (Note) -> Unit) {
    val presentation = presentationOf(note, links)
    Text(stringResource(when (presentation) {
        NotePresentation.TYPED -> R.string.memo_typed
        NotePresentation.VOICE_PENDING -> R.string.memo_voice_pending
        NotePresentation.VOICE_SUGGESTED -> R.string.memo_voice_suggested
        NotePresentation.VOICE_CONFIRMED -> R.string.memo_voice_confirmed
    }))
    Text(note.text)
    if (presentation.editable) {
        OutlinedButton(onClick = { onEdit(note) }, enabled = editingEnabled, modifier = Modifier.heightIn(min = 64.dp)) {
            Text(stringResource(R.string.memo_edit))
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private fun formatTime(value: Long) = Instant.ofEpochMilli(value).atZone(DemoConfig.DEMO_ZONE).format(timeFormatter)

private fun TypedMemoResult.textResource() = when (this) {
    TypedMemoResult.SAVED -> R.string.memo_saved
    TypedMemoResult.BLOCKED -> R.string.memo_park_required
    TypedMemoResult.EMPTY -> R.string.memo_empty_input
    TypedMemoResult.STORAGE_UNAVAILABLE -> R.string.storage_unavailable
    TypedMemoResult.NOT_EDITABLE -> R.string.memo_not_editable
    TypedMemoResult.FAILED -> R.string.memo_save_failed
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
