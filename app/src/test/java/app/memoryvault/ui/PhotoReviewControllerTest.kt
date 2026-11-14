package app.memoryvault.ui

import app.memoryvault.data.LinkState
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.platform.PhotoAccess
import app.memoryvault.platform.PhotoPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoReviewControllerTest {
    private val full = PhotoPermissions(PhotoAccess.FULL, true)

    // Treating resume as parked would query private media before vehicle readiness.
    @Test fun onlyForegroundParkedStateStartsAScanAndResumeRefreshesPermissions() = runTest {
        val drive = MutableStateFlow(DriveSnapshot())
        val store = Store()
        var permissions = full
        val controller = PhotoReviewController(drive, { permissions }, { store }, backgroundScope)
        controller.onResume(); runCurrent()
        assertEquals(0, store.scans)
        drive.value = DriveSnapshot(state = DriveState.PARKED); runCurrent()
        assertEquals(1, store.scans)
        assertEquals(PhotoScanStatus.UPDATED, controller.state.value.scanStatus)
        controller.onStop(); runCurrent()
        controller.refresh(); runCurrent()
        assertEquals(1, store.scans)
        permissions = PhotoPermissions(PhotoAccess.PARTIAL, false)
        controller.onResume(); runCurrent()
        assertEquals(2, store.scans)
        assertEquals(permissions, controller.state.value.permissions)
    }

    @Test fun departureAndCollapsedUnknownRecoveryCancelOldScanAndInvalidateItsCommitGuard() = runTest {
        val drive = MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))
        val store = Store()
        var firstGuard: (() -> Boolean)? = null
        var cancelled = false
        store.onScan = { guard ->
            if (store.scans == 1) {
                firstGuard = guard
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        val controller = PhotoReviewController(drive, { full }, { store }, backgroundScope)
        controller.onResume(); runCurrent()
        assertEquals(PhotoScanStatus.SCANNING, controller.state.value.scanStatus)
        drive.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 1)
        drive.value = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 1)
        assertFalse(requireNotNull(firstGuard).invoke())
        runCurrent()
        assertTrue(cancelled)
        assertEquals(2, store.scans)
        controller.onStop(); runCurrent()
        assertEquals(PhotoScanStatus.IDLE, controller.state.value.scanStatus)
    }

    @Test fun actionsRequireForegroundAndCurrentParkedStateAndRecheckAfterGettingStore() = runTest {
        val drive = MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))
        val store = Store()
        var invalidate = false
        val controller = PhotoReviewController(drive, { full }, {
            if (invalidate) drive.value = DriveSnapshot(state = DriveState.DRIVING)
            store
        }, backgroundScope)
        assertEquals(PhotoReviewResult.BLOCKED, controller.decide("p", LinkState.CONFIRMED))
        controller.onResume(); runCurrent()
        assertEquals(PhotoReviewResult.SAVED, controller.decide("p", LinkState.CONFIRMED))
        assertEquals(listOf("p" to LinkState.CONFIRMED), store.decisions)
        invalidate = true
        assertEquals(PhotoReviewResult.BLOCKED, controller.decide("p", LinkState.REJECTED))
        assertEquals(1, store.decisions.size)
        for (state in listOf(DriveState.DRIVING, DriveState.UNKNOWN, DriveState.TRANSITION)) {
            drive.value = DriveSnapshot(state = state)
            assertEquals(PhotoReviewResult.BLOCKED, controller.decide("p", LinkState.REJECTED))
        }
    }

    @Test fun unavailableStorageCanBeRetriedAfterStartupAndDeniedMediaNeverAcceptsAConfirmation() = runTest {
        val drive = MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))
        var store: Store? = null
        var permissions = full
        val controller = PhotoReviewController(drive, { permissions }, { store }, backgroundScope)
        controller.onResume(); runCurrent()
        assertEquals(PhotoScanStatus.STORAGE_UNAVAILABLE, controller.state.value.scanStatus)
        val readyStore = Store()
        store = readyStore
        controller.onStorageReady(); runCurrent()
        assertEquals(1, readyStore.scans)
        permissions = PhotoPermissions(PhotoAccess.DENIED, false)
        controller.onResume(); runCurrent()
        assertEquals(PhotoReviewResult.BLOCKED, controller.decide("p", LinkState.CONFIRMED))
        assertTrue(readyStore.decisions.isEmpty())
    }

    @Test fun failuresAreClassifiedWithoutExposingPayloadAndCancellationPropagates() = runTest {
        val drive = MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))
        val store = Store().apply { onScan = { error("private media payload") } }
        val controller = PhotoReviewController(drive, { full }, { store }, backgroundScope)
        controller.onResume(); runCurrent()
        assertEquals(PhotoScanStatus.FAILED, controller.state.value.scanStatus)
        assertFalse(controller.state.value.toString().contains("private media"))
        store.onDecision = { throw CancellationException("cancel") }
        try { controller.decide("p", LinkState.REJECTED); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    @Test fun thumbnailEpochTracksForegroundGenerationAndExplicitRescanEvenWithUnchangedPhotos() = runTest {
        val drive = MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))
        val controller = PhotoReviewController(drive, { full }, { Store() }, backgroundScope)
        controller.onResume(); runCurrent()
        val first = controller.state.value.thumbnailEpoch
        assertNotNull("A parked foreground view needs a thumbnail epoch", first)
        assertTrue(controller.thumbnailAllowed(requireNotNull(first)))
        controller.refresh(); runCurrent()
        val refreshed = requireNotNull(controller.state.value.thumbnailEpoch)
        assertNotEquals(first, refreshed)
        assertFalse(controller.thumbnailAllowed(first))
        controller.onStop()
        assertFalse(controller.thumbnailAllowed(refreshed))
        controller.onResume(); runCurrent()
        val resumed = requireNotNull(controller.state.value.thumbnailEpoch)
        assertNotEquals(refreshed, resumed)
        drive.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 1)
        drive.value = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 1)
        assertFalse(controller.thumbnailAllowed(resumed))
        runCurrent()
        assertTrue(controller.thumbnailAllowed(requireNotNull(controller.state.value.thumbnailEpoch)))
    }

    private class Store : PhotoReviewStore {
        var scans = 0
        var onScan: suspend (() -> Boolean) -> Unit = {}
        var onDecision: suspend (() -> Boolean) -> Boolean = { true }
        val decisions = mutableListOf<Pair<String, LinkState>>()
        override suspend fun sync(canApply: () -> Boolean) { scans++; onScan(canApply) }
        override suspend fun decide(uri: String, state: LinkState, canApply: () -> Boolean): Boolean {
            val accepted = onDecision(canApply)
            if (accepted) decisions += uri to state
            return accepted
        }
    }
}
