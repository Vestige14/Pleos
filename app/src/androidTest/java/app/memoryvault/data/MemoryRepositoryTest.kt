package app.memoryvault.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: MemoryRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
        repository = MemoryRepository(db, PhotoSource { emptyList() })
    }

    @After fun tearDown() = db.close()

    @Test fun observeDay_returnsCardsInTimeOrder() = runBlocking {
        repository.seedIfEmpty(seed.copy(visits = day.reversed()))
        val draft = repository.observeDay("2026-11-08").first()
        assertEquals("2026-11-08", draft.date)
        assertEquals(listOf("v1", "v2", "v3"), draft.cards.map { it.visit.id })
        assertTrue(draft.cards.all { it.photos.isEmpty() && it.notes.isEmpty() })
        assertTrue(draft.pendingNotes.isEmpty())
    }

    // Accidentally using latest/capture visit would put this typed note on v3.
    @Test fun typedNote_usesChosenVisitAndPreservesText() = runBlocking {
        repository.seedIfEmpty(seed)
        val raw = "  칼국수 국물이 좋았어\n"
        assertTrue(repository.addNote("v1", raw, NoteKind.TYPED, t("15:30")))
        val draft = repository.observeDay("2026-11-08").first()
        val note = draft.cards[0].notes.single()
        assertEquals(raw, note.text)
        assertEquals("v1", note.visitId)
        assertEquals("2026-11-08", note.sourceDate)
        assertEquals(t("15:30"), note.capturedAt)
        assertEquals(NoteKind.TYPED, note.kind)
        assertTrue(draft.cards.drop(1).all { it.notes.isEmpty() })
    }

    @Test fun typedNote_canBeEditedWithoutRewritingSourceTime() = runBlocking {
        repository.seedIfEmpty(seed)
        assertTrue(repository.addNote("v2", "처음 쓴 메모", NoteKind.TYPED, t("14:58")))
        val original = db.noteDao().getAll().single()
        assertTrue(repository.updateTypedNote(original.id, "  새 메모\n"))
        val edited = requireNotNull(db.noteDao().get(original.id))
        assertEquals("  새 메모\n", edited.text)
        assertEquals(2, edited.revision)
        assertEquals(original.createdAt, edited.createdAt)
        assertEquals(original.capturedAt, edited.capturedAt)
        assertEquals(original.sourceDate, edited.sourceDate)
        assertEquals("v2", edited.visitId)
        assertFalse(repository.updateTypedNote(original.id, "  \n"))
        assertEquals(edited, db.noteDao().get(original.id))
    }

    @Test fun typedNote_blankSavesNothing() = runBlocking {
        repository.seedIfEmpty(seed)
        assertFalse(repository.addNote("v2", "  \n", NoteKind.TYPED, t("14:58")))
        assertTrue(db.noteDao().getAll().isEmpty())
    }

    @Test fun voiceCannotBypassSessionTransactionThroughAddNote() = runBlocking {
        repository.seedIfEmpty(seed)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.addNote("v2", "음성", NoteKind.VOICE, t("14:58")) }
        }
        assertTrue(db.noteDao().getAll().isEmpty())
    }

    @Test fun observeDay_showsOnlyCandidateAndConfirmedPhotos() = runBlocking {
        repository.seedIfEmpty(seed)
        db.photoDao().upsert(listOf(
            Photo("content://candidate", t("13:45"), null, null, "v2", LinkState.CANDIDATE),
            Photo("content://confirmed", t("14:00"), null, null, "v2", LinkState.CONFIRMED),
            Photo("content://rejected", t("14:12"), null, null, "v2", LinkState.REJECTED),
            Photo("content://unlinked", null, null, null, null, LinkState.UNLINKED),
        ))
        val draft = repository.observeDay("2026-11-08").first()
        assertEquals(listOf("v1", "v2", "v3"), draft.cards.map { it.visit.id })
        assertEquals(listOf("content://candidate", "content://confirmed"), draft.cards[1].photos.map { it.uri })
    }
}
