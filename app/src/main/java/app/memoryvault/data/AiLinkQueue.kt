package app.memoryvault.data

import androidx.room.withTransaction
import app.memoryvault.domain.AiLinkJobState
import app.memoryvault.domain.CandidateSnapshot
import app.memoryvault.domain.VisitCandidate
import java.util.UUID

/** Persists requests only. Scheduling and network execution belong to a later stage. */
class AiLinkQueue(private val db: AppDatabase) {
    suspend fun enqueueLinkJob(noteId: Long): String = db.withTransaction {
        val note = requireNotNull(db.noteDao().get(noteId)) { "Note does not exist" }
        val policy = requireNotNull(db.noteLinkDao().getPolicy(noteId)) { "Note link policy does not exist" }
        db.aiLinkDao().getForRevision(noteId, note.revision, policy.userRevision)?.let {
            return@withTransaction it.jobId
        }
        val candidates = db.visitDao().getDay(note.sourceDate)
            .filter { it.arrivedAt <= note.capturedAt }
            .map { VisitCandidate(it.id, it.placeName, it.arrivedAt, it.leftAt) }
        val jobId = UUID.randomUUID().toString()
        db.aiLinkDao().insertJob(AiLinkJob(
            jobId = jobId,
            noteId = noteId,
            noteRevision = note.revision,
            userRevision = policy.userRevision,
            contextHash = CandidateSnapshot.contextHash(candidates),
            promptVersion = "memory-links-v1",
            state = AiLinkJobState.WAITING,
            attempts = 0,
            nextAttemptAt = System.currentTimeMillis(),
        ))
        db.aiLinkDao().insertSnapshot(AiLinkRequestSnapshot(jobId, CandidateSnapshot.encode(candidates)))
        jobId
    }
}
