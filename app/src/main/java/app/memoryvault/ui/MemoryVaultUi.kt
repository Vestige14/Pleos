package app.memoryvault.ui

import app.memoryvault.AppContainer
import app.memoryvault.DemoConfig
import app.memoryvault.RepositoryState
import app.memoryvault.TypedMemoResult
import app.memoryvault.data.DayDraft
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.service.VoiceMemoService
import app.memoryvault.service.VoiceServiceState
import app.memoryvault.service.VoiceStartRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

sealed interface DayDraftState {
    data object Loading : DayDraftState
    data class Ready(val draft: DayDraft) : DayDraftState
    data object Failed : DayDraftState
}

/** UI dependency boundary; vehicle and recording safety remain owned by the production sources. */
interface MemoryVaultUi {
    val driveSnapshot: StateFlow<DriveSnapshot>
    val day: Flow<DayDraftState>
    val voiceState: StateFlow<VoiceServiceState>
    val readyNotificationEnabled: StateFlow<Boolean>
    suspend fun addTypedNote(visitId: String, text: String): TypedMemoResult
    suspend fun updateTypedNote(noteId: Long, text: String): TypedMemoResult
    fun setReadyNotificationEnabled(enabled: Boolean): Boolean
    fun retryVehicleConnection()
    fun startVoice(): VoiceStartRequest
    fun cancelVoice()
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppUiBridge(
    private val container: AppContainer,
    private val requestVoice: () -> VoiceStartRequest,
) : MemoryVaultUi {
    override val driveSnapshot = container.driveSnapshot
    override val voiceState = container.voiceState
    override val readyNotificationEnabled = container.readyNotificationEnabled
    override val day: Flow<DayDraftState> = container.repository.flatMapLatest { state ->
        when (state) {
            RepositoryState.Loading -> flowOf(DayDraftState.Loading)
            RepositoryState.Failed -> flowOf(DayDraftState.Failed)
            is RepositoryState.Ready -> state.repository.observeDay(DemoConfig.DEMO_DATE.toString())
                .map<DayDraft, DayDraftState> { DayDraftState.Ready(it) }
                .catch { emit(DayDraftState.Failed) }
        }
    }
    override suspend fun addTypedNote(visitId: String, text: String) = container.addTypedNote(visitId, text)
    override suspend fun updateTypedNote(noteId: Long, text: String) = container.updateTypedNote(noteId, text)
    override fun setReadyNotificationEnabled(enabled: Boolean) = container.setReadyNotificationEnabled(enabled)
    override fun retryVehicleConnection() = container.retryVehicleConnection()
    override fun startVoice() = requestVoice()
    override fun cancelVoice() = VoiceMemoService.requestCancel()
}
