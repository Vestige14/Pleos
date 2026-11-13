package app.memoryvault.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.domain.LinkOrigin
import app.memoryvault.domain.NoteLinkStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotePresentationTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: MemoryRepository

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
        repository = MemoryRepository(db)
        repository.seedIfEmpty(seed)
    }

    @After fun tearDown() = db.close()

    // Multiple evidence spans may describe one note/visit, but must not duplicate the note.
    @Test fun suggestions_preserveStatusAndShowOneNotePerVisitWithoutAssigningNoteVisitId() = runBlocking {
        val id = repository.saveVoiceMemo("multi-link", "식당은 좋았어. 카페는 시끄러웠어", t("15:30"))
        db.noteLinkDao().insert(NoteVisitLink(id, "v1", 0, 2, LinkOrigin.AI, NoteLinkStatus.SUGGESTED, "request-1"))
        db.noteLinkDao().insert(NoteVisitLink(id, "v1", 4, 7, LinkOrigin.AI, NoteLinkStatus.SUGGESTED, "request-1"))
        db.noteLinkDao().insert(NoteVisitLink(id, "v3", 9, 11, LinkOrigin.AI, NoteLinkStatus.SUGGESTED, "request-1"))
        val draft = repository.observeDay("2026-11-08").first()
        assertEquals(listOf(id), draft.cards[0].notes.map { it.id })
        assertEquals(listOf(id), draft.cards[2].notes.map { it.id })
        assertTrue(draft.cards[1].notes.isEmpty())
        assertEquals(2, draft.cards[0].noteLinks.size)
        assertEquals(1, draft.cards[2].noteLinks.size)
        assertTrue(draft.cards.flatMap { it.noteLinks }.all { it.status == NoteLinkStatus.SUGGESTED })
        assertNull(db.noteDao().get(id)?.visitId)
        assertTrue(draft.pendingNotes.isEmpty())
    }

    @Test fun directTypedAndUserConfirmedLink_doNotDuplicateCardNote() = runBlocking {
        repository.addNote("v2", "해변 메모", NoteKind.TYPED, t("14:58"))
        val id = db.noteDao().getAll().single().id
        db.noteLinkDao().insert(NoteVisitLink(id, "v2", 0, 2, LinkOrigin.USER, NoteLinkStatus.CONFIRMED))
        val card = repository.observeDay("2026-11-08").first().cards[1]
        assertEquals(listOf(id), card.notes.map { it.id })
        assertEquals(1, card.noteLinks.size)
        assertEquals(NoteLinkStatus.CONFIRMED, card.noteLinks.single().status)
    }

    @Test fun rejectedOnlyVoice_remainsPendingAndDoesNotAppearOnVisitCard() = runBlocking {
        val id = repository.saveVoiceMemo("rejected-link", "카페가 좋았어", t("15:30"))
        db.noteLinkDao().insert(NoteVisitLink(id, "v3", 0, 2, LinkOrigin.AI, NoteLinkStatus.REJECTED, "request-2"))
        val draft = repository.observeDay("2026-11-08").first()
        assertEquals(listOf(id), draft.pendingNotes.map { it.id })
        assertTrue(draft.cards.all { it.notes.isEmpty() && it.noteLinks.isEmpty() })
    }

    @Test fun legacyVoiceVisitId_aloneIsNotPromotedToUserConfirmation() = runBlocking {
        val id = db.noteDao().insert(Note(
            visitId = "v3", text = "과거 자동 연결 원문", kind = NoteKind.VOICE,
            createdAt = t("15:30"), sourceDate = "2026-11-08", voiceSessionId = "legacy-voice",
        ))
        val draft = repository.observeDay("2026-11-08").first()
        assertEquals(listOf(id), draft.pendingNotes.map { it.id })
        assertTrue(draft.cards.all { it.notes.isEmpty() && it.noteLinks.isEmpty() })
    }

    @Test fun linkToAnotherDate_doesNotHidePendingVoiceOrMoveItToAnotherDay() = runBlocking {
        db.visitDao().insertAll(listOf(day[0].copy(
            id = "next-day", date = "2026-11-09", arrivedAt = t("12:00") + 86_400_000,
            leftAt = t("13:10") + 86_400_000,
        )))
        val id = repository.saveVoiceMemo("wrong-date-link", "식당", t("15:30"))
        db.noteLinkDao().insert(NoteVisitLink(id, "next-day", 0, 2, LinkOrigin.AI, NoteLinkStatus.SUGGESTED, "request-3"))
        assertEquals(listOf(id), repository.observeDay("2026-11-08").first().pendingNotes.map { it.id })
        val nextDay = repository.observeDay("2026-11-09").first()
        assertTrue(nextDay.pendingNotes.isEmpty())
        assertTrue(nextDay.cards.single().notes.isEmpty())
        assertTrue(nextDay.cards.single().noteLinks.isEmpty())
    }
}
