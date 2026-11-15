package app.memoryvault.platform.capture

import org.junit.Assert.*
import org.junit.Test

class PcmEngineCleanupTest {
    @Test fun completedUtteranceDoesNotQueueAStopThatCanInterruptItsSuccessor() {
        val delayedStops = mutableListOf<() -> Unit>()
        var active = "first"
        val cleanup = PcmEngineCleanup({ delayedStops += { active = "stopped" } }, {}, {}, {})
        cleanup.completed()
        cleanup.close()
        active = "second"
        delayedStops.forEach { it() }
        assertEquals("second", active)
    }

    @Test fun unfinishedRecognitionStopsOnceAndAlwaysReleasesAndClearsListeners() {
        val calls = mutableListOf<String>()
        val cleanup = PcmEngineCleanup({ calls += "stop" }, { calls += "remove" }, { calls += "release" }, { calls += "clear" })
        cleanup.close(); cleanup.close()
        assertEquals(listOf("stop", "remove", "release", "clear"), calls)
        assertTrue(cleanup.closed)
    }

    @Test fun completedRecognitionStillReleasesAndReportsCleanupFailure() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("remove failed")
        val cleanup = PcmEngineCleanup({ calls += "stop" }, { calls += "remove"; throw failure }, { calls += "release" }, { calls += "clear" })
        cleanup.completed()
        assertSame(failure, assertThrows(IllegalStateException::class.java) { cleanup.close() })
        assertEquals(listOf("remove", "release", "clear"), calls)
        assertTrue(cleanup.closed)
    }
}
