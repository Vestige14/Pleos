package app.memoryvault.ui

import app.memoryvault.data.LinkState
import app.memoryvault.data.PhotoOperationBlocked
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.platform.PhotoAccess
import app.memoryvault.platform.PhotoPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PhotoScanStatus { IDLE, SCANNING, UPDATED, FAILED, STORAGE_UNAVAILABLE }
enum class PhotoReviewResult { SAVED, BLOCKED, NOT_EDITABLE, STORAGE_UNAVAILABLE, FAILED }
data class PhotoReviewState(
    val permissions: PhotoPermissions = PhotoPermissions(PhotoAccess.DENIED, false),
    val scanStatus: PhotoScanStatus = PhotoScanStatus.IDLE,
    val thumbnailEpoch: PhotoThumbnailEpoch? = null,
)

internal interface PhotoReviewStore {
    suspend fun sync(canApply: () -> Boolean)
    suspend fun decide(uri: String, state: LinkState, canApply: () -> Boolean): Boolean
}

internal class PhotoReviewController(
    private val drive: StateFlow<DriveSnapshot>,
    private val permissions: () -> PhotoPermissions,
    private val store: () -> PhotoReviewStore?,
    scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(PhotoReviewState())
    val state = mutableState.asStateFlow()
    fun thumbnailAllowed(epoch: PhotoThumbnailEpoch): Boolean = currentThumbnailEpoch() == epoch
    suspend fun <T> loadThumbnail(epoch: PhotoThumbnailEpoch, request: PhotoThumbnailRequest<T>): T? =
        thumbnailReads.load(epoch, request)
    private data class Request(val foreground: Boolean = false, val revision: Long = 0)
    private data class Trigger(val drive: DriveState, val generation: Long, val request: Request)
    private val requests = MutableStateFlow(Request())
    private val thumbnailReads = PhotoThumbnailLoader(::currentThumbnailEpoch,
        combine(drive.map { it.state to it.invalidationGeneration }.distinctUntilChanged(), requests) { _, _ -> Unit })

    init {
        scope.launch {
            combine(
                drive.map { it.state to it.invalidationGeneration }.distinctUntilChanged(), requests,
            ) { current, request -> Trigger(current.first, current.second, request) }.collectLatest { trigger ->
                mutableState.update { it.copy(thumbnailEpoch = currentThumbnailEpoch()) }
                if (!allowed(trigger)) {
                    mutableState.update { it.copy(scanStatus = PhotoScanStatus.IDLE) }
                    return@collectLatest
                }
                val ready = store()
                if (!allowed(trigger)) return@collectLatest
                if (ready == null) {
                    mutableState.update { it.copy(scanStatus = PhotoScanStatus.STORAGE_UNAVAILABLE) }
                    return@collectLatest
                }
                mutableState.update { it.copy(scanStatus = PhotoScanStatus.SCANNING) }
                try {
                    ready.sync { allowed(trigger) }
                    if (allowed(trigger)) mutableState.update { it.copy(scanStatus = PhotoScanStatus.UPDATED) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: PhotoOperationBlocked) {
                    mutableState.update { it.copy(scanStatus = PhotoScanStatus.IDLE) }
                } catch (_: Exception) {
                    mutableState.update { it.copy(scanStatus = if (allowed(trigger)) PhotoScanStatus.FAILED else PhotoScanStatus.IDLE) }
                } finally {
                    mutableState.update { if (it.scanStatus == PhotoScanStatus.SCANNING) it.copy(scanStatus = PhotoScanStatus.IDLE) else it }
                }
            }
        }
    }

    fun onResume() {
        requests.update { it.copy(foreground = true, revision = it.revision + 1) }
        mutableState.update { it.copy(permissions = permissions(), thumbnailEpoch = currentThumbnailEpoch()) }
    }

    fun onStop() {
        requests.update { it.copy(foreground = false, revision = it.revision + 1) }
        mutableState.update { it.copy(scanStatus = PhotoScanStatus.IDLE, thumbnailEpoch = null) }
    }

    fun refresh() {
        if (!requests.value.foreground || drive.value.state != DriveState.PARKED) return
        requests.update { it.copy(revision = it.revision + 1) }
        mutableState.update { it.copy(permissions = permissions(), thumbnailEpoch = currentThumbnailEpoch()) }
    }

    fun onStorageReady() = refresh()

    suspend fun decide(uri: String, state: LinkState): PhotoReviewResult {
        val current = drive.value
        val trigger = Trigger(current.state, current.invalidationGeneration, requests.value)
        if (!allowed(trigger) || permissions().access == PhotoAccess.DENIED) return PhotoReviewResult.BLOCKED
        return try {
            val ready = store() ?: return PhotoReviewResult.STORAGE_UNAVAILABLE
            if (!allowed(trigger)) return PhotoReviewResult.BLOCKED
            if (ready.decide(uri, state) { allowed(trigger) }) PhotoReviewResult.SAVED
            else if (!allowed(trigger)) PhotoReviewResult.BLOCKED else PhotoReviewResult.NOT_EDITABLE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: PhotoOperationBlocked) {
            PhotoReviewResult.BLOCKED
        } catch (_: Exception) {
            PhotoReviewResult.FAILED
        }
    }

    private fun allowed(trigger: Trigger): Boolean = trigger.request.foreground &&
        requests.value == trigger.request && trigger.drive == DriveState.PARKED &&
        drive.value.let { it.state == DriveState.PARKED && it.invalidationGeneration == trigger.generation }

    private fun currentThumbnailEpoch(): PhotoThumbnailEpoch? {
        val current = drive.value
        val request = requests.value
        if (!request.foreground || current.state != DriveState.PARKED || permissions().access == PhotoAccess.DENIED) return null
        return PhotoThumbnailEpoch(request.revision, current.invalidationGeneration)
    }
}
