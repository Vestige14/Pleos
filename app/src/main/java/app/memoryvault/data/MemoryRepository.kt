package app.memoryvault.data

import androidx.room.withTransaction
import app.memoryvault.DemoConfig
import app.memoryvault.domain.NoteLinkStatus
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class MemoryRepository(private val db: AppDatabase, photoSource: PhotoSource = PhotoSource { emptyList() }) {
    suspend fun seedIfEmpty(seed: SeedDay) {
        db.visitDao().insertAll(seed.visits)
    }

    fun observeDay(date: String): Flow<DayDraft> = combine(
        db.visitDao().observeDay(date), db.photoDao().observeAll(), db.noteDao().observeAll(),
        db.noteLinkDao().observeAll(),
    ) { visits, photos, notes, links ->
        val visitIds = visits.map { it.id }.toSet()
        val notesById = notes.filter { it.kind == NoteKind.TYPED || it.sourceDate == date }.associateBy { it.id }
        val activeLinks = links.filter {
            it.status != NoteLinkStatus.REJECTED && it.visitId in visitIds && it.noteId in notesById
        }
        val linkedNoteIds = activeLinks.map { it.noteId }.toSet()
        val cards = visits.map { visit ->
            val visitLinks = activeLinks.filter { it.visitId == visit.id }
            val directTyped = notes.filter { it.kind == NoteKind.TYPED && it.visitId == visit.id }
            VisitCard(
                visit,
                photos.filter { it.visitId == visit.id && it.linkState in setOf(LinkState.CANDIDATE, LinkState.CONFIRMED) },
                (directTyped + visitLinks.mapNotNull { notesById[it.noteId] })
                    .distinctBy { it.id }.sortedWith(compareBy(Note::capturedAt, Note::id)),
                noteLinks = visitLinks,
            )
        }
        DayDraft(date, cards, pendingNotes = notes.filter {
            it.kind == NoteKind.VOICE && it.sourceDate == date && it.id !in linkedNoteIds
        })
    }

    suspend fun addNote(visitId: String, text: String, kind: NoteKind, now: Long): Boolean {
        require(kind == NoteKind.TYPED) { "Voice memos require saveVoiceMemo and a session ID" }
        if (text.isBlank()) return false
        db.noteDao().insert(Note(
            visitId = visitId, text = text, kind = kind, createdAt = now,
            sourceDate = Instant.ofEpochMilli(now).atZone(DemoConfig.DEMO_ZONE).toLocalDate().toString(),
        ))
        return true
    }

    suspend fun updateTypedNote(noteId: Long, text: String): Boolean {
        if (text.isBlank()) return false
        return db.noteDao().updateTypedText(noteId, text) == 1
    }

    suspend fun saveVoiceMemo(sessionId: String, text: String, capturedAt: Long): Long {
        require(text.isNotBlank()) { "Voice memo text is blank" }
        return db.withTransaction {
            db.noteDao().getByVoiceSession(sessionId)?.let { return@withTransaction it.id }
            val sourceDate = Instant.ofEpochMilli(capturedAt).atZone(DemoConfig.DEMO_ZONE).toLocalDate().toString()
            val captureVisit = db.visitDao().getDay(sourceDate).lastOrNull { it.arrivedAt <= capturedAt }
            val noteId = db.noteDao().insert(Note(
                visitId = null,
                text = text,
                kind = NoteKind.VOICE,
                createdAt = capturedAt,
                captureVisitId = captureVisit?.id,
                capturedAt = capturedAt,
                sourceDate = sourceDate,
                voiceSessionId = sessionId,
            ))
            db.noteLinkDao().insertPolicy(NoteLinkPolicy(noteId))
            AiLinkQueue(db).enqueueLinkJob(noteId)
            noteId
        }
    }
}
