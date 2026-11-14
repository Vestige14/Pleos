package app.memoryvault.sync

import app.memoryvault.domain.AiLinkJobState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Runs the same state machine from CoroutineWorker and tests; Room remains the scheduling authority. */
class MemoryLinkExecutor(private val queue: LinkWorkStore, private val api: MemoryLinkApi,
    private val sessions: LinkSessionSource, private val connected: () -> Boolean) {
    suspend fun run(jobId: String): WorkExecution {
        if (!connected()) return WorkExecution.RETRY
        val session = try { sessions.load() }
        catch (_: LinkSessionException) {
            queue.blockForConfiguration(jobId, LinkError.SESSION_STORAGE)
            return WorkExecution.FINISHED
        }
        if (session == null) {
            queue.blockForConfiguration(jobId, LinkError.CONFIG_REQUIRED)
            return WorkExecution.FINISHED
        }
        val lease = queue.claim(jobId) ?: return nextExecution(jobId)
        try {
            if (!connected()) { queue.cancel(jobId, lease.attemptToken); return WorkExecution.RETRY }
            if (!queue.beginAttempt(lease)) return nextExecution(jobId)
            return when (val outcome = api.link(lease.request, session)) {
                is ApiOutcome.Success -> {
                    val applied = queue.applyResult(jobId, outcome.result, lease.attemptToken)
                    if (applied == ApplyOutcome.IGNORED_STALE) nextExecution(jobId)
                    else WorkExecution.FINISHED
                }
                is ApiOutcome.Failed -> {
                    queue.fail(jobId, outcome.failure, lease.attemptToken)
                    nextExecution(jobId)
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { queue.cancel(jobId, lease.attemptToken) }
            throw cancelled
        }
    }

    // A stale owner cannot change Room, but must preserve a wake-up for the current active job.
    private suspend fun nextExecution(jobId: String): WorkExecution = when (queue.getJob(jobId)?.state) {
        AiLinkJobState.WAITING, AiLinkJobState.RETRY_WAIT, AiLinkJobState.RUNNING -> WorkExecution.RETRY
        else -> WorkExecution.FINISHED
    }
}
enum class WorkExecution { FINISHED, RETRY }
