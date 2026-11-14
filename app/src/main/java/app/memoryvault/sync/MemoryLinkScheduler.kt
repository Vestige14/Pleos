package app.memoryvault.sync

import androidx.work.*
import app.memoryvault.domain.AiLinkJobState
import java.util.concurrent.TimeUnit

class MemoryLinkScheduler(private val manager: WorkManager, private val queue: LinkWorkStore,
    private val now: () -> Long = System::currentTimeMillis) {
    fun request(jobId: String, nextAttemptAt: Long = 0): OneTimeWorkRequest {
        require(jobId.isNotBlank())
        // The DB still enforces long Retry-After deadlines when this wake-up is earlier.
        val delay = (nextAttemptAt - now()).coerceIn(0, TimeUnit.DAYS.toMillis(1))
        return OneTimeWorkRequestBuilder<MemoryLinkWorker>()
            .setInputData(workDataOf("jobId" to jobId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
    }
    fun schedule(jobId: String, nextAttemptAt: Long = 0): Operation = manager.enqueueUniqueWork(
        "link-$jobId", ExistingWorkPolicy.KEEP, request(jobId, nextAttemptAt))
    suspend fun reconcilePendingJobs() {
        queue.pendingJobs().forEach { job ->
            val next = if (job.state == AiLinkJobState.RUNNING) maxOf(job.nextAttemptAt, job.leaseUntil ?: now()) else job.nextAttemptAt
            schedule(job.jobId, next).result.get()
        }
    }
}
