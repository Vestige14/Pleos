package app.memoryvault.share

import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import kotlinx.coroutines.flow.StateFlow

/** App-private metadata for a confirmed public image; never contains source photos or private notes. */
data class ShareExportReceipt(val uri: String, val displayName: String, val date: String)

interface ShareExportReceiptStore {
    val latest: StateFlow<ShareExportReceipt?>
    suspend fun save(receipt: ShareExportReceipt)
}

internal object ShareReceiptVisibility {
    // Composition observes the displayed snapshot; this non-UI boundary also checks the actual latest source.
    fun forCurrentDay(receipt: ShareExportReceipt?, date: String, displayed: DriveSnapshot,
        source: StateFlow<DriveSnapshot>, foreground: Boolean): ShareExportReceipt? =
        forDay(receipt, date, displayed, source.value, foreground)

    fun forDay(receipt: ShareExportReceipt?, date: String, displayed: DriveSnapshot,
        current: DriveSnapshot, foreground: Boolean): ShareExportReceipt? = receipt?.takeIf {
        foreground && it.date == date && displayed.state == DriveState.PARKED &&
            current.state == DriveState.PARKED && displayed.invalidationGeneration == current.invalidationGeneration
    }
}
