package app.memoryvault.share.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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

/** Consumes only the immutable allow-listed snapshot; no private day/entity reference enters this screen. */
@Composable
internal fun SharePreviewScreen(
    draft: ShareDraft, busy: Boolean, notice: Int?, onExport: () -> Unit, onBack: () -> Unit,
    thumbnail: suspend (String) -> ImageBitmap?,
) {
    Column(Modifier.fillMaxWidth().testTag("share-preview"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.share_preview_title), style = MaterialTheme.typography.headlineMedium)
        Text(draft.date)
        draft.items.forEach { item ->
            Text("${item.order}. ${item.placeName}", style = MaterialTheme.typography.titleLarge)
            item.photoUris.forEachIndexed { index, uri ->
                SharePhotoThumbnail(uri, thumbnail, Modifier.testTag("share-preview-photo-${item.order}-$index"))
            }
            item.caption?.let { Text(it) }
        }
        Text(stringResource(R.string.app_name))
        notice?.let { Text(stringResource(it), modifier = Modifier.testTag("share-result")) }
        if (busy) Text(stringResource(R.string.share_exporting))
        Button(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .testTag("share-export-button")) { Text(stringResource(R.string.share_export)) }
        OutlinedButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .testTag("share-back-select")) { Text(stringResource(R.string.share_back_select)) }
    }
}
