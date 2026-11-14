package app.memoryvault.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.memoryvault.sync.WorkExecution
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

class MemoryAssociationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("jobId")?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val runtime = withTimeoutOrNull(5_000) { MemoryAssociationRuntime.instance.filterNotNull().first() } ?: return Result.retry()
        return when (runtime.execute(id)) { WorkExecution.FINISHED -> Result.success(); WorkExecution.RETRY -> Result.retry() }
    }
}
