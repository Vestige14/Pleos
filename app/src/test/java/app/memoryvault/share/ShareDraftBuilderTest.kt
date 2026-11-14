package app.memoryvault.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ShareDraftBuilderTest {
    // Missing inclusion/date filtering would leak the excluded café into the snapshot.
    @Test fun excludedVisitsAreAbsentAndTimeTiesUseIdBeforeRenumbering() {
        val input = ShareInput(DATE, listOf(
            visit("cafe", "바다 앞 카페", 30),
            visit("b", "방아머리해변", 20),
            visit("a", "대부도 칼국수집", 20),
            visit("other-day", "다른 날", 0, date = "2026-11-09"),
        ))

        val result = requireNotNull(ShareDraftBuilder.build(selection("a", "b", "other-day"), input))

        assertEquals(listOf("대부도 칼국수집", "방아머리해변"), result.items.map { it.placeName })
        assertEquals(listOf(1, 2), result.items.map { it.order })
        assertEquals(DATE, result.date)
    }

    // Omitting any of the accessibility/state/selection/visit filters would share a forbidden photo.
    @Test fun onlySelectedAccessibleCandidateOrConfirmedPhotosFromIncludedVisitAreCopied() {
        val photos = listOf(
            photo("candidate", SharePhotoState.CANDIDATE),
            photo("confirmed", SharePhotoState.CONFIRMED),
            photo("rejected", SharePhotoState.REJECTED),
            photo("unlinked", SharePhotoState.UNLINKED),
            photo("inaccessible", SharePhotoState.CONFIRMED, accessible = false),
            photo("unselected", SharePhotoState.CANDIDATE),
            photo("candidate", SharePhotoState.CANDIDATE),
        )
        val input = ShareInput(DATE, listOf(
            visit("beach", "방아머리해변", 20, photos),
            visit("cafe", "바다 앞 카페", 30, listOf(photo("cafe-photo", SharePhotoState.CONFIRMED))),
        ))
        val selection = ShareSelection(DATE, setOf("beach"),
            setOf("candidate", "confirmed", "rejected", "unlinked", "inaccessible", "cafe-photo"), emptyMap())

        val result = requireNotNull(ShareDraftBuilder.build(selection, input))

        assertEquals(listOf("candidate", "confirmed"), result.items.single().photoUris)
        assertEquals(SharePhotoState.CANDIDATE, photos.first().linkState)
    }

    // Copying descriptions before checking visit inclusion would expose excluded text.
    @Test fun onlyIncludedVisitCaptionIsTrimmedAndBlankCaptionIsAbsent() {
        val input = ShareInput(DATE, listOf(visit("a", "식당", 1), visit("b", "해변", 2)))
        val selected = ShareSelection(DATE, setOf("a", "b"), emptySet(),
            mapOf("a" to " \n\t ", "b" to "  바다에서 한참 놀았어요\n", "cafe" to "개인 설명"))

        val result = requireNotNull(ShareDraftBuilder.build(selected, input))

        assertNull(result.items[0].caption)
        assertEquals("바다에서 한참 놀았어요", result.items[1].caption)
        assertFalse(result.items.any { it.caption?.contains("개인") == true })
    }

    // Retaining source list/selection references would silently change an already previewed result.
    @Test fun laterInputAndSelectionChangesCannotChangeSnapshot() {
        val photos = mutableListOf(photo("p1", SharePhotoState.CANDIDATE))
        val visits = mutableListOf(visit("a", "식당", 1, photos))
        val included = mutableSetOf("a")
        val selectedPhotos = mutableSetOf("p1")
        val captions = mutableMapOf("a" to "공유 설명")
        val result = requireNotNull(ShareDraftBuilder.build(
            ShareSelection(DATE, included, selectedPhotos, captions), ShareInput(DATE, visits)))

        photos.clear()
        visits.clear()
        included.clear()
        selectedPhotos.clear()
        captions["a"] = "변경된 개인 설명"

        assertEquals("식당", result.items.single().placeName)
        assertEquals(listOf("p1"), result.items.single().photoUris)
        assertEquals("공유 설명", result.items.single().caption)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.items as MutableList<ShareItem>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.items.single().photoUris as MutableList<String>).clear()
        }
    }

    // A public constructor retaining a mutable list would bypass builder snapshot protection.
    @Test fun outputConstructorsAlsoDefensivelyCopyNestedLists() {
        val uris = mutableListOf("p1")
        val items = mutableListOf(ShareItem(1, "해변", uris, null))
        val draft = ShareDraft(DATE, items)
        uris += "private-photo"
        items.clear()
        assertEquals(listOf("p1"), draft.items.single().photoUris)
    }

    @Test fun noIncludedVisitsOrUnknownIdsReturnNull() {
        val input = ShareInput(DATE, listOf(visit("a", "식당", 1)))
        assertNull(ShareDraftBuilder.build(selection(), input))
        assertNull(ShareDraftBuilder.build(selection("missing"), input))
    }

    @Test fun selectionFromDifferentDateReturnsNull() {
        val input = ShareInput(DATE, listOf(visit("a", "식당", 1)))
        assertNull(ShareDraftBuilder.build(ShareSelection("2026-11-09", setOf("a"), emptySet(), emptyMap()), input))
    }

    // Duplicated membership must not share a photo twice; invalid/excluded occurrences cannot claim it first.
    @Test fun duplicateUriBelongsOnlyToFirstEligibleIncludedVisitInTimeOrder() {
        val input = ShareInput(DATE, listOf(
            visit("later", "카페", 5, listOf(photo("shared", SharePhotoState.CONFIRMED))),
            visit("excluded", "제외 장소", 1, listOf(photo("shared", SharePhotoState.CONFIRMED))),
            visit("rejected", "식당", 2, listOf(photo("shared", SharePhotoState.REJECTED))),
            visit("inaccessible", "공원", 3, listOf(photo("shared", SharePhotoState.CONFIRMED, false))),
            visit("first-valid", "해변", 4, listOf(photo("shared", SharePhotoState.CANDIDATE))),
        ))
        val selected = ShareSelection(DATE, setOf("later", "rejected", "inaccessible", "first-valid"),
            setOf("shared"), emptyMap())

        val result = requireNotNull(ShareDraftBuilder.build(selected, input))

        assertEquals(listOf(emptyList<String>(), emptyList(), listOf("shared"), emptyList()), result.items.map { it.photoUris })
    }

    private fun selection(vararg ids: String) = ShareSelection(DATE, ids.toSet(), emptySet(), emptyMap())

    private fun visit(id: String, name: String, arrivedAt: Long,
        photos: List<SharePhotoInput> = emptyList(), date: String = DATE) =
        ShareVisitInput(id, date, name, arrivedAt, photos)

    private fun photo(uri: String, state: SharePhotoState, accessible: Boolean = true) =
        SharePhotoInput(uri, state, accessible)

    private companion object { const val DATE = "2026-11-08" }
}
