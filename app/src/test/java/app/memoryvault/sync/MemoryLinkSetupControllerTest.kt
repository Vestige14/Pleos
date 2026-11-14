package app.memoryvault.sync

import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.sync.ui.*
import org.junit.Assert.*
import org.junit.Test

class MemoryLinkSetupControllerTest {
    private var drive = DriveSnapshot(DriveState.PARKED)
    private val saved = mutableListOf<Pair<String, String>>()
    private fun controller() = MemoryLinkSetupController({ drive }) { endpoint, token, allowed ->
        if (!allowed()) throw LinkSetupBlocked()
        saved += endpoint to token
    }
    @Test fun unknownDrivingAndStoppedNeverOpenOrSubmitAndInputsAreErased() {
        val c = controller()
        assertFalse(c.open()); c.onStart(); assertTrue(c.open())
        c.updateEndpoint("http://localhost:8000"); c.updateToken("x".repeat(48))
        drive = DriveSnapshot(DriveState.DRIVING); c.onDriveChanged()
        assertFalse(c.state.value.editing); assertEquals("", c.state.value.token); assertEquals("", c.state.value.endpoint)
        assertEquals(SetupNotice.BLOCKED, c.submit()); assertTrue(saved.isEmpty())
        drive = DriveSnapshot(DriveState.UNKNOWN, invalidationGeneration = 1); assertFalse(c.open())
        drive = DriveSnapshot(DriveState.PARKED, invalidationGeneration = 1); assertTrue(c.open())
        c.updateToken("x".repeat(48)); c.onStop()
        assertEquals("", c.state.value.token); assertFalse(c.state.value.editing)
        c.onStart(); assertFalse(c.state.value.editing)
    }
    @Test fun validInputsAreSavedOnlyFromCurrentGenerationThenHiddenWithoutTokenInStateString() {
        val c = controller(); c.onStart(); c.open()
        c.updateEndpoint("http://localhost:8000"); c.updateToken("x".repeat(48))
        assertFalse(c.state.value.toString().contains("x".repeat(48)))
        assertEquals(SetupNotice.SAVED, c.submit())
        assertEquals(listOf("http://localhost:8000" to "x".repeat(48)), saved)
        assertEquals("", c.state.value.token); assertFalse(c.state.value.editing)
    }
    @Test fun staleClickSeesLatestDriveSnapshotEvenBeforeCollectorNotifies() {
        val c = controller(); c.onStart(); c.open()
        c.updateEndpoint("http://localhost:8000"); c.updateToken("x".repeat(48))
        drive = DriveSnapshot(DriveState.PARKED, invalidationGeneration = 2)
        assertEquals(SetupNotice.BLOCKED, c.submit()); assertTrue(saved.isEmpty())
    }
    @Test fun stopDuringSaveInvalidatesCommitGuardAndNeverReportsSaved() {
        lateinit var c: MemoryLinkSetupController
        c = MemoryLinkSetupController({ drive }) { _, _, guard -> c.onStop(); assertFalse(guard()); throw LinkSetupBlocked() }
        c.onStart(); c.open(); c.updateEndpoint("http://localhost:8000"); c.updateToken("x".repeat(48))
        assertEquals(SetupNotice.BLOCKED, c.submit()); assertEquals("", c.state.value.token)
    }
    @Test fun invalidEndpointSessionAndStorageFailureUseFixedNoticesAndDoNotExposeExceptions() {
        val c = controller(); c.onStart(); c.open(); c.updateEndpoint("http://10.0.2.2:8000"); c.updateToken("x".repeat(48))
        assertEquals(SetupNotice.INVALID_ENDPOINT, c.submit()); assertTrue(saved.isEmpty())
        c.updateEndpoint("http://localhost:8000"); c.updateToken("short")
        assertEquals(SetupNotice.INVALID_SESSION, c.submit()); assertTrue(saved.isEmpty())
        val broken = MemoryLinkSetupController({ drive }) { _, _, _ -> throw LinkSessionException() }
        broken.onStart(); broken.open(); broken.updateEndpoint("http://localhost:8000"); broken.updateToken("x".repeat(48))
        assertEquals(SetupNotice.STORAGE_ERROR, broken.submit()); assertEquals("", broken.state.value.token)
    }
}
