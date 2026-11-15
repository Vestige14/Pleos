package app.memoryvault.share.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.memoryvault.R
import app.memoryvault.share.ShareDraft
import app.memoryvault.ui.components.P2ActionPair

/** Consumes only the immutable allow-listed snapshot; no private day/entity reference enters this screen. */
@Composable
internal fun SharePreviewScreen(
    draft: ShareDraft, busy: Boolean, notice: Int?, onExport: () -> Unit, onBack: () -> Unit,
    thumbnail: suspend (String) -> ImageBitmap?,
    onHome: () -> Unit,
    onSaveOnly: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().testTag("share-preview"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.share_preview_title), style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = onHome, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Text(stringResource(R.string.p2_back_home))
        }
        Text(stringResource(R.string.p2_share_steps))
        Text(draft.date, style = MaterialTheme.typography.titleMedium)
        draft.items.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${item.order}. ${item.placeName}", style = MaterialTheme.typography.titleLarge)
                    item.photoUris.forEachIndexed { index, uri ->
                        SharePhotoThumbnail(uri, thumbnail,
                            Modifier.widthIn(max = 480.dp).testTag("share-preview-photo-${item.order}-$index"))
                    }
                    item.caption?.let { Text(it) }
                }
            }
        }
        Text(stringResource(R.string.app_name))
        notice?.let { Text(stringResource(it), modifier = Modifier.testTag("share-result")) }
        if (busy) Text(stringResource(R.string.share_exporting))
        P2ActionPair(first = { actionModifier ->
            OutlinedButton(onClick = onSaveOnly, enabled = !busy, modifier = actionModifier.heightIn(min = 64.dp)
                .testTag("share-save-only-button")) { Text(stringResource(R.string.p2_share_save_only)) }
        }, second = { actionModifier ->
            Button(onClick = onExport, enabled = !busy, modifier = actionModifier.heightIn(min = 64.dp)
                .testTag("share-export-button")) { Text(stringResource(R.string.p2_share_send)) }
        })
        Text(stringResource(R.string.p2_share_send_help))
        OutlinedButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .testTag("share-back-select")) { Text(stringResource(R.string.share_back_select)) }
    }
}
