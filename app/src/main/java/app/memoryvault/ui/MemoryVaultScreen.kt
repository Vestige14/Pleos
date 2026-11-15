package app.memoryvault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import app.memoryvault.data.SeedLoader
import app.memoryvault.data.Visit
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveReadinessReason
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.platform.PhotoAccess
import app.memoryvault.p1.provenance.ParkedProvenanceBadges
import app.memoryvault.p1.provenance.ProvenancePolicy
import app.memoryvault.ui.components.P2ActionPair
import app.memoryvault.ui.components.P2HomeNavigation
import app.memoryvault.ui.components.P2HomePane
import app.memoryvault.ui.components.P2RecordShareActions
import app.memoryvault.ui.components.P2ScreenColumn
import app.memoryvault.ui.components.P2SectionHeading
import app.memoryvault.ui.theme.P2DarkColors
import app.memoryvault.ui.theme.P2LightColors
import app.memoryvault.ui.theme.P2Typography
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun MemoryVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) P2DarkColors else P2LightColors,
        typography = P2Typography, content = content,
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
    val voiceTiming by ui.voiceTiming.collectAsStateWithLifecycle()
    val readyEnabled by ui.readyNotificationEnabled.collectAsStateWithLifecycle()
    val photoReview by ui.photoReview.collectAsStateWithLifecycle()
    val displayedPhotoGeneration = snapshot.invalidationGeneration
    val navigation = remember(ui) { P2HomeNavigation() }
    val navigationState by navigation.state.collectAsStateWithLifecycle()
    var editor by rememberSaveable(stateSaver = editorSaver) { mutableStateOf(MemoEditorState()) }
    var typedNotice by rememberSaveable { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    var photoSaving by remember { mutableStateOf(false) }
    var photoNotice by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val appContext = LocalContext.current.applicationContext
    val knownReplayVisits = remember(appContext) {
        runCatching {
            appContext.assets.open("seed_day.json").bufferedReader().use { SeedLoader.parse(it.readText()).visits.toSet() }
        }.getOrDefault(emptySet())
    }
    var provenanceForeground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycle, ui, navigation) {
        val observer = LifecycleEventObserver { _, _ ->
            provenanceForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            navigation.revalidate(ui.driveSnapshot.value, provenanceForeground)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); navigation.home() }
    }
    val provenanceVisible = provenanceForeground && snapshot.state == DriveState.PARKED
    fun photosAllowed() = provenanceForeground && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
        ui.driveSnapshot.value.let {
            it.state == DriveState.PARKED && it.invalidationGeneration == displayedPhotoGeneration
        }
    fun paneAllowed(pane: P2HomePane) = photosAllowed() && navigation.state.value.pane == pane &&
        (pane == P2HomePane.HOME || navigation.isCurrent(ui.driveSnapshot.value, provenanceForeground))
    fun openPane(pane: P2HomePane) {
        if (!saving && !photoSaving && !editor.isOpen && editor.pendingTarget == null && photosAllowed()) {
            if (navigation.open(pane, ui.driveSnapshot.value, provenanceForeground, displayedPhotoGeneration)) photoNotice = null
        }
    }
    fun decidePhoto(pane: P2HomePane, uri: String, decision: LinkState) {
        if (!photoSaving && paneAllowed(pane)) {
            photoSaving = true
            scope.launch {
                try {
                    val result = if (paneAllowed(pane)) ui.setPhotoState(uri, decision) else PhotoReviewResult.BLOCKED
                    if (paneAllowed(pane)) photoNotice = result.textResource()
                } finally { photoSaving = false }
            }
        }
    }
    LaunchedEffect(snapshot.state, snapshot.invalidationGeneration, provenanceForeground) {
        navigation.revalidate(ui.driveSnapshot.value, provenanceForeground)
    }
    val pane = if (navigation.isCurrent(ui.driveSnapshot.value, provenanceForeground)) navigationState.pane else P2HomePane.HOME
    BackHandler(enabled = navigationState.pane != P2HomePane.HOME) { navigation.home() }

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

    val day = if (provenanceVisible && photosAllowed() && pane != P2HomePane.SETTINGS) {
        val observed by ui.day.collectAsStateWithLifecycle(initialValue = DayDraftState.Loading)
        observed
    } else DayDraftState.Loading

    Scaffold { insets ->
        when (pane) {
            P2HomePane.SETTINGS -> SettingsScreen(onHome = navigation::home, modifier = Modifier.padding(insets),
                voice = {
                    ParkedSetup(readyEnabled, permissionSummary,
                        onPermissions = { if (paneAllowed(P2HomePane.SETTINGS)) onRequestPermissions() },
                        onReady = { if (paneAllowed(P2HomePane.SETTINGS)) ui.setReadyNotificationEnabled(!ui.readyNotificationEnabled.value) })
                }, photos = {
                    PhotoSetup(photoReview, photoNotice, photoSaving,
                        onPermissions = { if (paneAllowed(P2HomePane.SETTINGS)) onRequestPhotoPermissions() },
                        onRefresh = { if (paneAllowed(P2HomePane.SETTINGS)) ui.refreshPhotos() })
                }, ai = { MemoryLinkEntry(ui.driveSnapshot, ui.aiProgress) })
            P2HomePane.PHOTOS -> {
                val epochAllowed = photoReview.thumbnailEpoch?.let {
                    it.driveGeneration == displayedPhotoGeneration && ui.thumbnailAllowed(it)
                } ?: false
                PhotoAlbumScreen(day, photoReview, ui,
                    contentAllowed = navigation.canShowPhotos(ui.driveSnapshot.value, photosAllowed(), photoReview.permissions.access, epochAllowed),
                    busy = photoSaving, notice = photoNotice,
                    onDecision = { uri, decision -> decidePhoto(P2HomePane.PHOTOS, uri, decision) },
                    onHome = navigation::home, onSettings = { openPane(P2HomePane.SETTINGS) }, modifier = Modifier.padding(insets))
            }
            P2HomePane.HOME -> P2ScreenColumn(Modifier.padding(insets)) {
                if (snapshot.state != DriveState.DRIVING)
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                P2RecordShareActions(showShare = snapshot.state == DriveState.PARKED,
                    record = { actionModifier ->
                        VoiceControls(snapshot.state, voice, onStart = {
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                                ui.driveSnapshot.value.state != DriveState.TRANSITION && !ui.voiceState.value.isBusy) ui.startVoice()
                        }, onCancel = { if (ui.voiceState.value.isBusy) ui.cancelVoice() },
                            onFinish = { if (ui.voiceState.value == app.memoryvault.service.VoiceServiceState.Listening) ui.finishVoice() },
                            timing = voiceTiming, modifier = actionModifier)
                    }, share = { actionModifier ->
                        Card(actionModifier) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(stringResource(R.string.p2_share_eyebrow), style = MaterialTheme.typography.labelMedium)
                                Text(stringResource(R.string.p2_share_title), style = MaterialTheme.typography.headlineMedium)
                                Text(stringResource(R.string.p2_share_help))
                                OutlinedButton(onClick = {
                                    if (photosAllowed() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) onShareDay()
                                }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("open-share")) {
                                    Text(stringResource(R.string.p2_share_open))
                                }
                            }
                        }
                    })
                if (provenanceVisible && photosAllowed()) {
                    val shownDraft = (day as? DayDraftState.Ready)?.draft
                    P2ActionPair(first = { actionModifier ->
                        OutlinedButton(onClick = { openPane(P2HomePane.PHOTOS) }, enabled = !saving && !photoSaving,
                            modifier = actionModifier.heightIn(min = 64.dp).testTag("open-photo-album")) {
                            Text(stringResource(R.string.p2_photo_album))
                        }
                    }, second = { actionModifier ->
                        OutlinedButton(onClick = { openPane(P2HomePane.SETTINGS) }, enabled = !saving && !photoSaving,
                            modifier = actionModifier.heightIn(min = 64.dp).testTag("open-settings")) {
                            Text(stringResource(R.string.p2_settings))
                        }
                    })
                    Text(stringResource(R.string.day_draft), style = MaterialTheme.typography.headlineMedium)
                    shownDraft?.let { RecordDatePicker(ui, it.date) }
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
                    when (val state = day) {
                        DayDraftState.Loading -> Text(stringResource(R.string.storage_loading))
                        DayDraftState.Failed -> Text(stringResource(R.string.storage_unavailable))
                        is DayDraftState.Ready -> DayContent(
                            state.draft, editingEnabled = !saving,
                            provenanceVisible = provenanceVisible, knownReplayVisits = knownReplayVisits,
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
                            onPhotoDecision = { uri, decision -> decidePhoto(P2HomePane.HOME, uri, decision) },
                        )
                    }
                    P2SectionHeading(stringResource(R.string.p2_home_tools))
                    CaptureEntry(ui.driveSnapshot)
                    app.memoryvault.memory.ui.MemoryAssociationEntry(ui.driveSnapshot)
                } else {
                    if (snapshot.state != DriveState.DRIVING && snapshot.state != DriveState.PARKED) LockedStatus(snapshot, ui::retryVehicleConnection)
                }
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
            Text(stringResource(R.string.p2_parked_setup), style = MaterialTheme.typography.titleLarge)
            P2ActionPair(first = { actionModifier ->
                OutlinedButton(onClick = onPermissions, modifier = actionModifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.voice_permissions_setup))
                }
            }, second = { actionModifier ->
                OutlinedButton(onClick = onReady, modifier = actionModifier.heightIn(min = 64.dp)) {
                    Text(stringResource(if (ready) R.string.voice_ready_disable_button else R.string.voice_ready_enable_button))
                }
            })
        }
    }
}

