package app.memoryvault.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.memoryvault.sync.MemoryLinkProgress
import app.memoryvault.platform.capture.CaptureEntry
import app.memoryvault.sync.ui.MemoryLinkEntry
import app.memoryvault.sync.ui.MemoryLinkNoteStatus
import kotlinx.coroutines.flow.StateFlow
import app.memoryvault.DemoConfig
import app.memoryvault.R
import app.memoryvault.TypedMemoResult
import app.memoryvault.data.DayDraft
import app.memoryvault.data.LinkState
import app.memoryvault.data.Note
import app.memoryvault.data.NoteVisitLink
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveReadinessReason
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.platform.PhotoAccess
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
    save = { listOf(it.visitId ?: "", it.noteId ?: -1L, it.text, it.isOpen, it.openedGeneration) },
    restore = {
        MemoEditorState((it[0] as String).ifEmpty { null }, (it[1] as Long).takeIf { id -> id >= 0 },
            it[2] as String, it[3] as Boolean, it.getOrNull(4) as? Long ?: 0L)
    },
)

@Composable
fun MemoryVaultScreen(
    ui: MemoryVaultUi,
    onRequestPermissions: () -> Unit,
    permissionSummary: Int = R.string.voice_permissions_needed,
    onRequestPhotoPermissions: () -> Unit = {},
    onShareDay: () -> Unit = {},
) {
    val snapshot by ui.driveSnapshot.collectAsStateWithLifecycle()
    val voice by ui.voiceState.collectAsStateWithLifecycle()
    val readyEnabled by ui.readyNotificationEnabled.collectAsStateWithLifecycle()
    val photoReview by ui.photoReview.collectAsStateWithLifecycle()
    val displayedPhotoGeneration = snapshot.invalidationGeneration
    fun photosAllowed() = ui.driveSnapshot.value.let {
        it.state == DriveState.PARKED && it.invalidationGeneration == displayedPhotoGeneration
    }
    var editor by rememberSaveable(stateSaver = editorSaver) { mutableStateOf(MemoEditorState()) }
    var typedNotice by rememberSaveable { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    var photoSaving by remember { mutableStateOf(false) }
    var photoNotice by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(snapshot.state, snapshot.invalidationGeneration) {
        if (snapshot.state != DriveState.PARKED || snapshot.invalidationGeneration != editor.openedGeneration) {
            editor = editor.onDriveChanged(snapshot.state, snapshot.invalidationGeneration)
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    DisposableEffect(lifecycle, focus, keyboard) {
        val observer = MemoEditorLifecycleObserver(
            currentEditor = { editor },
            updateEditor = { editor = it },
            closeInput = { focus.clearFocus(force = true); keyboard?.hide() },
        )
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
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
                app.memoryvault.share.ui.ShareSavedReceiptNotice(ui.driveSnapshot, DemoConfig.DEMO_DATE.toString())
                OutlinedButton(onClick = { if (photosAllowed()) onShareDay() },
                    modifier = Modifier.heightIn(min = 64.dp).testTag("open-share")) {
                    Text(stringResource(R.string.share_open_day))
                }
                ParkedSetup(
                    readyEnabled, permissionSummary,
                    onPermissions = { if (ui.driveSnapshot.value.state == DriveState.PARKED) onRequestPermissions() },
                    onReady = { ui.setReadyNotificationEnabled(!ui.readyNotificationEnabled.value) },
                )
                VoiceControls(snapshot.state, voice, onStart = {
                    if (ui.driveSnapshot.value.state == DriveState.DRIVING) ui.startVoice()
                }, onCancel = ui::cancelVoice)
                PhotoSetup(photoReview, photoNotice, photoSaving,
                    onPermissions = { if (photosAllowed()) onRequestPhotoPermissions() },
                    onRefresh = { if (photosAllowed()) ui.refreshPhotos() },
                )
                MemoryLinkEntry(ui.driveSnapshot, ui.aiProgress)
                CaptureEntry(ui.driveSnapshot)
                app.memoryvault.memory.ui.MemoryAssociationEntry(ui.driveSnapshot)
                typedNotice?.let { Text(stringResource(it), modifier = Modifier.testTag("typed-result")) }
                if (editor.visitId != null && editor.text.isNotEmpty() && !editor.isOpen && editor.pendingTarget == null) {
                    OutlinedButton(
                        onClick = {
                            val current = ui.driveSnapshot.value
                            editor = editor.resume(current.state, current.invalidationGeneration)
                            typedNotice = null
                        },
                        enabled = !saving, modifier = Modifier.heightIn(min = 64.dp),
                    ) { Text(stringResource(R.string.memo_resume)) }
                }
                val day by ui.day.collectAsStateWithLifecycle(initialValue = DayDraftState.Loading)
                when (val state = day) {
                    DayDraftState.Loading -> Text(stringResource(R.string.storage_loading))
                    DayDraftState.Failed -> Text(stringResource(R.string.storage_unavailable))
                    is DayDraftState.Ready -> DayContent(
                        state.draft, editingEnabled = !saving,
                        onAdd = { id ->
                            val current = ui.driveSnapshot.value
                            editor = editor.openAdd(id, current.state, current.invalidationGeneration)
                            typedNotice = null
                        },
                        onEdit = { note ->
                            val current = ui.driveSnapshot.value
                            editor = editor.openEdit(note, current.state, current.invalidationGeneration)
                            typedNotice = null
                        },
                        photoAccess = photoReview.permissions.access != PhotoAccess.DENIED,
                        ui = ui, thumbnailEpoch = photoReview.thumbnailEpoch,
                        photoActionsEnabled = !photoSaving,
                        onPhotoDecision = { uri, decision ->
                            if (photosAllowed() && !photoSaving) {
                                photoSaving = true
                                scope.launch {
                                    try {
                                        photoNotice = (if (photosAllowed()) ui.setPhotoState(uri, decision)
                                            else PhotoReviewResult.BLOCKED).textResource()
                                    } finally { photoSaving = false }
                                }
                            }
                        },
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
    if (snapshot.state == DriveState.PARKED && editor.isOpen && snapshot.invalidationGeneration == editor.openedGeneration) {
        AlertDialog(
            onDismissRequest = { editor = editor.close(); focus.clearFocus(force = true); keyboard?.hide() },
            title = { Text(stringResource(if (editor.noteId == null) R.string.memo_add else R.string.memo_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editor.text,
                        onValueChange = {
                            val current = ui.driveSnapshot.value
                            editor = editor.changeText(it, current.state, current.invalidationGeneration)
                        },
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
                                    val current = ui.driveSnapshot.value
                                    val result = if (!submitted.canSubmit(current.state, current.invalidationGeneration)) TypedMemoResult.BLOCKED
                                    else submitted.noteId?.let { ui.updateTypedNote(it, submitted.text) }
                                        ?: ui.addTypedNote(visitId, submitted.text)
                                    typedNotice = result.textResource()
                                    if (result == TypedMemoResult.SAVED) {
                                        editor = MemoEditorState()
                                        focus.clearFocus(force = true)
                                        keyboard?.hide()
                                    } else {
                                        val latest = ui.driveSnapshot.value
                                        editor = editor.onDriveChanged(latest.state, latest.invalidationGeneration)
                                    }
                                } finally { saving = false }
                            }
                        }
                    },
                ) { Text(stringResource(if (saving) R.string.memo_saving else R.string.memo_save)) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { editor = editor.close(); focus.clearFocus(force = true); keyboard?.hide() },
                    enabled = !saving, modifier = Modifier.heightIn(min = 64.dp),
                ) { Text(stringResource(R.string.memo_close)) }
            },
        )
    }
    if (snapshot.state == DriveState.PARKED && editor.pendingTarget != null && snapshot.invalidationGeneration == editor.openedGeneration) {
        AlertDialog(
            modifier = Modifier.testTag("discard-draft-confirmation"),
            onDismissRequest = { editor = editor.cancelDiscard() },
            title = { Text(stringResource(R.string.memo_discard_title)) },
            text = { Text(stringResource(R.string.memo_discard_help)) },
            confirmButton = {
                Button(
                    modifier = Modifier.heightIn(min = 64.dp),
                    onClick = {
                        val current = ui.driveSnapshot.value
                        editor = editor.confirmDiscard(current.state, current.invalidationGeneration)
                        typedNotice = null
                    },
                ) { Text(stringResource(R.string.memo_discard_confirm)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { editor = editor.cancelDiscard() }, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.memo_discard_cancel))
                }
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
private fun DayContent(
    draft: DayDraft,
    editingEnabled: Boolean,
    onAdd: (String) -> Unit,
    onEdit: (Note) -> Unit,
    photoAccess: Boolean,
    ui: MemoryVaultUi,
    thumbnailEpoch: PhotoThumbnailEpoch?,
    photoActionsEnabled: Boolean,
    onPhotoDecision: (String, LinkState) -> Unit,
) {
    if (photoAccess && draft.unlinkedPhotos.isNotEmpty()) Text(stringResource(R.string.photo_unlinked_count, draft.unlinkedPhotos.size))
    if (draft.inaccessiblePhotoCount > 0) Text(stringResource(R.string.photo_inaccessible_count, draft.inaccessiblePhotoCount))
    Text(stringResource(R.string.pending_voice_title), style = MaterialTheme.typography.titleLarge)
    if (draft.pendingNotes.isEmpty()) Text(stringResource(R.string.pending_voice_empty))
    draft.pendingNotes.forEach { note ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoContent(note, emptyList(), editingEnabled, onEdit, ui.aiProgress)
            }
        }
    }
    draft.cards.forEach { card ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(card.visit.placeName, style = MaterialTheme.typography.titleLarge)
                Text("${formatTime(card.visit.arrivedAt)} – ${formatTime(card.visit.leftAt)}")
                if (card.notes.isEmpty()) Text(stringResource(R.string.memo_empty))
                card.notes.forEach { MemoContent(it, card.noteLinks, editingEnabled, onEdit, ui.aiProgress) }
                if (photoAccess) PhotoList(card.photos, photoActionsEnabled, onPhotoDecision, ui, thumbnailEpoch)
                else Text(stringResource(R.string.photo_access_card_hidden))
                OutlinedButton(onClick = { onAdd(card.visit.id) }, enabled = editingEnabled, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.memo_add))
                }
            }
        }
    }
}

@Composable
private fun MemoContent(note: Note, links: List<NoteVisitLink>, editingEnabled: Boolean, onEdit: (Note) -> Unit, aiProgress: StateFlow<MemoryLinkProgress>) {
    val presentation = presentationOf(note, links)
    if (presentation == NotePresentation.VOICE_PENDING) MemoryLinkNoteStatus(note.id, aiProgress)
    else Text(stringResource(when (presentation) {
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
