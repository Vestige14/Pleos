package app.memoryvault.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.memoryvault.R
import app.memoryvault.data.LinkState
import app.memoryvault.data.Photo
import app.memoryvault.platform.PhotoAccess
import app.memoryvault.p1.provenance.ParkedProvenanceBadges
import app.memoryvault.p1.provenance.ProvenancePolicy
import app.memoryvault.ui.components.P2ActionPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
internal fun PhotoSetup(
    state: PhotoReviewState,
    notice: Int?,
    busy: Boolean,
    onPermissions: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.p2_photo_settings), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(when (state.permissions.access) {
                PhotoAccess.FULL -> R.string.photo_access_full
                PhotoAccess.PARTIAL -> R.string.photo_access_partial
                PhotoAccess.DENIED -> R.string.photo_access_denied
            }))
            if (!state.permissions.location) Text(stringResource(R.string.photo_location_missing))
            P2ActionPair(first = { actionModifier ->
                OutlinedButton(onClick = onPermissions, enabled = !busy,
                    modifier = actionModifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.photo_permissions_setup))
                }
            }, second = { actionModifier ->
                OutlinedButton(onClick = onRefresh, enabled = !busy && state.scanStatus != PhotoScanStatus.SCANNING,
                    modifier = actionModifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.photo_rescan))
                }
            })
            val scanNotice = when (state.scanStatus) {
                PhotoScanStatus.SCANNING -> R.string.photo_scanning
                PhotoScanStatus.UPDATED -> R.string.photo_scan_updated
                PhotoScanStatus.FAILED -> R.string.photo_scan_failed
                PhotoScanStatus.STORAGE_UNAVAILABLE -> R.string.storage_unavailable
                PhotoScanStatus.IDLE -> null
            }
            scanNotice?.let { Text(stringResource(it)) }
            notice?.let { Text(stringResource(it), modifier = Modifier.testTag("photo-result")) }
        }
    }
}

@Composable
internal fun PhotoList(
    photos: List<Photo>, enabled: Boolean, onDecision: (String, LinkState) -> Unit,
    ui: MemoryVaultUi, epoch: PhotoThumbnailEpoch?, provenanceVisible: Boolean,
) {
    val visible = photos.filter { it.accessible && it.linkState in setOf(LinkState.CANDIDATE, LinkState.CONFIRMED) }
    if (visible.isEmpty()) {
        Text(stringResource(R.string.photo_empty))
        return
    }
    Text(stringResource(R.string.photo_count, visible.size))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(visible, key = { it.uri }) { photo ->
            Card(Modifier.width(288.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhotoThumbnail(photo.uri, ui, epoch)
                    if (photo.linkState == LinkState.CANDIDATE) {
                        P2ActionPair(first = { actionModifier ->
                            OutlinedButton(onClick = { onDecision(photo.uri, LinkState.CONFIRMED) }, enabled = enabled,
                                modifier = actionModifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.photo_confirm)) }
                        }, second = { actionModifier ->
                            OutlinedButton(onClick = { onDecision(photo.uri, LinkState.REJECTED) }, enabled = enabled,
                                modifier = actionModifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.photo_reject)) }
                        })
                    } else {
                        OutlinedButton(onClick = { onDecision(photo.uri, LinkState.REJECTED) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                            Text(stringResource(R.string.photo_reject))
                        }
                    }
                }
            }
        }
    }
}

private sealed interface Thumbnail {
    data object Loading : Thumbnail
    data class Ready(val image: ImageBitmap) : Thumbnail
    data object Failed : Thumbnail
}

@Composable
internal fun PhotoThumbnail(uri: String, ui: MemoryVaultUi, epoch: PhotoThumbnailEpoch?, descriptionResource: Int = R.string.photo_thumbnail_description) {
    val thumbnail by produceState<Thumbnail>(Thumbnail.Loading, uri, epoch) {
        if (epoch == null) {
            value = Thumbnail.Loading
            return@produceState
        }
        value = try {
            currentCoroutineContext().ensureActive()
            val image = ui.loadPhotoThumbnail(uri, epoch)
            currentCoroutineContext().ensureActive()
            if (!ui.thumbnailAllowed(epoch)) Thumbnail.Loading
            else if (image == null) Thumbnail.Failed else Thumbnail.Ready(image)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (ui.thumbnailAllowed(epoch)) Thumbnail.Failed else Thumbnail.Loading
        }
    }
    Box(Modifier.fillMaxWidth().height(160.dp).testTag("photo-thumbnail"), contentAlignment = Alignment.Center) {
        when (val image = thumbnail) {
            Thumbnail.Loading -> Text(stringResource(R.string.photo_thumbnail_loading))
            Thumbnail.Failed -> Text(stringResource(R.string.photo_thumbnail_unavailable))
            is Thumbnail.Ready -> Image(image.image, contentDescription = stringResource(descriptionResource),
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(160.dp))
        }
    }
}

internal fun PhotoReviewResult.textResource() = when (this) {
    PhotoReviewResult.SAVED -> R.string.photo_decision_saved
    PhotoReviewResult.BLOCKED -> R.string.photo_park_required
    PhotoReviewResult.NOT_EDITABLE -> R.string.photo_not_editable
    PhotoReviewResult.STORAGE_UNAVAILABLE -> R.string.storage_unavailable
    PhotoReviewResult.FAILED -> R.string.photo_decision_failed
}
