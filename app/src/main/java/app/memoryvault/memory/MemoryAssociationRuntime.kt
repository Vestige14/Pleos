package app.memoryvault.memory

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.withTransaction
import androidx.work.*
import app.memoryvault.BuildConfig
import app.memoryvault.data.*
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.AiLinkJobState
import app.memoryvault.memorycontract.NavigationEvidenceProvider
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.sync.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/** Application-owned instance, created only after DB migration/readiness; default provider is empty. */
class MemoryAssociationRuntime(
    context: Context, private val db: AppDatabase, private val drive: StateFlow<DriveSnapshot>,
    navigation: NavigationEvidenceProvider = EmptyNavigationEvidenceProvider,
    private val sessions: LinkSessionSource = MemoryLinkSessionStore(context.applicationContext),
    private val api: MemoryAssociationApi = HttpMemoryAssociationApi(BuildConfig.MEMORY_LINK_LOCAL_HTTP),
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    companion object {
        private val currentInstance = MutableStateFlow<MemoryAssociationRuntime?>(null)
        val instance: StateFlow<MemoryAssociationRuntime?> = currentInstance.asStateFlow()
    }
    private val app = context.applicationContext
    private val manager by lazy { WorkManager.getInstance(app) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean()
    private val preparationLock = Mutex()
    private val store = MemoryAssociationStore(db)
    private val queue = MemoryAssociationQueue(db, now)
    private val candidates = MemoryCandidates(db, navigation)
    private val images = MemoryPhotoSanitizer(app.contentResolver)
    private val descriptions = MemoryPhotoDescriptions(db, api, now)
    private val gate = MemoryReviewGate(drive)
    private var preparation: Job? = null
    private val mutableProgress = MutableStateFlow(MemoryAssociationProgress())
    val progress = mutableProgress.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        check(currentInstance.value == null || currentInstance.value === this) { "MEMORY_RUNTIME_ALREADY_STARTED" }
        currentInstance.value = this
        scope.launch {
            try {
                db.noteDao().observeAll().collect { notes ->
                    notes.filter { it.kind == NoteKind.VOICE }.forEach { store.register(it.id) }
                    reconcile()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableProgress.update { it.copy(errorCode = "STORAGE_UNAVAILABLE") } }
        }
        scope.launch {
            try {
                combine(db.memoryAssociationDao().observeStates(), db.memoryAssociationDao().observeLinks(),
                    db.noteLinkDao().observeAll(), db.aiLinkDao().observeJobs()) { _, _, _, _ -> Unit }.collect {
                    val items = store.items()
                    mutableProgress.update { it.copy(items = items, errorCode = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableProgress.update { it.copy(errorCode = "STORAGE_UNAVAILABLE") } }
        }
        scope.launch {
            drive.collect { value ->
                if (value.state != DriveState.PARKED || gate.capture() == null) endReview()
            }
        }
        reconcile()
    }
    fun beginReview(): MemoryReviewEpoch? {
        if (!started.get() || drive.value.state != DriveState.PARKED) return null
        gate.start()
        return gate.capture()?.also { reconcile() }
    }
    fun reviewAllowed(epoch: MemoryReviewEpoch) = gate.allows(epoch)
    fun endReview() { gate.stop(); preparation?.cancel(); preparation = null }

    /** New note/DB-ready calls recover scheduling only. No paid photo work starts without a live review epoch. */
    fun reconcile() {
        if (!started.get()) return
        scope.launch {
            try { queue.pending().forEach { schedule(it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableProgress.update { it.copy(errorCode = "SCHEDULING_FAILED") } }
        }
        val epoch = gate.capture() ?: return
        synchronized(this) {
            if (preparation?.isActive == true) return
            preparation = scope.launch {
                preparationLock.withLock {
                    mutableProgress.update { it.copy(preparing = true) }
                    try {
                        store.items().filter { it.state.status == "WAITING" && it.state.activeJobId == null && !it.userLocked }.forEach {
                            gate.require(epoch)
                            prepare(it, epoch)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: MemoryReviewBlocked) { }
                    catch (_: Exception) { mutableProgress.update { it.copy(errorCode = "PREPARATION_FAILED") } }
                    finally { mutableProgress.update { it.copy(preparing = false) } }
                }
            }
        }
    }
    fun onSessionUpdated() {
        scope.launch {
            try {
                queue.sessionUpdated()
                db.withTransaction {
                    db.memoryAssociationDao().states().filter { it.activeJobId == null &&
                        it.errorCode in setOf("CONFIG_REQUIRED", "SESSION_STORAGE", "UNAUTHORIZED") }.forEach {
                        db.memoryAssociationDao().saveState(it.copy(status = "WAITING", errorCode = null))
                    }
                }
                reconcile()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableProgress.update { it.copy(errorCode = "SESSION_STORAGE") } }
        }
    }
    private suspend fun prepare(item: MemoryReviewItem, epoch: MemoryReviewEpoch) {
        try {
            val batch = candidates.batch(item.note, item.state)
            val issues = batch.issues.toMutableSet()
            val prepared = mutableListOf<Pair<Int, MemorySource>>()
            var session: LinkSession? = null
            for ((index, source) in batch.sources.withIndex()) {
                gate.require(epoch)
                if (source.candidate.kind != MemoryKind.PHOTO) { prepared += index to source; continue }
                try {
                    val image = images.prepare(source.localRef) { gate.allows(epoch) }
                    val description = try {
                        descriptions.cached(image.sha256) ?: run {
                            if (!connected()) {
                                store.preparationError(item.note.id, item.userRevision, "NETWORK_UNAVAILABLE"); return
                            }
                            if (session == null) session = sessions.load() ?: run {
                                store.preparationError(item.note.id, item.userRevision, "CONFIG_REQUIRED"); return
                            }
                            descriptions.description(image, checkNotNull(session)) { gate.allows(epoch) }
                        }
                    }
                        finally { image.bytes.fill(0) }
                    gate.require(epoch)
                    prepared += index to source.copy(candidate = source.candidate.copy(description = description))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (blocked: MemoryReviewBlocked) { throw blocked }
                catch (failure: PhotoPreparationException) { issues += "${failure.code}@${source.candidate.id}" }
            }
            var next = batch.nextOffset
            var more = batch.hasMore
            fun snapshot() = AssociationSnapshot(batch.scope, prepared.map { it.second })
            // Whole NOTE candidates survive or are deferred intact; no text/Unicode truncation.
            while (true) {
                val value = snapshot()
                val request = AssociationRequest("00000000-0000-0000-0000-000000000000", item.note.revision, item.userRevision,
                    MemoryWire.contextHash(value), MemoryWire.timestamp(item.note.capturedAt), item.note.text, batch.scope, value.sources.map { it.candidate })
                val bytes = MemoryWire.json.encodeToString(request).toByteArray(Charsets.UTF_8).size
                if (bytes <= 65_536) break
                if (prepared.isEmpty()) { store.preparationError(item.note.id, item.userRevision, "NOTE_TOO_LARGE"); return }
                val removed = prepared.removeAt(prepared.lastIndex)
                next = item.state.pageOffset + removed.first
                more = true; issues += "MORE_CANDIDATES"
            }
            gate.require(epoch)
            val id = queue.enqueue(item.note, item.userRevision, snapshot(), next, more, issues) { gate.allows(epoch) }
            id?.let { db.aiLinkDao().getJob(it)?.let { job -> schedule(job) } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (blocked: MemoryReviewBlocked) { throw blocked }
        catch (_: LinkSessionException) { store.preparationError(item.note.id, item.userRevision, "SESSION_STORAGE") }
        catch (_: Exception) { store.preparationError(item.note.id, item.userRevision, "PREPARATION_FAILED") }
    }
    internal suspend fun execute(jobId: String): WorkExecution {
        if (!connected()) return WorkExecution.RETRY
        val session = try { sessions.load() } catch (_: Exception) {
            queue.configuration(jobId, LinkError.SESSION_STORAGE); return WorkExecution.FINISHED
        } ?: run { queue.configuration(jobId, LinkError.CONFIG_REQUIRED); return WorkExecution.FINISHED }
        val lease = queue.claim(jobId) ?: return queue.nextExecution(jobId)
        try {
            if (!connected()) { queue.cancel(lease); return WorkExecution.RETRY }
            if (!queue.begin(lease)) return queue.nextExecution(jobId)
            when (val outcome = api.link(lease.request, session)) {
                is MemoryApiResult.Success -> queue.apply(jobId, lease.token, outcome.value)
                is MemoryApiResult.Failed -> queue.fail(jobId, lease.token, outcome.failure)
            }
            return queue.nextExecution(jobId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { queue.cancel(lease) }
            throw cancelled
        } catch (_: Exception) {
            queue.fail(jobId, lease.token, LinkFailure(503, LinkError.OUTCOME_UNKNOWN, false))
            return queue.nextExecution(jobId)
        }
    }
    suspend fun decide(item: MemoryReviewItem, action: MemoryReviewAction, selected: Set<String>, epoch: MemoryReviewEpoch) = guardedAction {
        store.decide(item.note.id, item.note.revision, item.userRevision, action, selected) { gate.allows(epoch) }
    }
    suspend fun manualChoices(item: MemoryReviewItem, epoch: MemoryReviewEpoch): List<MemorySource> {
        gate.require(epoch)
        val batch = candidates.batch(item.note, item.state.copy(pageOffset = 0), includeRejected = true, forManualSelection = true)
        gate.require(epoch)
        return (batch.sources + item.links.map { it.source }).distinctBy { it.candidate.id }
    }
    suspend fun correct(item: MemoryReviewItem, selectedIds: Set<String>, epoch: MemoryReviewEpoch) = guardedAction {
        val choices = manualChoices(item, epoch)
        store.correct(item.note.id, item.note.revision, item.userRevision, choices, selectedIds,
            0, item.note.text.codePointCount(0, item.note.text.length)) { gate.allows(epoch) }
    }
    suspend fun reanalyze(item: MemoryReviewItem, nextPage: Boolean, date: String?, epoch: MemoryReviewEpoch) = guardedAction {
        store.reanalyze(item.note.id, item.note.revision, item.userRevision, nextPage, date) { gate.allows(epoch) }
            .also { if (it == MemoryReviewResult.SAVED) reconcile() }
    }
    private suspend fun guardedAction(action: suspend () -> MemoryReviewResult): MemoryReviewResult = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: MemoryReviewBlocked) { MemoryReviewResult.BLOCKED }
        catch (_: Exception) { MemoryReviewResult.FAILED }
    private fun connected(): Boolean {
        val network = app.getSystemService(ConnectivityManager::class.java)
        return network?.activeNetwork?.let { network.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
    }
    private fun schedule(job: AiLinkJob) {
        val deadline = if (job.state == AiLinkJobState.RUNNING) maxOf(job.nextAttemptAt, job.leaseUntil ?: now()) else job.nextAttemptAt
        val work = OneTimeWorkRequestBuilder<MemoryAssociationWorker>().setInputData(workDataOf("jobId" to job.jobId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay((deadline - now()).coerceIn(0, TimeUnit.DAYS.toMillis(1)), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniqueWork("memory-v2-${job.jobId}", ExistingWorkPolicy.KEEP, work)
    }
    override fun close() {
        endReview(); scope.cancel(); started.set(false)
        if (currentInstance.value === this) currentInstance.value = null
    }
}
