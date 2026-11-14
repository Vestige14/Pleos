package app.memoryvault.sync

import app.memoryvault.data.AiLinkJob
import app.memoryvault.domain.AiLinkJobState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MemoryLinkExecutorTest {
    private val request = LinkRequest("job", 1, 0, "h", "2026-11-08T14:58:00+09:00", "바다", emptyList())
    private val session = LinkSession("http://localhost:8000", "x".repeat(48))
    private val source = object : LinkSessionSource { override fun load() = session }
    @Test fun offlineDoesNotAcquireLeaseOrCallAPIAndConnectedExecutesOriginalSnapshot() = runBlocking {
        val queue = Store(request)
        var connected = false
        val sent = mutableListOf<LinkRequest>()
        val executor = MemoryLinkExecutor(queue, MemoryLinkApi { req, _ -> sent += req; ApiOutcome.Success(response(req)) }, source) { connected }
        assertEquals(WorkExecution.RETRY, executor.run("job")); assertNull(queue.lease); assertTrue(sent.isEmpty())
        connected = true
        assertEquals(WorkExecution.FINISHED, executor.run("job"))
        assertEquals(listOf(request), sent); assertEquals(AiLinkJobState.REVIEW_REQUIRED, queue.job.state)
    }
    @Test fun duplicateExecutionHasOneHTTPWhileLeaseOwnerIsSuspended() = runBlocking {
        val queue = Store(request); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val executor = MemoryLinkExecutor(queue, MemoryLinkApi { req, _ -> calls++; entered.complete(Unit); release.await(); ApiOutcome.Success(response(req)) }, source) { true }
        val first = async { executor.run("job") }; entered.await()
        assertEquals(WorkExecution.RETRY, executor.run("job")); assertEquals(1, calls)
        release.complete(Unit); first.await(); assertEquals(1, calls)
    }
    @Test fun onlyRetryableStoreOutcomeReturnsRetryAndAuthNeverLoops() = runBlocking {
        for (failure in listOf(LinkFailure(429, LinkError.UPSTREAM_RATE_LIMIT, true), LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false), LinkFailure(401, LinkError.UNAUTHORIZED, false))) {
            val queue = Store(request)
            val executor = MemoryLinkExecutor(queue, MemoryLinkApi { _, _ -> ApiOutcome.Failed(failure) }, source) { true }
            assertEquals(if (failure.retryable) WorkExecution.RETRY else WorkExecution.FINISHED, executor.run("job"))
            assertEquals(failure.code.name, queue.job.lastErrorCode)
        }
    }
    @Test fun noSessionBlocksWithoutAttemptAndCancellationIsPropagated() = runBlocking {
        val queue = Store(request); var calls = 0
        val empty = object : LinkSessionSource { override fun load(): LinkSession? = null }
        val executor = MemoryLinkExecutor(queue, MemoryLinkApi { req, _ -> calls++; ApiOutcome.Success(response(req)) }, empty) { true }
        assertEquals(WorkExecution.FINISHED, executor.run("job")); assertEquals(0, calls)
        assertEquals(AiLinkJobState.BLOCKED, queue.job.state); assertEquals(0, queue.job.attempts)
        val store = Store(request); val entered = CompletableDeferred<Unit>()
        val cancel = MemoryLinkExecutor(store, MemoryLinkApi { _, _ -> entered.complete(Unit); awaitCancellation() }, source) { true }
        val running = launch { cancel.run("job") }; entered.await(); running.cancelAndJoin()
        assertTrue(store.cancelled); assertTrue(running.isCancelled)
    }
    @Test fun staleResultWhileReplacementIsStillActiveRequestsAnotherWakeup() = runBlocking {
        val queue = object : Store(request) {
            override suspend fun applyResult(jobId: String, result: LinkResponse, attemptToken: String) = ApplyOutcome.IGNORED_STALE
        }
        val executor = MemoryLinkExecutor(queue, MemoryLinkApi { r, _ -> ApiOutcome.Success(response(r)) }, source) { true }
        assertEquals(WorkExecution.RETRY, executor.run("job"))
    }
    @Test fun staleFailureKeepsWakeupForEveryActivePersistedState() = runBlocking {
        for (state in listOf(AiLinkJobState.WAITING, AiLinkJobState.RETRY_WAIT, AiLinkJobState.RUNNING)) {
            val queue = object : Store(request) {
                override suspend fun fail(jobId: String, failure: LinkFailure, attemptToken: String): AiLinkJobState? {
                    job = job.copy(state = state)
                    return null
                }
            }
            var calls = 0
            val executor = MemoryLinkExecutor(queue, MemoryLinkApi { _, _ ->
                calls++
                ApiOutcome.Failed(LinkFailure(503, LinkError.UPSTREAM_UNAVAILABLE, true))
            }, source) { true }
            assertEquals(state.name, WorkExecution.RETRY, executor.run("job"))
            assertEquals(1, calls)
            assertEquals(state, queue.job.state)
        }
    }
    @Test fun rejectedAttemptKeepsWakeupForEveryActivePersistedStateWithoutHTTP() = runBlocking {
        for (state in listOf(AiLinkJobState.WAITING, AiLinkJobState.RETRY_WAIT, AiLinkJobState.RUNNING)) {
            val queue = object : Store(request) {
                override suspend fun beginAttempt(lease: LinkLease): Boolean {
                    job = job.copy(state = state)
                    return false
                }
            }
            var calls = 0
            val executor = MemoryLinkExecutor(queue, MemoryLinkApi { r, _ -> calls++; ApiOutcome.Success(response(r)) }, source) { true }
            assertEquals(state.name, WorkExecution.RETRY, executor.run("job"))
            assertEquals(0, calls)
            assertEquals(0, queue.job.attempts)
            assertEquals(state, queue.job.state)
        }
    }
    @Test fun staleFailureAndRejectedAttemptNeverRepeatSupersededOrUnknownOutcomeJobs() = runBlocking {
        for (state in listOf(AiLinkJobState.SUPERSEDED, AiLinkJobState.REVIEW_REQUIRED)) {
            for (rejectAdmission in listOf(false, true)) {
                val queue = object : Store(request) {
                    private fun finish() {
                        job = job.copy(state = state,
                            lastErrorCode = if (state == AiLinkJobState.REVIEW_REQUIRED) LinkError.OUTCOME_UNKNOWN.name else null)
                    }
                    override suspend fun beginAttempt(lease: LinkLease): Boolean {
                        if (!rejectAdmission) return super.beginAttempt(lease)
                        finish()
                        return false
                    }
                    override suspend fun fail(jobId: String, failure: LinkFailure, attemptToken: String): AiLinkJobState? {
                        finish()
                        return null
                    }
                }
                var calls = 0
                val executor = MemoryLinkExecutor(queue, MemoryLinkApi { _, _ ->
                    calls++
                    ApiOutcome.Failed(LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false))
                }, source) { true }
                assertEquals(WorkExecution.FINISHED, executor.run("job"))
                assertEquals(WorkExecution.FINISHED, executor.run("job"))
                assertEquals(if (rejectAdmission) 0 else 1, calls)
                assertEquals(state, queue.job.state)
                if (state == AiLinkJobState.REVIEW_REQUIRED) assertEquals(LinkError.OUTCOME_UNKNOWN.name, queue.job.lastErrorCode)
            }
        }
    }
    private fun response(r: LinkRequest) = LinkResponse(r.requestId, r.noteRevision, r.userRevision, r.contextHash, LinkDecision.NO_MATCH, emptyList())

    private open class Store(val request: LinkRequest) : LinkWorkStore {
        var job = AiLinkJob("job", 1, 1, 0, "h", "memory-links-v1", AiLinkJobState.WAITING, 0, 0)
        var lease: LinkLease? = null; var cancelled = false
        override suspend fun claim(jobId: String): LinkLease? {
            if (job.state != AiLinkJobState.WAITING) return null
            job = job.copy(state = AiLinkJobState.RUNNING)
            return LinkLease(jobId, "attempt", request, 0).also { lease = it }
        }
        override suspend fun beginAttempt(lease: LinkLease): Boolean { job = job.copy(attempts = 1); return true }
        override suspend fun getJob(jobId: String) = job
        override suspend fun pendingJobs() = listOf(job)
        override suspend fun applyResult(jobId: String, result: LinkResponse, attemptToken: String): ApplyOutcome {
            job = job.copy(state = AiLinkJobState.REVIEW_REQUIRED); return ApplyOutcome.REVIEW_REQUIRED
        }
        override suspend fun fail(jobId: String, failure: LinkFailure, attemptToken: String): AiLinkJobState? {
            job = job.copy(state = if (failure.retryable) AiLinkJobState.RETRY_WAIT else AiLinkJobState.BLOCKED, lastErrorCode = failure.code.name); return job.state
        }
        override suspend fun cancel(jobId: String, attemptToken: String) { cancelled = true }
        override suspend fun blockForConfiguration(jobId: String, code: LinkError) { job = job.copy(state = AiLinkJobState.BLOCKED) }
        override suspend fun resumeConfigurationBlocked() {}
    }
}
