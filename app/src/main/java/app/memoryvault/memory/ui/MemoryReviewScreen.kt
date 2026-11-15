package app.memoryvault.memory.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.memoryvault.R
import app.memoryvault.memory.*
import app.memoryvault.p1.provenance.ParkedProvenanceBadges
import app.memoryvault.p1.provenance.ProvenancePolicy
import app.memoryvault.ui.components.P2ActionPair
import java.time.Instant
import java.time.format.DateTimeFormatter

data class MemoryManualSelection(val item: MemoryReviewItem, val choices: List<MemorySource>)

@Composable
fun MemoryReviewScreen(
    progress: MemoryAssociationProgress, allowed: Boolean, busy: Boolean, result: MemoryReviewResult?, manual: MemoryManualSelection?,
    onOpen: () -> Unit, onClose: () -> Unit, onRefresh: () -> Unit,
    onAction: (MemoryReviewItem, MemoryReviewAction, Set<String>) -> Unit,
    onManual: (MemoryReviewItem) -> Unit, onReanalyze: (MemoryReviewItem, Boolean, String?) -> Unit,
    onPhoto: (MemorySource) -> Unit, onSetup: () -> Unit,
    onManualSave: (MemoryManualSelection, Set<String>) -> Unit = { _, _ -> }, onManualCancel: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(allowed) { if (!allowed) { focus.clearFocus(force = true); keyboard?.hide() } }
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            val sidePadding = if (maxWidth < 600.dp) 16.dp else 24.dp
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize().padding(horizontal = sidePadding),
                contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("heading") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.memory_review_title), style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = onClose, modifier = Modifier.align(androidx.compose.ui.Alignment.Start)) {
                        Text(stringResource(R.string.p2_back_home))
                    }
                }
            }
            if (!allowed) {
                item("locked") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.memory_review_locked))
                        Button(onClick = onOpen, enabled = progress.errorCode != "STORAGE_UNAVAILABLE",
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                            Text(stringResource(R.string.memory_review_open))
                        }
                    }
                }
            } else {
                item("status") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (progress.preparing || busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.memory_working)) }
                        if (progress.errorCode != null) Text(stringResource(R.string.memory_error), color = MaterialTheme.colorScheme.error)
                        result?.let { Text(resultText(it), color = if (it == MemoryReviewResult.SAVED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                        P2ActionPair(first = { actionModifier ->
                            OutlinedButton(onClick = onRefresh, enabled = !busy,
                                modifier = actionModifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.memory_refresh)) }
                        }, second = { actionModifier ->
                            TextButton(onClick = onSetup, enabled = !busy,
                                modifier = actionModifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.memory_setup)) }
                        })
                    }
                }
                if (manual != null) {
                    item("manual") {
                        var selected by remember(manual.item.note.id, manual.item.userRevision) { mutableStateOf(emptySet<String>()) }
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.memory_manual_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.memory_manual_help))
                            manual.choices.forEach { source ->
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
                                        Checkbox(checked = source.candidate.id in selected, onCheckedChange = { checked ->
                                            selected = if (checked) selected + source.candidate.id else selected - source.candidate.id
                                        }, enabled = !busy)
                                        Column(Modifier.weight(1f)) { EvidenceContent(source, onPhoto) }
                                    }
                                }
                            }
                            Button(onClick = { onManualSave(manual, selected) }, enabled = !busy && selected.size in 1..10,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                                Text(stringResource(R.string.memory_manual_save))
                            }
                            TextButton(onClick = onManualCancel) { Text(stringResource(R.string.memory_cancel)) }
                        }
                    }
                } else {
                    if (progress.items.isEmpty() && !progress.preparing && !busy && progress.errorCode == null)
                        item("empty") { Text(stringResource(R.string.memory_empty)) }
                    items(progress.items, key = { it.note.id }) { item ->
                        MemoryNoteCard(item, busy, onAction, onManual, onReanalyze, onPhoto)
                    }
                }
            }
            }
        }
    }
}

