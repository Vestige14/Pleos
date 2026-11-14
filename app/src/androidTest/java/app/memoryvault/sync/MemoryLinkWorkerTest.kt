package app.memoryvault.sync

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.*
import app.memoryvault.data.*
import app.memoryvault.domain.AiLinkJobState
import app.memoryvault.domain.LinkOrigin
import app.memoryvault.domain.NoteLinkStatus
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryLinkWorkerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var queue: AiLinkQueue
    private lateinit var manager: WorkManager
    private lateinit var driver: TestDriver
    private lateinit var scheduler: MemoryLinkScheduler
    private lateinit var executor: MemoryLinkExecutor
    private var now = System.currentTimeMillis()
    private var beforeAttempt: suspend (LinkLease) -> Unit = {}
    private var duringApi: suspend (LinkRequest) -> Unit = {}
    private val calls = AtomicInteger()
    private var outcome: ApiOutcome? = null
    private val inputs = mutableListOf<Set<String>>()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        queue = AiLinkQueue(db, { now })
        val workStore = object : LinkWorkStore by queue {
            override suspend fun beginAttempt(lease: LinkLease): Boolean {
                beforeAttempt(lease)
                return queue.beginAttempt(lease)
            }
        }
        executor = MemoryLinkExecutor(workStore, MemoryLinkApi { request, _ ->
            calls.incrementAndGet()
            duringApi(request)
            outcome ?: ApiOutcome.Success(LinkResponse(request.requestId, request.noteRevision, request.userRevision,
                request.contextHash, LinkDecision.NO_MATCH, emptyList()))
        }, object : LinkSessionSource { override fun load() = LinkSession("http://localhost:8000", "x".repeat(48)) }) { true }
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                if (workerClassName != MemoryLinkWorker::class.java.name) return null
                synchronized(inputs) { inputs += workerParameters.inputData.keyValueMap.keys.toSet() }
                return MemoryLinkWorker(appContext, workerParameters, executor)
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(factory).setMinimumLoggingLevel(Log.ERROR).build())
        manager = WorkManager.getInstance(context)
        driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        scheduler = MemoryLinkScheduler(manager, queue, { now })
    }
    @After fun close() {
        manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
        db.close()
    }
    private suspend fun save(): AiLinkJob {
        MemoryRepository(db).saveVoiceMemo("synthetic-worker", "저장된 합성 원문", Instant.parse("2026-11-08T05:58:00Z").toEpochMilli())
        return db.aiLinkDao().getAllJobs().single().also { now = maxOf(now, it.nextAttemptAt) }
    }
    private fun infos(id: String) = manager.getWorkInfosForUniqueWork("link-$id").get(10, TimeUnit.SECONDS)
    private fun awaitState(id: String, expected: WorkInfo.State) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end) {
            if (infos(id).singleOrNull()?.state == expected) return
            Thread.sleep(10)
        }
        assertEquals(expected, infos(id).single().state)
    }
    private fun awaitRetry(id: String) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end) {
            val info = infos(id).singleOrNull()
            if (info?.state == WorkInfo.State.ENQUEUED && info.runAttemptCount == 1) return
            Thread.sleep(10)
        }
        val info = infos(id).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(1, info.runAttemptCount)
    }
    @Test fun workManagerDefaultFactoryCanConstructWorker() {
        assertNotNull(TestListenableWorkerBuilder<MemoryLinkWorker>(context).build())
    }
    @Test fun offlineDoesNoAPICallConnectedDriverRunsAndInputContainsOnlyJobId() = runBlocking {
        val job = save()
        scheduler.schedule(job.jobId).result.get(10, TimeUnit.SECONDS)
        assertEquals(WorkInfo.State.ENQUEUED, infos(job.jobId).single().state)
        assertEquals(0, calls.get()); assertEquals(0, queue.getJob(job.jobId)!!.attempts)
        driver.setAllConstraintsMet(infos(job.jobId).single().id)
        awaitState(job.jobId, WorkInfo.State.SUCCEEDED)
        assertEquals(1, calls.get()); assertEquals(listOf(setOf("jobId")), inputs)
        assertEquals(AiLinkJobState.REVIEW_REQUIRED, queue.getJob(job.jobId)!!.state)
    }
    @Test fun duplicateUniqueEnqueueAndReconcileRecoverCommitBeforeEnqueue() = runBlocking {
        val job = save()
        assertTrue(infos(job.jobId).isEmpty())
        scheduler.reconcilePendingJobs()
        // Querying the same serialized task executor observes the prior asynchronous enqueue.
        assertEquals(1, infos(job.jobId).size)
        scheduler.schedule(job.jobId).result.get(10, TimeUnit.SECONDS)
        assertEquals(1, infos(job.jobId).size)
        driver.setAllConstraintsMet(infos(job.jobId).single().id)
        awaitState(job.jobId, WorkInfo.State.SUCCEEDED)
        assertEquals(1, calls.get())
    }
    @Test fun retryable503RemainsQueuedButUnknownOutcomeFinishesWithoutRepeat() = runBlocking {
        val job = save()
        outcome = ApiOutcome.Failed(LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false))
        scheduler.schedule(job.jobId).result.get(10, TimeUnit.SECONDS)
        driver.setAllConstraintsMet(infos(job.jobId).single().id)
        awaitState(job.jobId, WorkInfo.State.SUCCEEDED)
        assertEquals(1, calls.get())
        assertEquals(AiLinkJobState.REVIEW_REQUIRED, queue.getJob(job.jobId)!!.state)
        scheduler.reconcilePendingJobs()
        assertEquals(1, calls.get())
    }
    @Test fun expiredFailureKeepsWorkerEnqueuedAndLeaseCanBeReclaimed() = runBlocking {
        val job = save()
        val original = db.noteDao().get(job.noteId)
        var expiredToken: String? = null
        duringApi = {
            expiredToken = queue.getJob(job.jobId)!!.attemptToken
            now += 120_000L
        }
        outcome = ApiOutcome.Failed(LinkFailure(503, LinkError.UPSTREAM_UNAVAILABLE, true))
        scheduler.schedule(job.jobId).result.get(10, TimeUnit.SECONDS)
        driver.setAllConstraintsMet(infos(job.jobId).single().id)
        awaitRetry(job.jobId)
        val running = queue.getJob(job.jobId)!!
        assertEquals(AiLinkJobState.RUNNING, running.state)
        assertEquals(1, running.attempts)
        assertEquals(expiredToken, running.attemptToken)
        assertEquals(1, calls.get())
        val replacement = queue.claim(job.jobId)!!
        assertNotEquals(expiredToken, replacement.attemptToken)
        assertEquals(1, replacement.attemptsBefore)
        assertEquals(original, db.noteDao().get(job.noteId))
        assertTrue(db.noteLinkDao().getForNote(job.noteId).isEmpty())
    }
    @Test fun rejectedExpiredAdmissionKeepsWorkerEnqueuedWithoutCallingAPI() = runBlocking {
        val job = save()
        val original = db.noteDao().get(job.noteId)
        var expiredToken: String? = null
        beforeAttempt = { lease ->
            expiredToken = lease.attemptToken
            now += 120_000L
        }
        scheduler.schedule(job.jobId).result.get(10, TimeUnit.SECONDS)
        driver.setAllConstraintsMet(infos(job.jobId).single().id)
        awaitRetry(job.jobId)
        val running = queue.getJob(job.jobId)!!
        assertEquals(AiLinkJobState.RUNNING, running.state)
        assertEquals(0, running.attempts)
        assertEquals(0, calls.get())
        val replacement = queue.claim(job.jobId)!!
        assertNotEquals(expiredToken, replacement.attemptToken)
        assertEquals(0, replacement.attemptsBefore)
        assertEquals(original, db.noteDao().get(job.noteId))
        assertTrue(db.noteLinkDao().getForNote(job.noteId).isEmpty())
    }
    @Test fun supersededAdmissionFinishesAndPreservesUserLockWithoutHTTP() = runBlocking {
        val job = save()
        val original = db.noteDao().get(job.noteId)
        beforeAttempt = {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE note_link_policies SET userLocked=1, userRevision=userRevision+1 WHERE noteId=?", arrayOf(job.noteId))
        }
        scheduler.schedule(job.jobId).result.get(10, TimeUnit.SECONDS)
        driver.setAllConstraintsMet(infos(job.jobId).single().id)
        awaitState(job.jobId, WorkInfo.State.SUCCEEDED)
        assertEquals(AiLinkJobState.SUPERSEDED, queue.getJob(job.jobId)!!.state)
        assertEquals(0, calls.get())
        assertEquals(0, queue.getJob(job.jobId)!!.attempts)
        assertTrue(db.noteLinkDao().getPolicy(job.noteId)!!.userLocked)
        assertEquals(1, db.noteLinkDao().getPolicy(job.noteId)!!.userRevision)
        assertEquals(original, db.noteDao().get(job.noteId))
        scheduler.reconcilePendingJobs()
        assertEquals(WorkInfo.State.SUCCEEDED, infos(job.jobId).single().state)
        assertEquals(0, calls.get())
    }
    @Test fun expiredSameTokenUnknownFinishesWithoutAnotherHTTP() = runBlocking {
        val job = save()
        val original = db.noteDao().get(job.noteId)
        val policy = db.noteLinkDao().getPolicy(job.noteId)
        duringApi = { now += 120_000L }
        outcome = ApiOutcome.Failed(LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false))
        scheduler.schedule(job.jobId).result.get(10, TimeUnit.SECONDS)
        driver.setAllConstraintsMet(infos(job.jobId).single().id)
        awaitState(job.jobId, WorkInfo.State.SUCCEEDED)
        val settled = queue.getJob(job.jobId)!!
        assertEquals(AiLinkJobState.REVIEW_REQUIRED, settled.state)
        assertEquals(LinkError.OUTCOME_UNKNOWN.name, settled.lastErrorCode)
        assertEquals(1, settled.attempts)
        assertNull(settled.attemptToken)
        assertNull(settled.leaseUntil)
        assertEquals(1, calls.get())
        now += 86_400_000L
        scheduler.reconcilePendingJobs()
        assertEquals(WorkExecution.FINISHED, executor.run(job.jobId))
        assertNull(queue.claim(job.jobId))
        assertEquals(1, calls.get())
        assertEquals(original, db.noteDao().get(job.noteId))
        assertEquals(policy, db.noteLinkDao().getPolicy(job.noteId))
        assertTrue(db.noteLinkDao().getForNote(job.noteId).isEmpty())
    }
    @Test fun oldUnknownDoesNotStopReplacementOwnerAndKeepsWakeup() = runBlocking {
        val job = save()
        val original = db.noteDao().get(job.noteId)
        var current: AiLinkJob? = null
        duringApi = {
            now += 120_000L
            val replacement = queue.claim(job.jobId)!!
            assertTrue(queue.beginAttempt(replacement))
            current = queue.getJob(job.jobId)
        }
        outcome = ApiOutcome.Failed(LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false))
        scheduler.schedule(job.jobId).result.get(10, TimeUnit.SECONDS)
        driver.setAllConstraintsMet(infos(job.jobId).single().id)
        awaitRetry(job.jobId)
        assertEquals(current, queue.getJob(job.jobId))
        assertEquals(AiLinkJobState.RUNNING, queue.getJob(job.jobId)!!.state)
        assertEquals(2, queue.getJob(job.jobId)!!.attempts)
        assertNull(queue.claim(job.jobId))
        assertEquals(1, calls.get())
        assertEquals(original, db.noteDao().get(job.noteId))
        assertTrue(db.noteLinkDao().getForNote(job.noteId).isEmpty())
    }
    @Test fun expiredUnknownPreservesConfirmedUserLinkAndStopsWork() = runBlocking {
        val job = save()
        val original = db.noteDao().get(job.noteId)!!
        val confirmed = NoteVisitLink(job.noteId, "user-v", 0, 1, LinkOrigin.USER, NoteLinkStatus.CONFIRMED)
        duringApi = {
            now += 120_000L
            db.visitDao().insertAll(listOf(Visit("user-v", original.sourceDate, "사용자가 확정한 곳", 1.0, 2.0,
                original.capturedAt - 1_000, original.capturedAt)))
            db.noteLinkDao().insert(confirmed)
            db.openHelper.writableDatabase.execSQL(
                "UPDATE note_link_policies SET userLocked=1,userRevision=userRevision+1 WHERE noteId=?", arrayOf(job.noteId))
        }
        outcome = ApiOutcome.Failed(LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false))
        scheduler.schedule(job.jobId).result.get(10, TimeUnit.SECONDS)
        driver.setAllConstraintsMet(infos(job.jobId).single().id)
        awaitState(job.jobId, WorkInfo.State.SUCCEEDED)
        assertEquals(AiLinkJobState.SUPERSEDED, queue.getJob(job.jobId)!!.state)
        assertEquals(listOf(confirmed), db.noteLinkDao().getForNote(job.noteId))
        assertEquals(NoteLinkPolicy(job.noteId, 1, true), db.noteLinkDao().getPolicy(job.noteId))
        assertEquals(original, db.noteDao().get(job.noteId))
        now += 86_400_000L
        scheduler.reconcilePendingJobs()
        assertEquals(WorkExecution.FINISHED, executor.run(job.jobId))
        assertEquals(1, calls.get())
    }
    @Test fun oldUnknownCannotReopenReplacementThatAlreadyRequiresReview() = runBlocking {
        val job = save()
        duringApi = {
            now += 120_000L
            val replacement = queue.claim(job.jobId)!!
            assertTrue(queue.beginAttempt(replacement))
            assertEquals(AiLinkJobState.REVIEW_REQUIRED,
                queue.fail(job.jobId, LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false), replacement.attemptToken))
        }
        outcome = ApiOutcome.Failed(LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false))
        scheduler.schedule(job.jobId).result.get(10, TimeUnit.SECONDS)
        driver.setAllConstraintsMet(infos(job.jobId).single().id)
        awaitState(job.jobId, WorkInfo.State.SUCCEEDED)
        assertEquals(AiLinkJobState.REVIEW_REQUIRED, queue.getJob(job.jobId)!!.state)
        assertEquals(LinkError.OUTCOME_UNKNOWN.name, queue.getJob(job.jobId)!!.lastErrorCode)
        assertEquals(2, queue.getJob(job.jobId)!!.attempts)
        assertEquals(WorkExecution.FINISHED, executor.run(job.jobId))
        assertEquals(1, calls.get())
    }
}
