package app.memoryvault

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.core.net.toUri
import androidx.room.Room
import app.memoryvault.sync.MemoryLinkRuntime
import app.memoryvault.navigation.PleosNavigationEvidenceSource
import app.memoryvault.data.AppDatabase
import app.memoryvault.data.MemoryRepository
import app.memoryvault.data.MediaStorePhotoSource
import app.memoryvault.data.LinkState
import app.memoryvault.data.NoteKind
import app.memoryvault.data.SeedLoader
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceMemoUseCase
import app.memoryvault.platform.AaosDriveStateSource
import app.memoryvault.platform.capture.CaptureRuntime
import app.memoryvault.platform.capture.CaptureNote
import app.memoryvault.platform.DriveSafetyBridge
import app.memoryvault.platform.readPhotoPermissions
import app.memoryvault.platform.AndroidPhotoThumbnailRequest
import app.memoryvault.service.ReadyNotificationPolicy
import app.memoryvault.service.StorageReadiness
import app.memoryvault.service.VoiceNotifications
import app.memoryvault.service.VoiceServiceState
import app.memoryvault.service.VoiceSessionController
import app.memoryvault.ui.TypedMemoStore
import app.memoryvault.ui.TypedMemoWriter
import app.memoryvault.ui.PhotoReviewController
import app.memoryvault.ui.PhotoReviewStore
import app.memoryvault.ui.PhotoThumbnailEpoch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface RepositoryState {
    data object Loading : RepositoryState
    data class Ready(val repository: MemoryRepository) : RepositoryState
    data object Failed : RepositoryState
}

internal class RepositoryStartup(private val load: suspend () -> MemoryRepository) {
    private val mutableState = MutableStateFlow<RepositoryState>(RepositoryState.Loading)
    val state = mutableState.asStateFlow()
    private val mutableReadiness = MutableStateFlow(StorageReadiness.LOADING)
    val readiness = mutableReadiness.asStateFlow()
    private val started = AtomicBoolean(false)
    suspend fun initialize() {
        if (!started.compareAndSet(false, true)) return
        try {
            mutableState.value = RepositoryState.Ready(load())
            mutableReadiness.value = StorageReadiness.READY
        } catch (cancelled: CancellationException) {
            mutableState.value = RepositoryState.Failed
            mutableReadiness.value = StorageReadiness.FAILED
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = RepositoryState.Failed
            mutableReadiness.value = StorageReadiness.FAILED
        }
    }
}

enum class TypedMemoResult { SAVED, BLOCKED, EMPTY, STORAGE_UNAVAILABLE, NOT_EDITABLE, FAILED }