@Composable private fun MemoryNoteCard(item: MemoryReviewItem, busy: Boolean,
    onAction: (MemoryReviewItem, MemoryReviewAction, Set<String>) -> Unit,
    onManual: (MemoryReviewItem) -> Unit, onReanalyze: (MemoryReviewItem, Boolean, String?) -> Unit, onPhoto: (MemorySource) -> Unit) {
    var selected by remember(item.note.id, item.userRevision, item.links) { mutableStateOf(emptySet<String>()) }
    var date by rememberSaveable(item.note.id) { mutableStateOf("") }
    val suggestions = item.links.filter { it.status == "SUGGESTED" }
    val confirmed = item.links.any { it.status == "CONFIRMED" }
    val dateConflict = "USER_DATE_CONFLICT" in item.state.issues.split(',')
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(item.note.capturedAt).atZone(ExperienceTimeResolver.zone)),
                style = MaterialTheme.typography.titleMedium)
            if (item.state.status != "CONFIRMED") Text(statusText(item.state.status), color = MaterialTheme.colorScheme.primary)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.memory_original), style = MaterialTheme.typography.labelLarge)
                Text(item.note.text, modifier = Modifier.testTag("memory-original-text"))
            }
            // The verified P1 refinement panel belongs directly below this original VOICE text.
            val experience = item.state.eventDateOverride ?: ExperienceTimeResolver.resolve(item.note.text, item.note.capturedAt).eventDate
            Text(stringResource(R.string.memory_event_date, experience ?: stringResource(R.string.memory_date_unresolved)))
            if (item.state.errorCode != null || item.state.issues.isNotEmpty()) Text(stringResource(R.string.memory_evidence_gap), color = MaterialTheme.colorScheme.error)
            if (dateConflict) Text(stringResource(R.string.memory_date_correction_review), color = MaterialTheme.colorScheme.error)
            item.links.filter { it.status != "REJECTED" }.forEach { link ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
                        if (link.status == "SUGGESTED") Checkbox(checked = link.key in selected, enabled = !busy,
                            onCheckedChange = { checked -> selected = if (checked) selected + link.key else selected - link.key })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val quote = runCatching { MemoryWire.evidence(item.note.text, link.startCp, link.endCp) }.getOrNull()
                            if (quote != null) Text(quote, style = MaterialTheme.typography.titleSmall)
                            EvidenceContent(link.source, onPhoto)
                        }
                    }
                }
            }
            if (suggestions.isNotEmpty()) {
                Button(onClick = { onAction(item, MemoryReviewAction.CONFIRM, selected) }, enabled = !busy && selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.memory_confirm_selected))
                }
                OutlinedButton(onClick = { onAction(item, MemoryReviewAction.REJECT, emptySet()) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.memory_reject))
                }
            }
            OutlinedButton(onClick = { onManual(item) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.memory_manual_title)) }
            if (confirmed) {
                OutlinedButton(onClick = { onAction(item, MemoryReviewAction.UNLINK, emptySet()) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.memory_unlink)) }
                Text(stringResource(R.string.memory_locked_decision))
            } else {
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text(stringResource(R.string.memory_correct_date)) },
                    placeholder = { Text("YYYY-MM-DD") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { onReanalyze(item, false, date.takeIf { it.isNotBlank() }) },
                    enabled = !busy && (!dateConflict || date.isNotBlank() && date != item.state.eventDateOverride),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.memory_reanalyze))
                }
                if (item.state.hasMore) {
                    Text(stringResource(R.string.memory_more))
                    OutlinedButton(onClick = { onReanalyze(item, true, null) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.memory_analyze_more)) }
                }
            }
        }
    }
}
@Composable private fun EvidenceContent(source: MemorySource, onPhoto: (MemorySource) -> Unit) {
    val candidate = source.candidate
    val kind = when (candidate.kind) {
        MemoryKind.PHOTO -> R.string.memory_source_photo
        MemoryKind.NOTE -> R.string.memory_source_note
        MemoryKind.NAVIGATION -> if (candidate.timeBasis == MemoryTimeBasis.NAV_ARRIVED) R.string.memory_source_arrival else R.string.memory_source_observation
        MemoryKind.VISIT -> R.string.memory_source_visit
    }
    Text(stringResource(kind), style = MaterialTheme.typography.labelLarge)
    Text(candidate.description.ifBlank { stringResource(R.string.memory_photo_manual) })
    val dateLabel = if (candidate.kind == MemoryKind.NAVIGATION && candidate.occurredAt == null)
        R.string.memory_observation_date else R.string.memory_event_date
    Text(stringResource(dateLabel, candidate.evidenceDate ?: stringResource(R.string.memory_date_unresolved)))
    if (candidate.kind == MemoryKind.PHOTO) TextButton(onClick = { onPhoto(source) }) { Text(stringResource(R.string.memory_open_photo)) }
}
@Composable private fun statusText(status: String): String = stringResource(when (status) {
    "WAITING", "RETRY_WAIT" -> R.string.memory_waiting
    "RUNNING" -> R.string.memory_working
    "SUGGESTED" -> R.string.memory_suggestion
    "CONFIRMED" -> R.string.memory_confirmed
    "NO_MATCH" -> R.string.memory_no_match
    "REJECTED" -> R.string.memory_rejected
    "UNLINKED" -> R.string.memory_unlinked
    "LEGACY" -> R.string.memory_legacy
    else -> R.string.memory_needs_review
})
@Composable private fun resultText(result: MemoryReviewResult): String = stringResource(when (result) {
    MemoryReviewResult.SAVED -> R.string.memory_saved
    MemoryReviewResult.LOCKED -> R.string.memory_locked_decision
    MemoryReviewResult.STALE -> R.string.memory_stale
    MemoryReviewResult.BLOCKED -> R.string.memory_review_locked
    MemoryReviewResult.INVALID -> R.string.memory_invalid
    MemoryReviewResult.FAILED -> R.string.memory_error
})
