package app.memoryvault

import android.content.Context
import androidx.room.Room
import app.memoryvault.data.AppDatabase
import app.memoryvault.data.MemoryRepository
import app.memoryvault.data.NoteKind
import app.memoryvault.data.SeedLoader
import app.memoryvault.domain.DrivePolicy
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceMemoUseCase
import app.memoryvault.platform.AaosDriveStateSource
import app.memoryvault.platform.PleosSpeechInput
import app.memoryvault.platform.DriveSafetyBridge
import app.memoryvault.service.ReadyNotificationPolicy
import app.memoryvault.service.StorageReadiness
import app.memoryvault.service.VoiceNotifications
import app.memoryvault.service.VoiceServiceState
import app.memoryvault.service.VoiceSessionController
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

enum class TypedMemoResult { SAVED, BLOCKED, EMPTY, STORAGE_UNAVAILABLE, FAILED }

/** Application-owned dependencies. Holds application Context only, never an Activity. */
class AppContainer(context: Context, val captureClock: CaptureClock = ReplayCaptureClock) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)
    private val driveSource = AaosDriveStateSource(appContext)
    val driveSnapshot = driveSource.snapshot
    private val driveSafety = DriveSafetyBridge(driveSnapshot)
    val driveState = driveSafety.state
    val notifications = VoiceNotifications(appContext)
    private val preferences = appContext.getSharedPreferences("voice_setup", Context.MODE_PRIVATE)
    private val mutableReadyEnabled = MutableStateFlow(preferences.getBoolean("ready_enabled", false))
    val readyNotificationEnabled = mutableReadyEnabled.asStateFlow()
    private val mutableVoiceState = MutableStateFlow<VoiceServiceState>(VoiceServiceState.Idle)
    val voiceState = mutableVoiceState.asStateFlow()

    private val startup = RepositoryStartup {
        val database = Room.databaseBuilder(appContext, AppDatabase::class.java, "memory-vault.db").build()
        try {
            val result = MemoryRepository(database)
            val seed = appContext.assets.open("seed_day.json").bufferedReader().use { SeedLoader.parse(it.readText()) }
            result.seedIfEmpty(seed)
            result
        } catch (failure: Throwable) {
            runCatching { database.close() }
            throw failure
        }
    }
    val repository = startup.state
    val storageReadiness = startup.readiness
    private val voice = VoiceMemoUseCase(driveState, { id, text, capturedAt ->
        val ready = repository.value as? RepositoryState.Ready ?: error("Storage unavailable")
        ready.repository.saveVoiceMemo(id, text, capturedAt)
    }, driveSafety = driveSafety)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) { startup.initialize() }
        driveSource.start()
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

    suspend fun addTypedNote(visitId: String, text: String): TypedMemoResult {
        if (!DrivePolicy.canEdit(driveSnapshot.value.state)) return TypedMemoResult.BLOCKED
        if (text.isBlank()) return TypedMemoResult.EMPTY
        val ready = repository.value as? RepositoryState.Ready ?: return TypedMemoResult.STORAGE_UNAVAILABLE
        return try {
            if (ready.repository.addNote(visitId, text, NoteKind.TYPED, captureClock.now())) TypedMemoResult.SAVED
            else TypedMemoResult.EMPTY
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TypedMemoResult.FAILED
        }
    }

    internal fun newVoiceController() = VoiceSessionController(
        driveState, storageReadiness, voice, notifications::availability, driveSource::start,
        speechFactory = { PleosSpeechInput(appContext) }, captureClock = captureClock,
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
