package app.memoryvault.memory

import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MemoryReviewBlocked : IllegalStateException("MEMORY_REVIEW_BLOCKED")
data class MemoryReviewEpoch(val foreground: Long, val generation: Long)

/** Foreground and generation are live capabilities, never restored from saved state. */
class MemoryReviewGate(private val drive: StateFlow<DriveSnapshot>) {
    private val sequence = AtomicLong()
    private val foreground = MutableStateFlow<MemoryReviewEpoch?>(null)
    fun start() { foreground.value = MemoryReviewEpoch(sequence.incrementAndGet(), drive.value.invalidationGeneration) }
    fun stop() { foreground.value = null }
    fun capture(): MemoryReviewEpoch? {
        val current = drive.value
        return foreground.value?.takeIf { current.state == DriveState.PARKED && it.generation == current.invalidationGeneration }
    }
    fun allows(epoch: MemoryReviewEpoch) = capture() == epoch
    fun require(epoch: MemoryReviewEpoch) { if (!allows(epoch)) throw MemoryReviewBlocked() }
}