@Composable
private fun LockedStatus(snapshot: DriveSnapshot, onRetry: () -> Unit) {
    val transition = snapshot.state == DriveState.TRANSITION
    Text(stringResource(if (transition) R.string.drive_transition else R.string.drive_unknown), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(when {
        transition -> R.string.p2_drive_transition_help
        snapshot.readinessReason == DriveReadinessReason.PERMISSION_DENIED -> R.string.p2_drive_permission_missing
        snapshot.readinessReason in setOf(DriveReadinessReason.CONNECTING, DriveReadinessReason.NOT_STARTED,
            DriveReadinessReason.MISSING_GEAR, DriveReadinessReason.MISSING_SPEED, DriveReadinessReason.MISSING_UX) -> R.string.drive_connecting
        else -> R.string.p2_drive_unavailable_help
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
    provenanceVisible: Boolean,
    knownReplayVisits: Set<Visit>,
    onAdd: (String) -> Unit,
    onEdit: (Note) -> Unit,
    photoAccess: Boolean,
    ui: MemoryVaultUi,
    thumbnailEpoch: PhotoThumbnailEpoch?,
    photoActionsEnabled: Boolean,
    onPhotoDecision: (String, LinkState) -> Unit,
) {
    draft.pendingNotes.forEach { note ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoContent(note, emptyList(), editingEnabled, onEdit, ui.aiProgress, provenanceVisible)
            }
        }
    }
    draft.cards.forEach { card ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(card.visit.placeName, style = MaterialTheme.typography.titleLarge)
                Text("${formatTime(card.visit.arrivedAt)} – ${formatTime(card.visit.leftAt)}")
                if (card.notes.isEmpty()) Text(stringResource(R.string.memo_empty))
                card.notes.forEach { MemoContent(it, card.noteLinks, editingEnabled, onEdit, ui.aiProgress, provenanceVisible) }
                if (photoAccess) PhotoList(card.photos, photoActionsEnabled, onPhotoDecision, ui, thumbnailEpoch, provenanceVisible)
                else Text(stringResource(R.string.photo_access_card_hidden))
                OutlinedButton(onClick = { onAdd(card.visit.id) }, enabled = editingEnabled, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.memo_add))
                }
            }
        }
    }
}

@Composable
private fun MemoContent(note: Note, links: List<NoteVisitLink>, editingEnabled: Boolean, onEdit: (Note) -> Unit,
    aiProgress: StateFlow<MemoryLinkProgress>, provenanceVisible: Boolean) {
    val presentation = presentationOf(note, links)
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
