package app.memoryvault.data

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.domain.AiLinkJobState
import app.memoryvault.domain.CandidateSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiLinkQueueTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: MemoryRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
        repository = MemoryRepository(db)
    }

    @After fun tearDown() = db.close()

    // A partial save or latest-visit assignment must never be reported as a saved voice memo.
    @Test fun offlineVoice_savesOneNotePolicyJobAndSnapshot() = runBlocking {
        repository.seedIfEmpty(seed)
        val actualBefore = System.currentTimeMillis()
        val raw = "  아까 칼국수집 국물이 좋았어\n"
        val id = repository.saveVoiceMemo("offline-session", raw, t("15:30"))
        assertCounts(1, 1, 1, 1)
        val note = requireNotNull(db.noteDao().get(id))
        assertEquals(raw, note.text)
        assertEquals(NoteKind.VOICE, note.kind)
        assertEquals("offline-session", note.voiceSessionId)
        assertNull(note.visitId)
        assertEquals("v3", note.captureVisitId)
        assertEquals(t("15:30"), note.capturedAt)
        assertEquals(t("15:30"), note.createdAt)
        assertEquals("2026-11-08", note.sourceDate)
        assertEquals(1, note.revision)
        val policy = requireNotNull(db.noteLinkDao().getPolicy(id))
        assertEquals(0, policy.userRevision)
        assertFalse(policy.userLocked)
        val job = db.aiLinkDao().getAllJobs().single()
        assertEquals(id, job.noteId)
        assertEquals(1, job.noteRevision)
        assertEquals(0, job.userRevision)
        assertEquals(AiLinkJobState.WAITING, job.state)
        assertEquals(0, job.attempts)
        assertTrue(job.nextAttemptAt in actualBefore..System.currentTimeMillis())
        assertNull(job.leaseUntil)
        assertNull(job.attemptToken)
        assertNull(job.lastErrorCode)
        assertEquals(listOf("v1", "v2", "v3"), candidates().map { it.id })
        val draft = repository.observeDay("2026-11-08").first()
        assertTrue(draft.cards.all { it.notes.isEmpty() && it.noteLinks.isEmpty() })
        assertEquals(listOf(id), draft.pendingNotes.map { it.id })
    }

    // Fail the fourth write in SQLite itself; the preceding three writes must disappear.
    @Test fun snapshotInsertFailure_rollsBackNotePolicyAndJob() = runBlocking {
        repository.seedIfEmpty(seed)
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER mv03a_fail_snapshot BEFORE INSERT ON ai_link_request_snapshots
            BEGIN SELECT RAISE(ABORT, 'mv03a test snapshot failure'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) {
            runBlocking { repository.saveVoiceMemo("rollback-session", "바다가 좋았어", t("14:58")) }
        }
        assertCounts(0, 0, 0, 0)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER mv03a_fail_snapshot")
        repository.saveVoiceMemo("rollback-session", "바다가 좋았어", t("14:58"))
        assertCounts(1, 1, 1, 1)
    }

    @Test fun sameSession_returnsOriginalNoteAndJobWithoutRewritingTextOrSnapshot() = runBlocking {
        repository.seedIfEmpty(seed)
        val firstId = repository.saveVoiceMemo("same-session", "처음 발화", t("14:58"))
        assertCounts(1, 1, 1, 1)
        val original = requireNotNull(db.noteDao().get(firstId))
        val job = db.aiLinkDao().getAllJobs().single()
        val snapshot = requireNotNull(db.aiLinkDao().getSnapshot(job.jobId))
        val secondId = repository.saveVoiceMemo("same-session", "중복 final의 다른 문장", t("16:30"))
        assertEquals(firstId, secondId)
        assertCounts(1, 1, 1, 1)
        assertEquals(original, db.noteDao().get(firstId))
        assertEquals(job, db.aiLinkDao().getAllJobs().single())
        assertEquals(snapshot, db.aiLinkDao().getSnapshot(job.jobId))
    }

    @Test fun concurrentSameSession_createsExactlyOneNoteAndJob() = runBlocking {
        repository.seedIfEmpty(seed)
        val ids = (1..20).map {
            async(Dispatchers.IO) {
                MemoryRepository(db).saveVoiceMemo("concurrent-session", "동일 최종 발화", t("14:58"))
            }
        }.awaitAll()
        assertCounts(1, 1, 1, 1)
        assertEquals(1, ids.toSet().size)
        assertEquals(db.noteDao().getAll().single().id, ids.first())
    }

    @Test fun voiceSessionUniqueConstraint_rejectsDuplicateRow() = runBlocking {
        val id = repository.saveVoiceMemo("db-unique-session", "음성 원문", t("11:00"))
        assertCounts(1, 1, 1, 1)
        val note = requireNotNull(db.noteDao().get(id))
        assertThrows(SQLiteException::class.java) {
            runBlocking { db.noteDao().insert(note.copy(id = 0)) }
        }
        assertCounts(1, 1, 1, 1)
    }

    @Test fun noVisits_stillSavesUnassignedVoiceAndEmptySnapshot() = runBlocking {
        val id = repository.saveVoiceMemo("no-visits-session", "남겨 둘 말", t("11:00"))
        assertCounts(1, 1, 1, 1)
        assertNull(db.noteDao().get(id)?.visitId)
        assertNull(db.noteDao().get(id)?.captureVisitId)
        assertTrue(candidates().isEmpty())
        val draft = repository.observeDay("2026-11-08").first()
        assertTrue(draft.cards.isEmpty())
        assertEquals(listOf(id), draft.pendingNotes.map { it.id })
    }

    @Test fun voiceRawText_cannotBeEditedThroughTypedPathOrDao() = runBlocking {
        val raw = "  아이들이 😊 바다에서 한참 놀았네\n"
        val id = repository.saveVoiceMemo("immutable-session", raw, t("14:58"))
        assertCounts(1, 1, 1, 1)
        val original = requireNotNull(db.noteDao().get(id))
        assertFalse(repository.updateTypedNote(id, "다른 원문"))
        assertEquals(0, db.noteDao().updateTypedText(id, "DAO에서도 변경 금지"))
        assertEquals(original, db.noteDao().get(id))
        assertEquals(raw, db.noteDao().get(id)?.text)
    }

    @Test fun blankVoice_isRejectedWithoutAnyWrites() = runBlocking {
        listOf("", "  \n\t").forEach { blank ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.saveVoiceMemo("blank-session", blank, t("14:58")) }
            }
        }
        assertCounts(0, 0, 0, 0)
    }

    @Test fun originalDateSnapshot_excludesFutureVisitsAndOtherDatesAndCoordinates() = runBlocking {
        repository.seedIfEmpty(seed)
        db.visitDao().insertAll(listOf(day[0].copy(
            id = "another-day", date = "2026-11-07", arrivedAt = t("10:00") - 86_400_000,
            leftAt = t("11:00") - 86_400_000,
        )))
        val id = repository.saveVoiceMemo("past-context", "아까 식당", t("14:58"))
        assertCounts(1, 1, 1, 1)
        assertEquals("2026-11-08", db.noteDao().get(id)?.sourceDate)
        assertEquals("v2", db.noteDao().get(id)?.captureVisitId)
        assertNull(db.noteDao().get(id)?.visitId)
        assertEquals(listOf("v1", "v2"), candidates().map { it.id })
        val snapshot = db.aiLinkDao().getAllSnapshots().single()
        Json.parseToJsonElement(snapshot.candidatesJson).jsonArray.forEach {
            assertEquals(setOf("id", "placeName", "arrivedAt", "leftAt"), it.jsonObject.keys)
        }
    }

    @Test fun exactArrival_isEligibleButBeforeFirstVisitHasNoCaptureVisit() = runBlocking {
        repository.seedIfEmpty(seed)
        val firstId = repository.saveVoiceMemo("before-first", "출발 전 메모", t("11:00"))
        assertCounts(1, 1, 1, 1)
        assertNull(db.noteDao().get(firstId)?.captureVisitId)
        assertTrue(candidates().isEmpty())
        val secondId = repository.saveVoiceMemo("at-arrival", "도착 메모", t("15:10"))
        assertEquals("v3", db.noteDao().get(secondId)?.captureVisitId)
        val secondJob = db.aiLinkDao().getAllJobs().single { it.noteId == secondId }
        assertEquals(listOf("v1", "v2", "v3"), CandidateSnapshot.decode(
            requireNotNull(db.aiLinkDao().getSnapshot(secondJob.jobId)).candidatesJson,
        ).map { it.id })
    }

    @Test fun sourceDate_usesSeoulEvenAcrossUtcMidnight() = runBlocking {
        repository.seedIfEmpty(seed)
        val utcTime = java.time.Instant.parse("2026-11-08T15:30:00Z").toEpochMilli()
        val id = repository.saveVoiceMemo("seoul-midnight", "자정 뒤 메모", utcTime)
        assertCounts(1, 1, 1, 1)
        assertEquals("2026-11-09", db.noteDao().get(id)?.sourceDate)
        assertNull(db.noteDao().get(id)?.captureVisitId)
        assertTrue(candidates().isEmpty())
        assertTrue(repository.observeDay("2026-11-08").first().pendingNotes.isEmpty())
        assertEquals(listOf(id), repository.observeDay("2026-11-09").first().pendingNotes.map { it.id })
    }

    @Test fun reenqueue_keepsOriginalJobAndSnapshotAfterCandidateChanges() = runBlocking {
        repository.seedIfEmpty(seed)
        val id = repository.saveVoiceMemo("frozen-snapshot", "아까 식당", t("14:58"))
        assertCounts(1, 1, 1, 1)
        val originalJob = db.aiLinkDao().getAllJobs().single()
        val originalSnapshot = db.aiLinkDao().getAllSnapshots().single()
        db.visitDao().insertAll(listOf(day[0].copy(id = "late-import", arrivedAt = t("12:30"))))
        assertEquals(originalJob.jobId, AiLinkQueue(db).enqueueLinkJob(id))
        assertCounts(1, 1, 1, 1)
        assertEquals(originalJob, db.aiLinkDao().getAllJobs().single())
        assertEquals(originalSnapshot, db.aiLinkDao().getAllSnapshots().single())
        assertEquals(listOf("v1", "v2"), candidates().map { it.id })
    }

    @Test fun concurrentEnqueueForExistingRevision_returnsSameJobWithoutDuplicates() = runBlocking {
        repository.seedIfEmpty(seed)
        val id = repository.saveVoiceMemo("enqueue-existing", "식당 메모", t("14:58"))
        val originalJobId = db.aiLinkDao().getAllJobs().single().jobId
        val ids = (1..20).map {
            async(Dispatchers.IO) { AiLinkQueue(db).enqueueLinkJob(id) }
        }.awaitAll()
        assertEquals(setOf(originalJobId), ids.toSet())
        assertCounts(1, 1, 1, 1)
    }

    // Exercises enqueue's own transaction, without saveVoiceMemo's outer transaction.
    @Test fun standaloneEnqueueFailure_preservesSourceAndRollsBackOnlyNewQueueWrites() = runBlocking {
        val id = db.noteDao().insert(Note(
            visitId = null, text = "저장된 원문", kind = NoteKind.VOICE,
            createdAt = t("14:58"), sourceDate = "2026-11-08", voiceSessionId = "standalone-queue",
        ))
        db.noteLinkDao().insertPolicy(NoteLinkPolicy(id))
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER mv03a_fail_standalone_snapshot BEFORE INSERT ON ai_link_request_snapshots
            BEGIN SELECT RAISE(ABORT, 'mv03a standalone snapshot failure'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) {
            runBlocking { AiLinkQueue(db).enqueueLinkJob(id) }
        }
        assertCounts(1, 1, 0, 0)
        assertEquals("저장된 원문", db.noteDao().get(id)?.text)
    }

    private suspend fun assertCounts(notes: Int, policies: Int, jobs: Int, snapshots: Int) {
        assertEquals("note count", notes, db.noteDao().getAll().size)
        assertEquals("policy count", policies, db.noteLinkDao().getAllPolicies().size)
        assertEquals("job count", jobs, db.aiLinkDao().getAllJobs().size)
        assertEquals("snapshot count", snapshots, db.aiLinkDao().getAllSnapshots().size)
    }

    private suspend fun candidates() = CandidateSnapshot.decode(
        db.aiLinkDao().getAllSnapshots().single().candidatesJson,
    )
}
