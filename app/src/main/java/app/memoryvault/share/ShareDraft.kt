package app.memoryvault.share

import java.util.Collections

/** Adapter input only; never pass Room entities or private note text to the renderer. */
data class ShareInput(val date: String, val visits: List<ShareVisitInput>)

data class ShareVisitInput(
    val id: String,
    val date: String,
    val placeName: String,
    val arrivedAt: Long,
    val photos: List<SharePhotoInput>,
)

enum class SharePhotoState { CANDIDATE, CONFIRMED, REJECTED, UNLINKED }

data class SharePhotoInput(val uri: String, val linkState: SharePhotoState, val accessible: Boolean)

data class ShareSelection(
    val date: String,
    val includedVisitIds: Set<String>,
    val selectedPhotoUris: Set<String>,
    val captions: Map<String, String>,
)

class ShareItem(val order: Int, val placeName: String, photoUris: List<String>, val caption: String?) {
    val photoUris: List<String> = Collections.unmodifiableList(ArrayList(photoUris))
}

/** Independent, deeply immutable snapshot: these are the only values that may be rendered. */
class ShareDraft(val date: String, items: List<ShareItem>) {
    val items: List<ShareItem> = Collections.unmodifiableList(ArrayList(items))
}

object ShareDraftBuilder {
    fun build(selection: ShareSelection, input: ShareInput): ShareDraft? {
        if (selection.date != input.date) return null
        val usedUris = mutableSetOf<String>()
        val items = input.visits
            .filter { it.date == selection.date && it.id in selection.includedVisitIds }
            .sortedWith(compareBy<ShareVisitInput> { it.arrivedAt }.thenBy { it.id })
            .mapIndexed { index, visit ->
                val uris = visit.photos.filter { photo ->
                    photo.accessible && photo.uri in selection.selectedPhotoUris &&
                        (photo.linkState == SharePhotoState.CANDIDATE ||
                            photo.linkState == SharePhotoState.CONFIRMED)
                }.map { it.uri }.filter { usedUris.add(it) }
                ShareItem(index + 1, visit.placeName, uris,
                    selection.captions[visit.id]?.trim()?.takeIf { it.isNotEmpty() })
            }
        return items.takeIf { it.isNotEmpty() }?.let { ShareDraft(selection.date, it) }
    }
}
