package app.memoryvault.share

import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ShareReceiptVisibilityTest {
    private val receipt = ShareExportReceipt("content://media/external/images/media/41", "saved.png", "2026-11-08")
    private val parked = DriveSnapshot(state = DriveState.PARKED)

    @Test fun restoredReceiptIsVisibleForTheMatchingParkedDay() {
        assertEquals(receipt, ShareReceiptVisibility.forDay(receipt, "2026-11-08", parked, parked, true))
    }

    @Test fun anotherDayAndBackgroundCannotPresentAnOldSavedResult() {
        assertNull(ShareReceiptVisibility.forDay(receipt, "2026-11-09", parked, parked, true))
        assertNull(ShareReceiptVisibility.forDay(receipt, "2026-11-08", parked, parked, false))
        assertNull(ShareReceiptVisibility.forDay(null, "2026-11-08", parked, parked, true))
    }

    @Test fun nonParkedAndStaleGenerationCannotRevealReceiptFromLateCompletion() {
        for (state in listOf(DriveState.DRIVING, DriveState.UNKNOWN, DriveState.TRANSITION)) {
            val unsafe = DriveSnapshot(state = state)
            assertNull(ShareReceiptVisibility.forDay(receipt, "2026-11-08", parked, unsafe, true))
            assertNull(ShareReceiptVisibility.forDay(receipt, "2026-11-08", unsafe, parked, true))
        }
        assertNull(ShareReceiptVisibility.forDay(receipt, "2026-11-08", parked,
            parked.copy(invalidationGeneration = 1), true))
    }
    @Test fun lateReceiptRenderingRechecksTheActualSourceAfterSnapshotWasCaptured() {
        val actual = MutableStateFlow(parked)
        val currentReceipt = { ShareReceiptVisibility.forCurrentDay(receipt, "2026-11-08", parked, actual, true) }
        assertEquals(receipt, currentReceipt())
        actual.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 1)
        actual.value = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 1)
        assertNull(currentReceipt())
    }

}
