package app.memoryvault.share

import app.memoryvault.data.DayDraft
import app.memoryvault.data.LinkState
import app.memoryvault.data.Note
import app.memoryvault.data.NoteKind
import app.memoryvault.data.Photo
import app.memoryvault.data.Visit
import app.memoryvault.data.VisitCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareFlowControllerTest {
    // Missing eligibility/date filters would offer rejected or inaccessible private media by default.
    @Test fun firstOpenSelectsAllVisitsAndOnlyEligiblePhotosForThatDay() = runTest {
        val store = Store()
        val controller = ShareFlowController(store)
        controller.open(input())
        val selected = requireNotNull(controller.state.value.selection)
        assertEquals(setOf("restaurant", "beach", "cafe"), selected.includedVisitIds)
        assertEquals(setOf("p1", "p2", "p3", "p5"), selected.selectedPhotoUris)
        assertEquals(listOf("restaurant", "beach", "cafe"), controller.state.value.input!!.visits.map { it.id })
        assertEquals(selected, store.rows[DATE])
        assertTrue(selected.captions.isEmpty())
    }

    // Re-defaulting after rescan or Activity recreation would silently reselect the café/p3.
    @Test fun excludedVisitAndPhotoStayExcludedAcrossRescanAndNewController() = runTest {
        val store = Store()
        val controller = ShareFlowController(store)
        controller.open(input())
        controller.setVisit("cafe", false)
        controller.setPhoto("p3", false)
        controller.setCaption("beach", "  바다에서 한참 놀았어요 \n")
        controller.setCaption("restaurant", " \n ")
        val oldPreview = requireNotNull(controller.preview())
        controller.open(input(extraPhoto = true))
        val reopened = ShareFlowController(store)
        reopened.open(input(extraPhoto = true))
        val draft = requireNotNull(reopened.preview())
        assertEquals(listOf("식당", "해변"), draft.items.map { it.placeName })
        assertEquals(listOf("p1", "p2"), draft.items.flatMap { it.photoUris })
        assertNull(draft.items[0].caption)
        assertEquals("바다에서 한참 놀았어요", draft.items[1].caption)
        controller.setVisit("restaurant", false)
        controller.setCaption("beach", "나중 설명")
        assertEquals(listOf("식당", "해변"), oldPreview.items.map { it.placeName })
        assertEquals("바다에서 한참 놀았어요", oldPreview.items[1].caption)
    }

    // A date switch must load that day's row, not leak another day's choices or re-default old choices.
    @Test fun switchingDatesAndEmptyVisitsRestoreTheirOwnSelection() = runTest {
        val controller = ShareFlowController(Store())
        controller.open(input())
        controller.setVisit("cafe", false)
        controller.open(ShareInput("2026-11-09", emptyList()))
        assertNull(controller.preview())
        assertEquals("2026-11-09", controller.state.value.selection!!.date)
        controller.open(input())
        assertFalse(controller.state.value.selection!!.includedVisitIds.contains("cafe"))
        for (id in listOf("restaurant", "beach")) controller.setVisit(id, false)
        assertNull(controller.preview())
    }

    // Allowing an older slow write to commit last would resurrect an exclusion after reentry.
    @Test fun rapidChangesSerializeWritesAndPreserveTheLatestSelection() = runTest {
        val store = Store()
        val controller = ShareFlowController(store)
        controller.open(input())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeSave = { if ("cafe" !in it.includedVisitIds && "p3" in it.selectedPhotoUris) {
            entered.complete(Unit); release.await()
        } }
        val first = launch { controller.setVisit("cafe", false) }
        runCurrent()
        assertTrue("Accepted edit must reach persistence", entered.isCompleted)
        entered.await()
        val second = launch { controller.setPhoto("p3", false) }
        runCurrent()
        assertNull("Uncommitted selections cannot be previewed", controller.preview())
        release.complete(Unit)
        first.join(); second.join()
        assertEquals(setOf("restaurant", "beach"), store.rows[DATE]!!.includedVisitIds)
        assertEquals(setOf("p1", "p2", "p5"), store.rows[DATE]!!.selectedPhotoUris)
    }

    // Cancellation on Activity destruction must not abort a change already accepted into visible state.
    @Test fun acceptedSaveFinishesWhenCallerIsCancelled() = runTest {
        val store = Store()
        val controller = ShareFlowController(store)
        controller.open(input())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeSave = { entered.complete(Unit); release.await() }
        val edit = launch { controller.setVisit("cafe", false) }
        runCurrent(); assertTrue("Accepted edit must reach persistence", entered.isCompleted)
        entered.await(); edit.cancel(); release.complete(Unit); edit.join()
        assertFalse(store.rows[DATE]!!.includedVisitIds.contains("cafe"))
    }

    // A failed DB write must be visible and cannot be misrepresented as a durable preview.
    @Test fun storageFailureBlocksPreviewUntilExplicitRetry() = runTest {
        val store = Store()
        val controller = ShareFlowController(store)
        controller.open(input())
        store.beforeSave = { error("private storage payload") }
        controller.setVisit("cafe", false)
        assertTrue(controller.state.value.persistenceFailed)
        assertNull(controller.preview())
        assertFalse(controller.state.value.toString().contains("private storage payload"))
        store.beforeSave = {}
        controller.retrySave()
        assertFalse(controller.state.value.persistenceFailed)
        assertEquals(listOf("식당", "해변"), controller.preview()!!.items.map { it.placeName })
    }

    // Holding incoming mutable lists/sets would change subsequent previews without an explicit edit.
    @Test fun controllerCopiesIncomingInputAndStoredCollections() = runTest {
        val photos = mutableListOf(SharePhotoInput("p1", SharePhotoState.CANDIDATE, true))
        val visits = mutableListOf(ShareVisitInput("restaurant", DATE, "식당", 1, photos))
        val ids = mutableSetOf("restaurant")
        val uris = mutableSetOf("p1")
        val captions = mutableMapOf("restaurant" to "공유 설명")
        val store = Store().apply { rows[DATE] = ShareSelection(DATE, ids, uris, captions) }
        val controller = ShareFlowController(store)
        controller.open(ShareInput(DATE, visits))
        photos.clear(); visits.clear(); ids.clear(); uris.clear(); captions.clear()
        val preview = requireNotNull(controller.preview())
        assertEquals(listOf("p1"), preview.items.single().photoUris)
        assertEquals("공유 설명", preview.items.single().caption)
    }

    // Mapper must whitelist photo/visit fields and never copy Note, address, or coordinates.
    @Test fun dayMapperOmitsPrivateNotesAndFiltersRejectedAndInaccessiblePhotos() {
        val visit = Visit("restaurant", DATE, "식당", 37.2830, 126.5810, 1, 2)
        val day = DayDraft(DATE, listOf(VisitCard(visit, listOf(
            Photo("p1", 1, 37.2830, 126.5810, "restaurant", LinkState.CANDIDATE),
            Photo("p6", 1, null, null, "restaurant", LinkState.REJECTED),
            Photo("private", 1, null, null, "restaurant", LinkState.CONFIRMED, false),
        ), listOf(Note(visitId = "restaurant", text = "개인 음성 원문", kind = NoteKind.VOICE,
            createdAt = 1, sourceDate = DATE)))))
        val mapped = ShareInputMapper.from(day)
        assertEquals(listOf("p1"), mapped.visits.single().photos.map { it.uri })
        assertEquals("식당", mapped.visits.single().placeName)
        assertFalse(mapped.toString().contains("개인 음성 원문"))
        assertFalse(mapped.toString().contains("126.581"))
    }

    // A transient load failure must not overwrite an existing row with defaults on retry.
    @Test fun failedLoadCanBeRetriedWithoutErasingStoredExclusions() = runTest {
        val store = Store().apply {
            rows[DATE] = ShareSelection(DATE, setOf("beach"), setOf("p2"), emptyMap())
            beforeLoad = { error("private database failure") }
        }
        val controller = ShareFlowController(store)
        controller.open(input())
        assertTrue(controller.state.value.persistenceFailed)
        assertNull(controller.preview())
        assertEquals(setOf("beach"), store.rows[DATE]!!.includedVisitIds)
        store.beforeLoad = {}
        controller.retrySave()
        assertEquals(listOf("해변"), requireNotNull(controller.preview()).items.map { it.placeName })
    }

    private class Store : ShareSelectionStore {
        val rows = mutableMapOf<String, ShareSelection>()
        var beforeLoad: suspend () -> Unit = {}
        var beforeSave: suspend (ShareSelection) -> Unit = {}
        override suspend fun load(date: String): ShareSelection? { beforeLoad(); return rows[date] }
        override suspend fun save(selection: ShareSelection) { beforeSave(selection); rows[selection.date] = selection }
    }

    private fun input(extraPhoto: Boolean = false): ShareInput {
        fun photo(uri: String, state: SharePhotoState = SharePhotoState.CANDIDATE, access: Boolean = true) =
            SharePhotoInput(uri, state, access)
        return ShareInput(DATE, listOf(
            ShareVisitInput("cafe", DATE, "카페", 3, listOf(photo("p5"))),
            ShareVisitInput("beach", DATE, "해변", 2, listOf(photo("p2", SharePhotoState.CONFIRMED),
                photo("p3"), photo("p6", SharePhotoState.REJECTED), photo("hidden", access = false)) +
                if (extraPhoto) listOf(photo("new")) else emptyList()),
            ShareVisitInput("restaurant", DATE, "식당", 1, listOf(photo("p1"))),
            ShareVisitInput("other", "2026-11-09", "다른 날짜", 0, listOf(photo("other-photo"))),
        ))
    }
    private companion object { const val DATE = "2026-11-08" }
}
