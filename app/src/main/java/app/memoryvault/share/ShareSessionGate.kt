package app.memoryvault.share

import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class ShareEpoch(val foreground: Long, val driveGeneration: Long)
interface ShareSafetyGate {
    val changes: Flow<Unit>
    fun capture(): ShareEpoch?
    fun allows(epoch: ShareEpoch): Boolean
}

/** Never restores a parked state; tokens are valid only in this foreground lifetime and generation. */
class ShareSessionGate(private val drive: StateFlow<DriveSnapshot>) : ShareSafetyGate {
    private val sequence = AtomicLong()
    private val foreground = MutableStateFlow<Long?>(null)
    override val changes: Flow<Unit> = combine(drive, foreground) { _, _ -> Unit }
    fun resume() { foreground.value = sequence.incrementAndGet() }
    fun stop() { foreground.value = null }
    override fun capture(): ShareEpoch? {
        val snapshot = drive.value
        val active = foreground.value ?: return null
        return if (snapshot.state == DriveState.PARKED) ShareEpoch(active, snapshot.invalidationGeneration) else null
    }
    override fun allows(epoch: ShareEpoch): Boolean = capture() == epoch
}
