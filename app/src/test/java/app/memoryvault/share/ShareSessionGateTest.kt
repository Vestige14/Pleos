package app.memoryvault.share

import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ShareSessionGateTest {
    @Test fun foregroundAndParkedAreRequiredAndStopInvalidatesOldTokens() {
        val snapshots = MutableStateFlow(DriveSnapshot())
        val gate = ShareSessionGate(snapshots)
        assertNull(gate.capture())
        gate.resume(); assertNull(gate.capture())
        snapshots.value = DriveSnapshot(state = DriveState.PARKED)
        val first = requireNotNull(gate.capture())
        assertTrue(gate.allows(first))
        gate.stop(); assertFalse(gate.allows(first)); assertNull(gate.capture())
        gate.resume(); assertFalse(gate.allows(first)); assertNotNull(gate.capture())
    }

    @Test fun shortUnknownRecoveryAndAllNonParkedStatesInvalidateOperations() {
        val snapshots = MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))
        val gate = ShareSessionGate(snapshots).apply { resume() }
        val token = requireNotNull(gate.capture())
        for (state in listOf(DriveState.TRANSITION, DriveState.DRIVING, DriveState.UNKNOWN)) {
            snapshots.value = DriveSnapshot(state = state)
            assertFalse(gate.allows(token))
        }
        snapshots.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 1)
        snapshots.value = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 1)
        assertFalse(gate.allows(token))
    }
}
