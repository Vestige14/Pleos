package app.memoryvault.platform

import app.memoryvault.domain.DriveState
import app.memoryvault.domain.VoiceDriveSafety
import app.memoryvault.domain.VoiceDriveSnapshot
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Authoritative reads plus latched invalidation; UI collection may still be queued. */
internal class DriveSafetyBridge(private val snapshots: StateFlow<DriveSnapshot>) : VoiceDriveSafety {
    @OptIn(InternalCoroutinesApi::class)
    val state: StateFlow<DriveState> = object : StateFlow<DriveState> {
        override val value: DriveState get() = snapshots.value.state
        override val replayCache: List<DriveState> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<DriveState>): Nothing =
            snapshots.collect(object : FlowCollector<DriveSnapshot> {
                override suspend fun emit(value: DriveSnapshot) { collector.emit(value.state) }
            })
    }
    override fun current(): VoiceDriveSnapshot = snapshots.value.let { VoiceDriveSnapshot(it.state, it.invalidationGeneration) }
    override suspend fun awaitInvalidation(generation: Long) {
        snapshots.first { it.state == DriveState.UNKNOWN || it.invalidationGeneration != generation }
    }
}
