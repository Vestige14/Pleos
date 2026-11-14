package app.memoryvault.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoSyncTest {
    private lateinit var db: AppDatabase
    private var scan = PhotoScan(emptyList())
    private lateinit var repository: MemoryRepository
    private val meta = PhotoMeta("content://photo", t("13:45"), 37.2846, 126.5779)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
        repository = MemoryRepository(db, PhotoSource { scan })
        repository.seedIfEmpty(seed)
    }
    @After fun tearDown() = db.close()

    // Losing the GPS guard would attach redacted media, while dropping the row hides readable photos.
    @Test fun readablePhotosWithAndWithoutMetadataBecomeCandidateAndUnlinked() = runBlocking {
        scan = PhotoScan(listOf(meta, PhotoMeta("content://no-gps", t("14:10"), null, null),
            PhotoMeta("content://no-exif", null, null, null)))
        repository.syncPhotos("2026-11-08")
        assertEquals(Photo(meta.uri, meta.takenAt, meta.lat, meta.lng, "v2", LinkState.CANDIDATE), db.photoDao().get(meta.uri))
        assertEquals(LinkState.UNLINKED, db.photoDao().get("content://no-gps")?.linkState)
        assertTrue(requireNotNull(db.photoDao().get("content://no-exif")).accessible)
        val draft = repository.observeDay("2026-11-08").first()
        assertEquals(listOf(meta.uri), draft.cards[1].photos.map { it.uri })
        assertEquals(setOf("content://no-gps", "content://no-exif"), draft.unlinkedPhotos.map { it.uri }.toSet())
    }

    @Test fun locationRegrantReevaluatesMachineDecisions() = runBlocking {
        scan = PhotoScan(listOf(meta.copy(lat = null, lng = null)))
        repository.syncPhotos("2026-11-08")
        assertEquals(LinkState.UNLINKED, db.photoDao().get(meta.uri)?.linkState)
        scan = PhotoScan(listOf(meta))
        repository.syncPhotos("2026-11-08")
        assertEquals("v2", db.photoDao().get(meta.uri)?.visitId)
        assertEquals(LinkState.CANDIDATE, db.photoDao().get(meta.uri)?.linkState)
        scan = PhotoScan(listOf(meta.copy(lat = 0.0, lng = 0.0)))
        repository.syncPhotos("2026-11-08")
        assertEquals(LinkState.UNLINKED, db.photoDao().get(meta.uri)?.linkState)
        assertNull(db.photoDao().get(meta.uri)?.visitId)
    }

    @Test fun accessLossDeletionAndRescanNeverOverwriteUserDecisionOrVisit() = runBlocking {
        for (decision in listOf(LinkState.CONFIRMED, LinkState.REJECTED)) {
            scan = PhotoScan(listOf(meta))
            db.photoDao().upsert(listOf(Photo(meta.uri, meta.takenAt, meta.lat, meta.lng, "v2", decision)))
            scan = PhotoScan(emptyList(), setOf(meta.uri))
            repository.syncPhotos("2026-11-08")
            val lost = requireNotNull(db.photoDao().get(meta.uri))
            assertFalse(lost.accessible)
            assertEquals(decision, lost.linkState)
            assertEquals("v2", lost.visitId)
            assertTrue(repository.observeDay("2026-11-08").first().cards.all { it.photos.isEmpty() })
            scan = PhotoScan(listOf(meta.copy(lat = 0.0, lng = 0.0)))
            repository.syncPhotos("2026-11-08")
            val restored = requireNotNull(db.photoDao().get(meta.uri))
            assertTrue(restored.accessible)
            assertEquals(decision, restored.linkState)
            assertEquals("v2", restored.visitId)
        }
    }

    @Test fun emptyScanDoesNotDeclareKnownPhotosInaccessibleAndKnownUrisAreSupplied() = runBlocking {
        db.photoDao().upsert(listOf(Photo(meta.uri, meta.takenAt, meta.lat, meta.lng, "v2", LinkState.CONFIRMED)))
        var received: Set<String>? = null
        val source = PhotoSource { known -> received = known; PhotoScan(emptyList()) }
        MemoryRepository(db, source).syncPhotos("2026-11-08")
        assertEquals(setOf(meta.uri), received)
        assertTrue(requireNotNull(db.photoDao().get(meta.uri)).accessible)
    }

    @Test fun failedOrCancelledScanKeepsEveryExistingRow() = runBlocking {
        val before = Photo(meta.uri, meta.takenAt, meta.lat, meta.lng, "v2", LinkState.CONFIRMED)
        db.photoDao().upsert(listOf(before))
        for (cancel in listOf(false, true)) {
            val source = PhotoSource {
                if (cancel) throw CancellationException("cancel") else error("temporary library failure")
            }
            try { MemoryRepository(db, source).syncPhotos("2026-11-08"); fail("Failure must propagate") }
            catch (_: CancellationException) { assertTrue(cancel) }
            catch (_: IllegalStateException) { assertFalse(cancel) }
            assertEquals(before, db.photoDao().get(meta.uri))
        }
    }

    @Test fun confirmationWhileScanIsSuspendedWinsOverTheScannedMetadata() = runBlocking {
        db.photoDao().upsert(listOf(Photo(meta.uri, meta.takenAt, meta.lat, meta.lng, "v2", LinkState.CANDIDATE)))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scanningRepository = MemoryRepository(db, PhotoSource {
            started.complete(Unit); release.await()
            PhotoScan(listOf(meta.copy(lat = 0.0, lng = 0.0)))
        })
        val job = async { scanningRepository.syncPhotos("2026-11-08") }
        job.invokeOnCompletion {
            if (!started.isCompleted) started.completeExceptionally(AssertionError("The photo source was not called"))
        }
        started.await()
        try { assertTrue(repository.setPhotoState(meta.uri, LinkState.CONFIRMED)) }
        finally { release.complete(Unit) }
        job.await()
        assertEquals(LinkState.CONFIRMED, db.photoDao().get(meta.uri)?.linkState)
        assertEquals("v2", db.photoDao().get(meta.uri)?.visitId)
    }

    @Test fun wholeLibraryScanMatchesOtherDatesWithoutChangingTheRequestedDay() = runBlocking {
        db.visitDao().insertAll(listOf(day[1].copy(id = "tomorrow", date = "2026-11-09",
            arrivedAt = t("13:30") + 86_400_000L, leftAt = t("14:50") + 86_400_000L)))
        scan = PhotoScan(listOf(meta.copy(takenAt = t("13:45") + 86_400_000L)))
        repository.syncPhotos("2026-11-08")
        assertEquals("tomorrow", db.photoDao().get(meta.uri)?.visitId)
        assertTrue(repository.observeDay("2026-11-08").first().cards.all { it.photos.isEmpty() })
    }

    @Test fun staleSafetyGuardCannotApplyScanOrDecision() = runBlocking {
        val before = Photo(meta.uri, meta.takenAt, meta.lat, meta.lng, "v2", LinkState.CANDIDATE)
        db.photoDao().upsert(listOf(before))
        var allowed = true
        val scanningRepository = MemoryRepository(db, PhotoSource {
            allowed = false; PhotoScan(emptyList(), setOf(meta.uri))
        })
        try { scanningRepository.syncPhotos("2026-11-08") { allowed }; fail("A stale scan must abort") }
        catch (_: PhotoOperationBlocked) { }
        assertEquals(before, db.photoDao().get(meta.uri))
        assertFalse(repository.setPhotoState(meta.uri, LinkState.REJECTED) { false })
        assertEquals(before, db.photoDao().get(meta.uri))
    }

    @Test fun onlyLinkedAccessiblePhotosAcceptExplicitUserDecisions() = runBlocking {
        scan = PhotoScan(listOf(meta, PhotoMeta("unlinked", null, null, null)))
        repository.syncPhotos("2026-11-08")
        assertTrue(repository.setPhotoState(meta.uri, LinkState.CONFIRMED))
        assertTrue(repository.setPhotoState(meta.uri, LinkState.REJECTED))
        assertEquals("v2", db.photoDao().get(meta.uri)?.visitId)
        assertFalse(repository.setPhotoState("unlinked", LinkState.CONFIRMED))
        assertFalse(repository.setPhotoState("absent", LinkState.REJECTED))
        for (machine in listOf(LinkState.CANDIDATE, LinkState.UNLINKED)) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.setPhotoState(meta.uri, machine) }
            }
        }
    }

    @Test fun closeAndReopenRestoresConfirmedRejectedAndInaccessibleRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mv-photo-test-${UUID.randomUUID()}.db"
        var fileDb = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            fileDb.visitDao().insertAll(day)
            val expected = listOf(
                Photo("confirmed", t("13:45"), null, null, "v2", LinkState.CONFIRMED),
                Photo("rejected", t("14:10"), null, null, "v2", LinkState.REJECTED, accessible = false),
            )
            fileDb.photoDao().upsert(expected)
            fileDb.close()
            fileDb = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(expected, fileDb.photoDao().getAll())
            val restored = MemoryRepository(fileDb).observeDay("2026-11-08").first()
            assertEquals(listOf("confirmed"), restored.cards[1].photos.map { it.uri })
            assertEquals(1, restored.inaccessiblePhotoCount)
        } finally {
            fileDb.close()
            check(name.startsWith("mv-photo-test-"))
            context.deleteDatabase(name)
        }
    }
}
