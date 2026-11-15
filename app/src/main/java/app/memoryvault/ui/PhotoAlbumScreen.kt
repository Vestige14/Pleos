package app.memoryvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.memoryvault.R
import app.memoryvault.data.LinkState
import app.memoryvault.platform.PhotoAccess
import app.memoryvault.ui.components.P2ActionPair
import app.memoryvault.ui.components.P2ScreenColumn
import app.memoryvault.ui.components.P2SectionHeading

/** Only the supplied day is shown; this screen does not query or scan the device gallery. */
@Composable
internal fun PhotoAlbumScreen(
    day: DayDraftState,
    photos: PhotoReviewState,
    ui: MemoryVaultUi,
    contentAllowed: Boolean,
    busy: Boolean,
    notice: Int?,
    onDecision: (String, LinkState) -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    P2ScreenColumn(modifier.testTag("p2-photo-album-page")) {
        Text(stringResource(R.string.p2_photo_album), style = MaterialTheme.typography.headlineLarge)
        P2ActionPair(first = { actionModifier ->
            OutlinedButton(onClick = onHome,
                modifier = actionModifier.heightIn(min = 64.dp).testTag("p2-photo-album-home")) {
                Text(stringResource(R.string.p2_back_home))
            }
        }, second = { actionModifier ->
            OutlinedButton(onClick = onSettings,
                modifier = actionModifier.heightIn(min = 64.dp).testTag("p2-photo-album-settings")) {
                Text(stringResource(R.string.p2_settings))
            }
        })
        if (photos.permissions.access == PhotoAccess.DENIED) {
            Text(stringResource(R.string.photo_access_denied))
            return@P2ScreenColumn
        }
        if (!contentAllowed) {
            Text(stringResource(when (photos.scanStatus) {
                PhotoScanStatus.STORAGE_UNAVAILABLE -> R.string.storage_unavailable
                PhotoScanStatus.FAILED -> R.string.photo_scan_failed
                else -> R.string.p2_photo_waiting
            }))
            return@P2ScreenColumn
        }
        when (day) {
            DayDraftState.Loading -> Text(stringResource(R.string.storage_loading))
            DayDraftState.Failed -> Text(stringResource(R.string.storage_unavailable))
            is DayDraftState.Ready -> {
                val draft = day.draft
                RecordDatePicker(ui, draft.date)
                notice?.let { Text(stringResource(it), Modifier.testTag("photo-result")) }
                val scanNotice = when (photos.scanStatus) {
                    PhotoScanStatus.SCANNING -> R.string.photo_scanning
                    PhotoScanStatus.FAILED -> R.string.photo_scan_failed
                    PhotoScanStatus.STORAGE_UNAVAILABLE -> R.string.storage_unavailable
                    else -> null
                }
                scanNotice?.let { Text(stringResource(it)) }
                P2SectionHeading(stringResource(R.string.p2_photo_unlinked))
                val unlinked = draft.unlinkedPhotos.filter { it.accessible && it.linkState == LinkState.UNLINKED }
                    .distinctBy { it.uri }
                if (unlinked.isEmpty()) {
                    if (scanNotice == null) Text(stringResource(R.string.p2_photo_unlinked_empty))
                } else LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(unlinked, key = { it.uri }) { photo ->
                        Card(Modifier.width(288.dp).testTag("p2-unlinked-photo")) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PhotoThumbnail(photo.uri, ui, photos.thumbnailEpoch, R.string.p2_photo_unlinked)
                                Text(stringResource(R.string.p2_photo_unlinked))
                            }
                        }
                    }
                }
                P2SectionHeading(stringResource(R.string.p2_photo_linked))
                val linkedCards = draft.cards.filter { card -> card.photos.any {
                    it.accessible && it.linkState in setOf(LinkState.CANDIDATE, LinkState.CONFIRMED)
                } }
                if (linkedCards.isEmpty() && scanNotice == null) Text(stringResource(R.string.photo_empty))
                linkedCards.forEach { card ->
                    Text(card.visit.placeName, style = MaterialTheme.typography.titleLarge)
                    PhotoList(card.photos, !busy, onDecision, ui, photos.thumbnailEpoch, provenanceVisible = true)
                }
            }
        }
    }
}
