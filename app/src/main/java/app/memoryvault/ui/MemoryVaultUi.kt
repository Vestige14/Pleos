package app.memoryvault.ui

import app.memoryvault.sync.MemoryLinkProgress
import app.memoryvault.sync.MemoryLinkRuntime
import app.memoryvault.AppContainer
import app.memoryvault.DemoConfig
import app.memoryvault.RepositoryState
import app.memoryvault.TypedMemoResult
import app.memoryvault.data.DayDraft
import app.memoryvault.data.LinkState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.domain.VoiceCaptureTiming
import app.memoryvault.service.VoiceMemoService
import app.memoryvault.service.VoiceServiceState
import app.memoryvault.service.VoiceStartRequest
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
    val aiProgress: StateFlow<MemoryLinkProgress> get() = MemoryLinkProgress.unavailable
    val driveSnapshot: StateFlow<DriveSnapshot>
    val recordDates: Flow<List<String>> get() = flowOf(emptyList())
    fun selectRecordDate(date: String) {}
    val day: Flow<DayDraftState>
    val voiceState: StateFlow<VoiceServiceState>
    val voiceTiming: StateFlow<VoiceCaptureTiming> get() = VoiceCaptureTiming.EMPTY
    val readyNotificationEnabled: StateFlow<Boolean>
    val photoReview: StateFlow<PhotoReviewState>
    fun thumbnailAllowed(epoch: PhotoThumbnailEpoch): Boolean
    suspend fun loadPhotoThumbnail(uri: String, epoch: PhotoThumbnailEpoch): ImageBitmap?
    fun refreshPhotos()
    suspend fun setPhotoState(uri: String, state: LinkState): PhotoReviewResult
    suspend fun addTypedNote(visitId: String, text: String): TypedMemoResult
    suspend fun updateTypedNote(noteId: Long, text: String): TypedMemoResult
    fun setReadyNotificationEnabled(enabled: Boolean): Boolean
    fun retryVehicleConnection()
    fun startVoice(): VoiceStartRequest
    fun cancelVoice()
    fun finishVoice(): Boolean = false
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppUiBridge(
    private val container: AppContainer,
    private val requestVoice: () -> VoiceStartRequest,
) : MemoryVaultUi {
    override val aiProgress = MemoryLinkRuntime.progress
    override val driveSnapshot = container.driveSnapshot
    override val voiceState = container.voiceState
    override val voiceTiming = container.voiceTiming
    override val readyNotificationEnabled = container.readyNotificationEnabled
    override val photoReview = container.photoReview
    override fun thumbnailAllowed(epoch: PhotoThumbnailEpoch) = container.thumbnailAllowed(epoch)
    override suspend fun loadPhotoThumbnail(uri: String, epoch: PhotoThumbnailEpoch): ImageBitmap? =
        container.loadPhotoThumbnail(uri, epoch)?.asImageBitmap()
    override fun refreshPhotos() = container.refreshPhotos()
    override suspend fun setPhotoState(uri: String, state: LinkState) = container.setPhotoState(uri, state)
    override fun selectRecordDate(date: String) = container.selectRecordDate(date)
    override val recordDates: Flow<List<String>> = container.repository.flatMapLatest { state ->
        if (state is RepositoryState.Ready) state.repository.observeDates() else flowOf(emptyList())
    }
    override val day: Flow<DayDraftState> = container.repository.flatMapLatest { state ->
        when (state) {
            RepositoryState.Loading -> flowOf(DayDraftState.Loading)
            RepositoryState.Failed -> flowOf(DayDraftState.Failed)
            is RepositoryState.Ready -> container.recordDate.flatMapLatest { date ->
                state.repository.observeDay(date)
                    .map<DayDraft, DayDraftState> { DayDraftState.Ready(it) }
                    .catch { emit(DayDraftState.Failed) }
            }
        }
    }
    override suspend fun addTypedNote(visitId: String, text: String) = container.addTypedNote(visitId, text)
    override suspend fun updateTypedNote(noteId: Long, text: String) = container.updateTypedNote(noteId, text)
    override fun setReadyNotificationEnabled(enabled: Boolean) = container.setReadyNotificationEnabled(enabled)
    override fun retryVehicleConnection() = container.retryVehicleConnection()
    override fun startVoice() = requestVoice()
    override fun cancelVoice() = VoiceMemoService.requestCancel()
    override fun finishVoice() = VoiceMemoService.requestFinish()
}
