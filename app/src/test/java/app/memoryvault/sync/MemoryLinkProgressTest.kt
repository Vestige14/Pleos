package app.memoryvault.sync

import app.memoryvault.data.AiLinkJob
import app.memoryvault.domain.AiLinkJobState
import org.junit.Assert.*
import org.junit.Test

class MemoryLinkProgressTest {
    private fun job(id: String, note: Long, state: AiLinkJobState, revision: Int = 1, userRevision: Int = 0, error: String? = null) =
        AiLinkJob(id, note, revision, userRevision, "h", "memory-links-v1", state, 0, 0, lastErrorCode = error)
    @Test fun latestNoteAndUserRevisionOwnsStatusAcrossAllDates() {
        val result = MemoryLinkProgress.fromJobs(listOf(job("old", 1, AiLinkJobState.DONE),
            job("new", 1, AiLinkJobState.WAITING, revision = 2), job("old-user", 2, AiLinkJobState.DONE),
            job("new-user", 2, AiLinkJobState.REVIEW_REQUIRED, userRevision = 1), job("other-day", 3, AiLinkJobState.DONE)))
        assertEquals(1, result.waiting); assertEquals(1, result.suggested); assertEquals(1, result.review)
        assertEquals(AiLinkJobState.WAITING, result.notes[1L]?.state)
        assertEquals(AiLinkJobState.REVIEW_REQUIRED, result.notes[2L]?.state)
    }
    @Test fun fixedErrorCodesNeverReflectStoredUnknownStringsOrMarkReviewAsConfirmed() {
        val result = MemoryLinkProgress.fromJobs(listOf(job("j1", 1, AiLinkJobState.BLOCKED, error = "UNAUTHORIZED"),
            job("j2", 2, AiLinkJobState.REVIEW_REQUIRED, error = "OUTCOME_UNKNOWN"),
            job("j3", 3, AiLinkJobState.RUNNING, error = "private synthetic text")))
        assertEquals(1, result.blocked); assertEquals(1, result.review); assertEquals(1, result.waiting)
        assertEquals(LinkError.UNAUTHORIZED, result.notes[1L]?.error)
        assertNull(result.notes[3L]?.error)
        assertFalse(result.toString().contains("private"))
    }
}
