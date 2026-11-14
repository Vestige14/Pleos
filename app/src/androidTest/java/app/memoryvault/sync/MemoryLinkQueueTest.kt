package app.memoryvault.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.data.*
import app.memoryvault.domain.*
import java.time.OffsetDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryLinkQueueTest {
    private lateinit var db: AppDatabase
    private lateinit var queue: AiLinkQueue
    private lateinit var repository: MemoryRepository
    private var now = 1_800_000_000_000L
    private val captured = OffsetDateTime.parse("2026-11-08T14:58:00+09:00").toInstant().toEpochMilli()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        repository = MemoryRepository(db)
        queue = AiLinkQueue(db, { now })
    }
    @After fun close() = db.close()
    private suspend fun job(): AiLinkJob {
        db.visitDao().insertAll(listOf(Visit("v2", "2026-11-08", "해변", 1.0, 2.0, captured - 10_000, captured - 1000)))
        val noteId = repository.saveVoiceMemo("synthetic-${db.noteDao().getAll().size}", "😊 바다가 좋았어", captured)
        val job = db.aiLinkDao().getAllJobs().single { it.noteId == noteId }
        // Enqueue's real system clock is intentionally distinct from replay capture time.
        now = maxOf(now, job.nextAttemptAt)
        return job
    }
    private fun success(lease: LinkLease) = LinkResponse(lease.request.requestId, lease.request.noteRevision,
        lease.request.userRevision, lease.request.contextHash, LinkDecision.SUGGEST, listOf(LinkSpan("v2", 2, 4)))

    @Test fun oneAtomicLeaseAcrossConcurrentQueuesAndAttemptsCountOnlyAdmission() = runBlocking {
        val job = job()
        val leases = (1..12).map { async(Dispatchers.IO) { AiLinkQueue(db, { now }).claim(job.jobId) } }.awaitAll().filterNotNull()
        assertEquals(1, leases.size)
        val running = queue.getJob(job.jobId)!!
        assertEquals(AiLinkJobState.RUNNING, running.state)
        assertEquals(0, running.attempts)
        assertEquals(now + 120_000L, running.leaseUntil)
        assertTrue(queue.beginAttempt(leases.single()))
        assertFalse(queue.beginAttempt(leases.single()))
        assertEquals(1, queue.getJob(job.jobId)!!.attempts)
    }
    @Test fun expiredLeaseAndReplacedAttemptIgnoreOldResult() = runBlocking {
        val job = job(); val old = queue.claim(job.jobId)!!; queue.beginAttempt(old)
        now += 120_000
        assertEquals(ApplyOutcome.IGNORED_STALE, queue.applyResult(job.jobId, success(old), old.attemptToken))
        val replacement = queue.claim(job.jobId)!!
        assertNotEquals(old.attemptToken, replacement.attemptToken)
        queue.beginAttempt(replacement)
        assertEquals(ApplyOutcome.IGNORED_STALE, queue.applyResult(job.jobId, success(old), old.attemptToken))
        assertEquals(ApplyOutcome.APPLIED, queue.applyResult(job.jobId, success(replacement), replacement.attemptToken))
        assertEquals(1, db.noteLinkDao().observeAll().first().size)
    }
    @Test fun suggestIsNotConfirmationAndNeverMutatesOriginal() = runBlocking {
        val job = job(); val note = db.noteDao().get(job.noteId)!!; val lease = queue.claim(job.jobId)!!; queue.beginAttempt(lease)
        assertEquals(ApplyOutcome.APPLIED, queue.applyResult(job.jobId, success(lease), lease.attemptToken))
        assertEquals(note, db.noteDao().get(job.noteId))
        assertEquals(NoteVisitLink(job.noteId, "v2", 2, 4, LinkOrigin.AI, NoteLinkStatus.SUGGESTED, job.jobId), db.noteLinkDao().observeAll().first().single())
        assertEquals(AiLinkJobState.DONE, queue.getJob(job.jobId)!!.state)
        assertEquals(ApplyOutcome.IGNORED_STALE, queue.applyResult(job.jobId, success(lease), lease.attemptToken))
    }
    @Test fun revisionAndLockChangesSupersedeWithoutOverwritingDecisions() = runBlocking {
        for (sql in listOf("UPDATE note_link_policies SET userRevision=userRevision+1", "UPDATE note_link_policies SET userLocked=1", "UPDATE notes SET revision=revision+1")) {
            val job = job(); val lease = queue.claim(job.jobId)!!; queue.beginAttempt(lease)
            db.openHelper.writableDatabase.execSQL(sql + " WHERE " + if (sql.contains("policies")) "noteId=${job.noteId}" else "id=${job.noteId}")
            assertEquals(ApplyOutcome.IGNORED_STALE, queue.applyResult(job.jobId, success(lease), lease.attemptToken))
            assertEquals(AiLinkJobState.SUPERSEDED, queue.getJob(job.jobId)!!.state)
        }
        assertTrue(db.noteLinkDao().observeAll().first().isEmpty())
    }
    @Test fun existingConfirmedOrRejectedLinksBlockAutomaticWorkEvenWithoutPolicyLock() = runBlocking {
        val job = job()
        db.noteLinkDao().insert(NoteVisitLink(job.noteId, "v2", 2, 4, LinkOrigin.USER, NoteLinkStatus.CONFIRMED))
        assertNull(queue.claim(job.jobId))
        assertEquals(NoteLinkStatus.CONFIRMED, db.noteLinkDao().observeAll().first().single().status)
        assertEquals(0, queue.getJob(job.jobId)!!.attempts)
    }
    @Test fun reviewNoMatchAndMalformedEchoBecomeReviewWithoutLinks() = runBlocking {
        for (decision in listOf(LinkDecision.REVIEW, LinkDecision.NO_MATCH)) {
            val job = job(); val lease = queue.claim(job.jobId)!!; queue.beginAttempt(lease)
            val result = success(lease).copy(decision = decision, links = emptyList())
            assertEquals(ApplyOutcome.REVIEW_REQUIRED, queue.applyResult(job.jobId, result, lease.attemptToken))
            assertEquals(AiLinkJobState.REVIEW_REQUIRED, queue.getJob(job.jobId)!!.state)
        }
        val job = job(); val lease = queue.claim(job.jobId)!!; queue.beginAttempt(lease)
        assertEquals(ApplyOutcome.REVIEW_REQUIRED, queue.applyResult(job.jobId, success(lease).copy(contextHash = "wrong"), lease.attemptToken))
        assertTrue(db.noteLinkDao().observeAll().first().isEmpty())
    }
    @Test fun changedSnapshotHashSupersedesCurrentAttempt() = runBlocking {
        val job = job(); val lease = queue.claim(job.jobId)!!; queue.beginAttempt(lease)
        db.openHelper.writableDatabase.execSQL("UPDATE ai_link_request_snapshots SET candidatesJson='[]' WHERE jobId=?", arrayOf(job.jobId))
        assertEquals(ApplyOutcome.IGNORED_STALE, queue.applyResult(job.jobId, success(lease), lease.attemptToken))
        assertEquals(AiLinkJobState.SUPERSEDED, queue.getJob(job.jobId)!!.state)
    }
    @Test fun retryAfterAndFiveAdmissionsAreBoundedUsingRealClockDomain() = runBlocking {
        val job = job()
        repeat(5) { attempt ->
            val lease = queue.claim(job.jobId)!!; assertTrue(queue.beginAttempt(lease))
            val state = queue.fail(job.jobId, LinkFailure(503, LinkError.UPSTREAM_UNAVAILABLE, true, 90_000), lease.attemptToken)
            assertEquals(if (attempt == 4) AiLinkJobState.REVIEW_REQUIRED else AiLinkJobState.RETRY_WAIT, state)
            if (attempt < 4) {
                val current = queue.getJob(job.jobId)!!
                assertEquals(now + maxOf(90_000L, 30_000L * (1L shl attempt)), current.nextAttemptAt)
                assertNull(queue.claim(job.jobId)); now = current.nextAttemptAt
            }
        }
        now += 86_400_000
        assertNull(queue.claim(job.jobId)); assertEquals(5, queue.getJob(job.jobId)!!.attempts)
    }
    @Test fun unknownOutcomeAndAuthAndQuotaNeverRetryFromHTTPStatusAlone() = runBlocking {
        for (f in listOf(LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false), LinkFailure(401, LinkError.UNAUTHORIZED, false), LinkFailure(502, LinkError.UPSTREAM_QUOTA, false))) {
            val job = job(); val lease = queue.claim(job.jobId)!!; queue.beginAttempt(lease)
            val state = queue.fail(job.jobId, f, lease.attemptToken)
            assertEquals(if (f.code == LinkError.OUTCOME_UNKNOWN) AiLinkJobState.REVIEW_REQUIRED else AiLinkJobState.BLOCKED, state)
            now += 1_000_000; assertNull(queue.claim(job.jobId))
        }
    }
    @Test fun reconcilingAllDaysRetainsCapturedTimeAndFrozenCandidates() = runBlocking {
        val job = job()
        db.visitDao().insertAll(listOf(Visit("new", "2026-11-08", "나중에 가져온 곳", 3.0, 4.0, captured - 5000, captured)))
        now += 86_400_000L * 7
        assertTrue(queue.pendingJobs().any { it.jobId == job.jobId })
        val request = queue.claim(job.jobId)!!.request
        assertEquals("2026-11-08T14:58:00+09:00", request.capturedAt)
        assertEquals(listOf("v2"), request.candidates.map { it.id })
        assertFalse(MemoryLinkProtocol.encodeRequest(request).contains("lat"))
    }
    @Test fun applyingLinksAndDoneIsOneTransaction() = runBlocking {
        val job = job(); val lease = queue.claim(job.jobId)!!; queue.beginAttempt(lease)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_done BEFORE UPDATE ON ai_link_jobs WHEN NEW.state='DONE' BEGIN SELECT RAISE(ABORT, 'synthetic rollback'); END")
        assertThrows(Exception::class.java) { runBlocking { queue.applyResult(job.jobId, success(lease), lease.attemptToken) } }
        assertTrue(db.noteLinkDao().observeAll().first().isEmpty())
        assertEquals(AiLinkJobState.RUNNING, queue.getJob(job.jobId)!!.state)
    }
    @Test fun cancelBeforeAdmissionReleasesButAfterAdmissionRequiresReview() = runBlocking {
        val first = job(); val lease = queue.claim(first.jobId)!!
        queue.cancel(first.jobId, lease.attemptToken)
        assertEquals(AiLinkJobState.WAITING, queue.getJob(first.jobId)!!.state)
        val next = queue.claim(first.jobId)!!; queue.beginAttempt(next); queue.cancel(first.jobId, next.attemptToken)
        assertEquals(AiLinkJobState.REVIEW_REQUIRED, queue.getJob(first.jobId)!!.state)
        assertEquals(LinkError.OUTCOME_UNKNOWN.name, queue.getJob(first.jobId)!!.lastErrorCode)
    }
    @Test fun expiredSameAttemptUnknownRequiresReviewAndCannotBeClaimedAgain() = runBlocking {
        val job = job()
        val original = db.noteDao().get(job.noteId)
        val policy = db.noteLinkDao().getPolicy(job.noteId)
        val lease = queue.claim(job.jobId)!!
        assertTrue(queue.beginAttempt(lease))
        now += 120_000L
        assertEquals(AiLinkJobState.REVIEW_REQUIRED,
            queue.fail(job.jobId, LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false), lease.attemptToken))
        val settled = queue.getJob(job.jobId)!!
        assertEquals(AiLinkJobState.REVIEW_REQUIRED, settled.state)
        assertEquals(LinkError.OUTCOME_UNKNOWN.name, settled.lastErrorCode)
        assertEquals(1, settled.attempts)
        assertNull(settled.attemptToken)
        assertNull(settled.leaseUntil)
        now += 86_400_000L
        assertNull(queue.claim(job.jobId))
        assertFalse(queue.pendingJobs().any { it.jobId == job.jobId })
        assertEquals(ApplyOutcome.IGNORED_STALE, queue.applyResult(job.jobId, success(lease), lease.attemptToken))
        assertEquals(original, db.noteDao().get(job.noteId))
        assertEquals(policy, db.noteLinkDao().getPolicy(job.noteId))
        assertTrue(db.noteLinkDao().getForNote(job.noteId).isEmpty())
    }
    @Test fun expiredUnknownFromReplacedAttemptCannotStopCurrentOwner() = runBlocking {
        for (expireReplacement in listOf(false, true)) {
            val job = job()
            val old = queue.claim(job.jobId)!!
            assertTrue(queue.beginAttempt(old))
            now += 120_000L
            val replacement = queue.claim(job.jobId)!!
            assertTrue(queue.beginAttempt(replacement))
            assertNotEquals(old.attemptToken, replacement.attemptToken)
            if (expireReplacement) now += 120_000L
            val current = queue.getJob(job.jobId)
            assertNull(queue.fail(job.jobId, LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false), old.attemptToken))
            assertEquals(current, queue.getJob(job.jobId))
            assertEquals(AiLinkJobState.RUNNING, queue.getJob(job.jobId)!!.state)
            assertEquals(replacement.attemptToken, queue.getJob(job.jobId)!!.attemptToken)
            assertTrue(db.noteLinkDao().getForNote(job.noteId).isEmpty())
        }
    }
    @Test fun expiredUnknownPreservesUserConfirmedLinkAndLock() = runBlocking {
        val job = job()
        val original = db.noteDao().get(job.noteId)
        val lease = queue.claim(job.jobId)!!
        assertTrue(queue.beginAttempt(lease))
        now += 120_000L
        val confirmed = NoteVisitLink(job.noteId, "v2", 2, 4, LinkOrigin.USER, NoteLinkStatus.CONFIRMED)
        db.noteLinkDao().insert(confirmed)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE note_link_policies SET userLocked=1,userRevision=userRevision+1 WHERE noteId=?", arrayOf(job.noteId))
        assertEquals(AiLinkJobState.SUPERSEDED,
            queue.fail(job.jobId, LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false), lease.attemptToken))
        assertEquals(AiLinkJobState.SUPERSEDED, queue.getJob(job.jobId)!!.state)
        assertEquals(listOf(confirmed), db.noteLinkDao().getForNote(job.noteId))
        assertEquals(NoteLinkPolicy(job.noteId, 1, true), db.noteLinkDao().getPolicy(job.noteId))
        assertEquals(original, db.noteDao().get(job.noteId))
        assertNull(queue.claim(job.jobId))
    }
    @Test fun expiredUnknownHonorsRevisionLockAndSnapshotChanges() = runBlocking {
        for (sql in listOf(
            "UPDATE note_link_policies SET userRevision=userRevision+1 WHERE noteId=?",
            "UPDATE note_link_policies SET userLocked=1 WHERE noteId=?",
            "UPDATE notes SET revision=revision+1 WHERE id=?",
            "UPDATE ai_link_request_snapshots SET candidatesJson='[]' WHERE jobId=?",
        )) {
            val job = job()
            val lease = queue.claim(job.jobId)!!
            assertTrue(queue.beginAttempt(lease))
            now += 120_000L
            val argument: Any = if (sql.contains("snapshots")) job.jobId else job.noteId
            db.openHelper.writableDatabase.execSQL(sql, arrayOf(argument))
            val note = db.noteDao().get(job.noteId)
            val policy = db.noteLinkDao().getPolicy(job.noteId)
            assertEquals(AiLinkJobState.SUPERSEDED,
                queue.fail(job.jobId, LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false), lease.attemptToken))
            assertEquals(note, db.noteDao().get(job.noteId))
            assertEquals(policy, db.noteLinkDao().getPolicy(job.noteId))
            assertTrue(db.noteLinkDao().getForNote(job.noteId).isEmpty())
            assertNull(queue.claim(job.jobId))
        }
    }
}
