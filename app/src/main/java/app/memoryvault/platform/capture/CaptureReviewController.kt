package app.memoryvault.platform.capture

import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.domain.DriveState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface CaptureAudioPlayer {
    fun play(file: File, allowed: () -> Boolean, onEnded: (Boolean) -> Unit)
    fun close()
}

data class CaptureReviewState(val visible: Boolean = false, val items: List<CaptureArtifact> = emptyList(),
    val playingSession: String? = null, val failed: Boolean = false)

class CaptureReviewController(
    private val drive: () -> DriveSnapshot,
    private val load: suspend () -> List<CaptureArtifact>,
    private val playable: suspend (String) -> File,
    private val player: CaptureAudioPlayer,
) {
    private val mutableState = MutableStateFlow(CaptureReviewState())
    val state = mutableState.asStateFlow()
    fun contentAllowed(): Boolean = openedGeneration?.let { mutableState.value.visible && allowed(epoch, it) } == true
    private var foreground = false
    private var epoch = 0L
    private var playEpoch = 0L
    private var openedGeneration: Long? = null

    fun onStart() { foreground = true }
    fun onStop() { foreground = false; hide() }
    fun onDriveChanged() {
        val current = drive()
        if (current.state != DriveState.PARKED || (openedGeneration != null && openedGeneration != current.invalidationGeneration)) hide()
    }

    suspend fun open() {
        val current = drive()
        if (!foreground || current.state != DriveState.PARKED) return
        stop()
        val opening = ++epoch
        openedGeneration = current.invalidationGeneration
        try {
            val items = load()
            if (allowed(opening, current.invalidationGeneration)) mutableState.value = CaptureReviewState(visible = true, items = items)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (allowed(opening, current.invalidationGeneration)) mutableState.value = CaptureReviewState(visible = true, failed = true)
        }
    }

    suspend fun play(sessionId: String) {
        val generation = openedGeneration ?: return
        val opening = epoch
        if (!mutableState.value.visible || !allowed(opening, generation)) return
        val artifact = mutableState.value.items.firstOrNull { it.sessionId == sessionId } ?: return
        if (artifact.state in setOf(CaptureState.CAPTURING, CaptureState.INTERRUPTED, CaptureState.BROKEN)) return
        stop()
        val starting = ++playEpoch
        try {
            val file = playable(sessionId)
            if (!allowed(opening, generation) || starting != playEpoch) return
            mutableState.value = mutableState.value.copy(playingSession = sessionId, failed = false)
            player.play(file, {
                allowed(opening, generation) && starting == playEpoch && mutableState.value.playingSession == sessionId
            }, { success ->
                if (starting == playEpoch) {
                    stop()
                    if (!success && allowed(opening, generation)) mutableState.value = mutableState.value.copy(failed = true)
                }
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (allowed(opening, generation) && starting == playEpoch) {
                stop(); mutableState.value = mutableState.value.copy(failed = true)
            }
        }
    }

    fun stop() {
        playEpoch++
        player.close()
        mutableState.value = mutableState.value.copy(playingSession = null)
    }
    private fun hide() { epoch++; openedGeneration = null; stop(); mutableState.value = CaptureReviewState() }
    private fun allowed(opening: Long, generation: Long): Boolean = foreground && epoch == opening && drive().let {
        it.state == DriveState.PARKED && it.invalidationGeneration == generation
    }
}
