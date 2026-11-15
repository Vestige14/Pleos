package app.memoryvault.data

import androidx.room.withTransaction
import app.memoryvault.DemoConfig
import app.memoryvault.domain.NoteLinkStatus
import app.memoryvault.domain.PhotoMatcher
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhotoOperationBlocked : IllegalStateException("Photo operation requires current parked state")

class MemoryRepository(
    private val db: AppDatabase,
    private val photoSource: PhotoSource = PhotoSource { PhotoScan(emptyList()) },
    private val enqueueVoiceLink: suspend (Long) -> Unit = { AiLinkQueue(db).enqueueLinkJob(it); Unit },
) {
    private val photoSync = Mutex()

    /** The scan covers the library, so matching uses all visits rather than erasing other days' links. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun syncPhotos(date: String, canApply: () -> Boolean = { true }) = photoSync.withLock {
        if (!canApply()) throw PhotoOperationBlocked()
        val knownUris = db.photoDao().getAll().map { it.uri }.toSet()
        val scan = photoSource.scan(knownUris)
        currentCoroutineContext().ensureActive()
        if (!canApply()) throw PhotoOperationBlocked()
        db.withTransaction {
            if (!canApply()) throw PhotoOperationBlocked()
            val visits = db.visitDao().getAll()
            // Read current decisions after the slow scan, inside the write transaction.
            val current = db.photoDao().getAll().associateBy { it.uri }
            val scanned = scan.photos.associateBy { it.uri }
            val updated = scanned.values.map { meta ->
                val existing = current[meta.uri]
                if (existing?.linkState?.isUserDecided == true) {
                    existing.copy(takenAt = meta.takenAt, lat = meta.lat, lng = meta.lng, accessible = true)
                } else {
                    val visitId = PhotoMatcher.match(meta, visits)
                    Photo(meta.uri, meta.takenAt, meta.lat, meta.lng, visitId,
                        if (visitId == null) LinkState.UNLINKED else LinkState.CANDIDATE)
                }
            } + (scan.inaccessibleUris - scanned.keys).mapNotNull { uri -> current[uri]?.copy(accessible = false) }
            if (!canApply()) throw PhotoOperationBlocked()
            db.photoDao().upsert(updated)
            currentCoroutineContext().ensureActive()
            if (!canApply()) throw PhotoOperationBlocked()
        }
    }

    suspend fun setPhotoState(uri: String, state: LinkState, canApply: () -> Boolean = { true }): Boolean {
        require(state.isUserDecided) { "Only explicit photo confirmation or rejection is allowed" }
        if (!canApply()) return false
        return try {
            db.withTransaction {
                val photo = db.photoDao().get(uri) ?: return@withTransaction false
                if (!canApply()) throw PhotoOperationBlocked()
                if (!photo.accessible || photo.visitId == null || photo.linkState == LinkState.UNLINKED) {
                    return@withTransaction false
                }
                db.photoDao().upsert(listOf(photo.copy(linkState = state)))
                if (!canApply()) throw PhotoOperationBlocked()
                true
            }
        } catch (_: PhotoOperationBlocked) { false }
    }

    suspend fun seedIfEmpty(seed: SeedDay) {
        db.visitDao().insertAll(seed.visits)
    }

    fun observeDates(): Flow<List<String>> = db.visitDao().observeDates()

    fun observeDay(date: String): Flow<DayDraft> = combine(
        db.visitDao().observeDay(date), db.photoDao().observeAll(), db.noteDao().observeAll(),
        db.noteLinkDao().observeAll(), db.memoryAssociationDao().observeLinks(),
    ) { visits, photos, notes, legacyLinks, associations ->
        val links = projectVisitLinks(legacyLinks, associations)
        val visitIds = visits.map { it.id }.toSet()
        val notesById = notes.associateBy { it.id }
        val activeLinks = links.filter {
            it.status != NoteLinkStatus.REJECTED && it.visitId in visitIds && it.noteId in notesById
        }
        val linkedNoteIds = activeLinks.map { it.noteId }.toSet()
        val cards = visits.map { visit ->
            val visitLinks = activeLinks.filter { it.visitId == visit.id }
            val directTyped = notes.filter { it.kind == NoteKind.TYPED && it.visitId == visit.id }
            VisitCard(
                visit,
                photos.filter { it.accessible && it.visitId == visit.id && it.linkState in setOf(LinkState.CANDIDATE, LinkState.CONFIRMED) },
                (directTyped + visitLinks.mapNotNull { notesById[it.noteId] })
                    .distinctBy { it.id }.sortedWith(compareBy(Note::capturedAt, Note::id)),
                noteLinks = visitLinks,
            )
        }
        fun Photo.belongsToDay() = visitId in visitIds || takenAt == null ||
            Instant.ofEpochMilli(takenAt).atZone(DemoConfig.DEMO_ZONE).toLocalDate().toString() == date
        DayDraft(
            date, cards,
            pendingNotes = notes.filter { it.kind == NoteKind.VOICE && it.sourceDate == date && it.id !in linkedNoteIds },
            unlinkedPhotos = photos.filter { it.accessible && it.linkState == LinkState.UNLINKED && it.belongsToDay() },
            inaccessiblePhotoCount = photos.count { !it.accessible && it.belongsToDay() },
        )
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
            enqueueVoiceLink(noteId)
            noteId
        }
    }
}
