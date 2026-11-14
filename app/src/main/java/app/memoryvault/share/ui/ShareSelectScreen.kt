package app.memoryvault.share.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.memoryvault.R
import app.memoryvault.share.ShareFlowState
import app.memoryvault.share.SharePhotoState

@Composable
internal fun ShareSelectScreen(
    state: ShareFlowState,
    canPreview: Boolean,
    onVisit: (String, Boolean) -> Unit,
    onPhoto: (String, Boolean) -> Unit,
    onCaption: (String, String) -> Unit,
    onPreview: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    thumbnail: suspend (String) -> ImageBitmap?,
) {
    val selected = state.selection
    val enabled = selected != null && !state.persistenceFailed
    Column(Modifier.fillMaxWidth().testTag("share-select"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.share_title), style = MaterialTheme.typography.headlineMedium)
        Text(state.input?.date.orEmpty())
        Text(stringResource(R.string.share_privacy))
        Text(stringResource(R.string.share_photo_pixels))
        if (state.persistenceFailed) {
            Text(stringResource(R.string.share_selection_failed))
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.share_retry)) }
        }
        state.input?.visits.orEmpty().forEach { visit ->
            val included = visit.id in selected?.includedVisitIds.orEmpty()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(visit.placeName, style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("share-visit-${visit.id}")
                        .toggleable(included, enabled = enabled, role = Role.Switch, onValueChange = { onVisit(visit.id, it) }),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.share_include_visit))
                        Switch(checked = included, onCheckedChange = null, enabled = enabled)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        itemsIndexed(visit.photos, key = { _, photo -> photo.uri }) { index, photo ->
                            val checked = photo.uri in selected?.selectedPhotoUris.orEmpty()
                            Column(Modifier.width(288.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SharePhotoThumbnail(photo.uri, thumbnail)
                                Text(stringResource(if (photo.linkState == SharePhotoState.CANDIDATE)
                                    R.string.photo_candidate else R.string.photo_confirmed))
                                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("share-photo-${visit.id}-$index")
                                    .toggleable(checked, enabled = enabled && included, role = Role.Checkbox,
                                        onValueChange = { onPhoto(photo.uri, it) }), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled && included)
                                    Text(stringResource(R.string.share_photo_choice, index + 1))
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = selected?.captions?.get(visit.id).orEmpty(),
                        onValueChange = { onCaption(visit.id, it) }, enabled = enabled && included,
                        label = { Text(stringResource(R.string.share_caption)) },
                        supportingText = { Text(stringResource(R.string.share_caption_help)) },
                        modifier = Modifier.fillMaxWidth().testTag("share-caption-${visit.id}"))
                }
            }
        }
        if (state.saving) Text(stringResource(R.string.share_saving_selection))
        if (!canPreview && !state.persistenceFailed && !state.saving) Text(stringResource(R.string.share_no_visits))
        Button(onClick = onPreview, enabled = canPreview, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .testTag("share-preview-button")) { Text(stringResource(R.string.share_preview)) }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Text(stringResource(R.string.share_back_day))
        }
    }
}