/** Application-owned dependencies. Holds application Context only, never an Activity. */
class AppContainer(context: Context, val captureClock: CaptureClock = ReplayCaptureClock) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)
    private val driveSource = AaosDriveStateSource(appContext)
    val navigationEvidence = PleosNavigationEvidenceSource(appContext)
    val driveSnapshot = driveSource.snapshot
    private val driveSafety = DriveSafetyBridge(driveSnapshot)
    val driveState = driveSafety.state
    val notifications = VoiceNotifications(appContext)
    private val preferences = appContext.getSharedPreferences("voice_setup", Context.MODE_PRIVATE)
    private val mutableReadyEnabled = MutableStateFlow(preferences.getBoolean("ready_enabled", false))
    val readyNotificationEnabled = mutableReadyEnabled.asStateFlow()
    private val mutableVoiceState = MutableStateFlow<VoiceServiceState>(VoiceServiceState.Idle)
    val voiceState = mutableVoiceState.asStateFlow()

    private val startup: RepositoryStartup = RepositoryStartup {
        val database = Room.databaseBuilder(appContext, AppDatabase::class.java, "memory-vault.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, app.memoryvault.memory.MemoryAssociationMigration.FROM_2_TO_3).build()
        try {
            val result = MemoryRepository(database, MediaStorePhotoSource(appContext) {
                driveSnapshot.value.state == DriveState.PARKED
            }, enqueueVoiceLink = { noteId -> app.memoryvault.memory.MemoryAssociationStore(database).register(noteId) })
            val seed = appContext.assets.open("seed_day.json").bufferedReader().use { SeedLoader.parse(it.readText()) }
            result.seedIfEmpty(seed)
            // User-authorized local filming preparation; absent from release assets.
            if ("ham_story_seed.json" in appContext.assets.list("").orEmpty()) scope.launch(Dispatchers.IO) {
                driveSnapshot.first { it.state == DriveState.PARKED }
                val generation = driveSnapshot.value.invalidationGeneration
                fun allowed() = driveSnapshot.value.let { it.state == DriveState.PARKED && it.invalidationGeneration == generation }
                try {
                    app.memoryvault.data.ScenarioPreparation.apply(appContext, database, ::allowed)
                    if (allowed()) refreshPhotos()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Preserve existing records; no retry or credential changes. */ }
            }
            try {
                CaptureRuntime.recover(appContext) { session ->
                    database.noteDao().getByVoiceSession(session)?.let { CaptureNote(it.id, it.text, it.capturedAt) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Private capture screen reports a broken index; existing DB stays available. */ }
            app.memoryvault.memory.MemoryAssociationRuntime(appContext, database, driveSnapshot,
                navigation = navigationEvidence).start()
            MemoryLinkRuntime.start(appContext, database)
            result
        } catch (failure: Throwable) {
            runCatching { database.close() }
            throw failure
        }
    }
    private val mutableRecordDate = MutableStateFlow(DemoConfig.DEMO_DATE.toString())
    val recordDate = mutableRecordDate.asStateFlow()
    fun selectRecordDate(date: String) {
        if (driveSnapshot.value.state == DriveState.PARKED) {
            runCatching { java.time.LocalDate.parse(date) }.onSuccess { mutableRecordDate.value = it.toString() }
        }
    }
    val repository = startup.state
    val storageReadiness = startup.readiness
    private val typed = TypedMemoWriter({ driveSnapshot.value.state }) {
        (repository.value as? RepositoryState.Ready)?.repository?.let { ready ->
            object : TypedMemoStore {
                override suspend fun add(visitId: String, text: String): Boolean =
                    ready.addNote(visitId, text, NoteKind.TYPED, captureClock.now())
                override suspend fun update(noteId: Long, text: String): Boolean =
                    ready.updateTypedNote(noteId, text)
            }
        }
    }
    private val voice = VoiceMemoUseCase(driveState, { id, text, capturedAt ->
        val ready = repository.value as? RepositoryState.Ready ?: error("Storage unavailable")
        ready.repository.saveVoiceMemo(id, text, capturedAt).also { MemoryLinkRuntime.reconcilePendingJobs(appContext) }
    }, driveSafety = driveSafety)
    val voiceTiming get() = voice.timing
    private val photos = PhotoReviewController(driveSnapshot, { readPhotoPermissions(appContext) }, {
        (repository.value as? RepositoryState.Ready)?.repository?.let { ready ->
            object : PhotoReviewStore {
                override suspend fun sync(canApply: () -> Boolean) = ready.syncPhotos(DemoConfig.DEMO_DATE.toString(), canApply)
                override suspend fun decide(uri: String, state: LinkState, canApply: () -> Boolean) =
                    ready.setPhotoState(uri, state, canApply)
            }
        }
    }, scope)
    val photoReview = photos.state

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) { startup.initialize() }
        driveSource.start()
        navigationEvidence.start()
        scope.launch { repository.collect { if (it is RepositoryState.Ready) photos.onStorageReady() } }
        scope.launch {
            combine(readyNotificationEnabled, driveState, voiceState) { _, _, _ -> Unit }.collect {
                refreshReadyNotification()
            }
        }
        scope.launch {
            while (isActive) {
                if (readyNotificationEnabled.value) refreshReadyNotification()
                delay(1_000L)
            }
        }
    }

    /** Enabling is intentional parked setup; disabling is always available. */
    fun setReadyNotificationEnabled(enabled: Boolean): Boolean {
        if (enabled && driveSnapshot.value.state != DriveState.PARKED) return false
        mutableReadyEnabled.value = enabled
        preferences.edit().putBoolean("ready_enabled", enabled).apply()
        refreshReadyNotification()
        return true
    }

    suspend fun addTypedNote(visitId: String, text: String): TypedMemoResult = typed.add(visitId, text)

    suspend fun updateTypedNote(noteId: Long, text: String): TypedMemoResult = typed.update(noteId, text)

    fun onPhotoResume() = photos.onResume()
    fun onPhotoStop() = photos.onStop()
    fun refreshPhotos() = photos.refresh()
    suspend fun setPhotoState(uri: String, state: LinkState) = photos.decide(uri, state)
    fun thumbnailAllowed(epoch: PhotoThumbnailEpoch) = photos.thumbnailAllowed(epoch)
    suspend fun loadPhotoThumbnail(uri: String, epoch: PhotoThumbnailEpoch): Bitmap? =
        photos.loadThumbnail(epoch, AndroidPhotoThumbnailRequest { signal ->
            appContext.contentResolver.loadThumbnail(uri.toUri(), Size(400, 300), signal)
        })

    /** Explicit retry after external vehicle-permission setup; never fabricates readiness. */
    fun retryVehicleConnection() { driveSource.start() }

    internal fun newVoiceController() = VoiceSessionController(
        driveState, storageReadiness, voice, notifications::availability, driveSource::start,
        speechFactory = { CaptureRuntime.newSpeechInput(appContext) },
        captureClock = CaptureClock { System.currentTimeMillis() },
    )

    internal fun publishVoiceState(state: VoiceServiceState) { mutableVoiceState.value = state }

    internal fun refreshReadyNotification() {
        val busy = when (voiceState.value) {
            VoiceServiceState.CheckingVehicle, VoiceServiceState.Preparing,
            VoiceServiceState.Listening, VoiceServiceState.Processing -> true
            else -> false
        }
        val state = ReadyNotificationPolicy.state(
            readyNotificationEnabled.value, driveSnapshot.value.state, notifications.availability(), busy,
        )
        notifications.ready(state)
    }
}
