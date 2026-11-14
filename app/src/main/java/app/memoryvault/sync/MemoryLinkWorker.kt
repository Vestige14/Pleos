package app.memoryvault.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class MemoryLinkWorker(context: Context, parameters: WorkerParameters,
    private val executor: MemoryLinkExecutor?) : CoroutineWorker(context, parameters) {
    // WorkManager's default factory requires this exact JVM constructor.
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters, null)
    override suspend fun doWork(): Result {
        val jobId = inputData.getString("jobId")?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val runner = executor ?: MemoryLinkRuntime.executorOrNull() ?: return Result.retry()
        return when (runner.run(jobId)) { WorkExecution.FINISHED -> Result.success(); WorkExecution.RETRY -> Result.retry() }
    }
}
