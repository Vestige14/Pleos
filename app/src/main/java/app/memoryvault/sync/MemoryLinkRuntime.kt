package app.memoryvault.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.WorkManager
import app.memoryvault.BuildConfig
import app.memoryvault.data.AiLinkQueue
import app.memoryvault.data.AppDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application DB must be ready before start; failures never turn local note storage into failure. */
object MemoryLinkRuntime {
    private val mutableProgress = MutableStateFlow(MemoryLinkProgress())
    val progress = mutableProgress.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<MemoryLinkExecutor>()
    @Volatile private var scheduler: MemoryLinkScheduler? = null
    @Volatile private var queue: AiLinkQueue? = null
    @Synchronized fun start(context: Context, db: AppDatabase) {
        if (scheduler != null) return
        try {
            val app = context.applicationContext
            val store = AiLinkQueue(db)
            val runner = MemoryLinkExecutor(store, HttpMemoryLinkApi(BuildConfig.MEMORY_LINK_LOCAL_HTTP), MemoryLinkSessionStore(app)) {
                val manager = app.getSystemService(ConnectivityManager::class.java)
                val network = manager?.activeNetwork
                network != null && manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }
            val scheduling = MemoryLinkScheduler(WorkManager.getInstance(app), store)
            queue = store; scheduler = scheduling; ready.complete(runner)
            scope.launch {
                try { db.aiLinkDao().observeJobs().collect { mutableProgress.value = MemoryLinkProgress.fromJobs(it) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableProgress.value = MemoryLinkProgress() }
            }
            reconcilePendingJobs(app)
        } catch (_: Exception) {
            // Startup remains recoverable; do not log exception text or touch the note transaction.
        }
    }
    fun reconcilePendingJobs(context: Context) {
        app.memoryvault.memory.MemoryAssociationRuntime.instance.value?.reconcile()
        scope.launch { try { scheduler?.reconcilePendingJobs() } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
    }
    fun onSessionUpdated(context: Context) {
        app.memoryvault.memory.MemoryAssociationRuntime.instance.value?.onSessionUpdated()
        scope.launch {
            try { queue?.resumeConfigurationBlocked(); scheduler?.reconcilePendingJobs() }
            catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    internal suspend fun executorOrNull(): MemoryLinkExecutor? = withTimeoutOrNull(5_000) { ready.await() }
}
