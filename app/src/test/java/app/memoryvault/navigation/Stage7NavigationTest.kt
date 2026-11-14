package app.memoryvault.navigation

import app.memoryvault.memorycontract.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Stage7NavigationTest {
    private class Sdk : NavigationSdk {
        lateinit var listener: NavigationSdk.Listener
        var connects = 0; var current = 0; var recent = 0; var closes = 0
        override fun connect(listener: NavigationSdk.Listener) { this.listener = listener; connects++ }
        override fun getDestinationInfo() { current++ }
        override fun getRecentDestinationInfo() { recent++ }
        override fun close() { closes++ }
    }
    private class Store : NavigationObservationStore {
        var rows = emptyList<NavigationEvidence>()
        var reads = 0
        override fun load(): List<NavigationEvidence> { reads++; return rows }
        override fun save(rows: List<NavigationEvidence>) { this.rows = rows }
    }
    private class Fixture {
        val sdk = Sdk(); val store = Store()
        val executor = ScheduledThreadPoolExecutor(1)
        var clock = 1_800_000_000_000L
        val source = PleosNavigationEvidenceSource({ sdk }, store, { clock }, executor)
        fun drain() { executor.submit {}.get(2, TimeUnit.SECONDS) }
        fun connect() { source.start(); drain(); sdk.listener.onConnected(); drain() }
        fun close() { source.close(); executor.awaitTermination(2, TimeUnit.SECONDS) }
    }
    @Test fun asyncConnectionGetsOnePairAndEmptyIsARealAvailableResponse() {
        val f = Fixture()
        try {
            assertTrue(f.source.snapshot().isEmpty()); assertEquals(0, f.store.reads)
            f.source.start(); f.source.start(); f.drain()
            assertEquals(1, f.sdk.connects); assertEquals(0, f.sdk.current)
            assertEquals(NavigationEvidenceStatus.NOT_READY, f.source.status())
            f.sdk.listener.onConnected(); f.drain()
            assertEquals(1, f.sdk.current); assertEquals(1, f.sdk.recent)
            assertEquals(NavigationEvidenceStatus.NOT_READY, f.source.status())
            f.sdk.listener.onRecentDestinationInfo(emptyList()); f.drain()
            assertEquals(NavigationEvidenceStatus.AVAILABLE, f.source.status())
        } finally { f.close() }
    }
    @Test fun actualCallbackClockAndKindsDoNotInventArrivalTimeOrCoordinates() {
        val f = Fixture()
        try {
            f.connect()
            val place = NavigationPlace("합성 해변", null, 0.0, 0.0)
            f.sdk.listener.onDestinationInfo(place); f.drain()
            f.sdk.listener.onRecentDestinationInfo(listOf(place)); f.drain()
            f.clock += 1000
            f.sdk.listener.onDestinationArrived(place); f.drain()
            val rows = f.source.snapshot()
            assertEquals(3, rows.size)
            assertTrue(rows.all { it.eventAtMillis == null })
            val arrival = rows.single { it.kind == NavigationEvidenceKind.ARRIVAL_OBSERVED }
            assertEquals(f.clock, arrival.observedAtMillis); assertNull(arrival.latitude); assertNull(arrival.longitude)
            assertEquals(0.0, rows.single { it.kind == NavigationEvidenceKind.DESTINATION_OBSERVED }.latitude!!, 0.0)
            f.sdk.listener.onDestinationArrived(place); f.drain(); assertEquals(3, f.source.snapshot().size)
        } finally { f.close() }
    }
    @Test fun changedAndCancelledRoutesDiscardLateCurrentWithoutMutatingRoutes() {
        val f = Fixture()
        try {
            f.connect()
            f.sdk.listener.onDestinationChanged(); f.drain()
            f.sdk.listener.onDestinationInfo(NavigationPlace("old", null, 1.0, 1.0)); f.drain()
            assertTrue(f.source.snapshot().isEmpty()); assertEquals(2, f.sdk.current)
            f.sdk.listener.onRouteCancelled(); f.drain()
            f.sdk.listener.onDestinationInfo(NavigationPlace("late", null, 1.0, 1.0)); f.drain()
            assertTrue(f.source.snapshot().isEmpty()); assertEquals(1, f.sdk.recent)
        } finally { f.close() }
    }
    @Test fun closeIsTerminalAndIgnoresLateCallbacks() {
        val f = Fixture(); f.connect(); f.close()
        val count = f.sdk.current
        f.sdk.listener.onConnected(); f.sdk.listener.onDestinationChanged()
        f.sdk.listener.onDestinationArrived(NavigationPlace("late", null, null, null)); f.source.start(); f.source.close()
        assertEquals(NavigationEvidenceStatus.UNAVAILABLE, f.source.status())
        assertTrue(f.source.snapshot().isEmpty()); assertEquals(count, f.sdk.current); assertEquals(1, f.sdk.closes)
    }
    @Test fun retentionAndCoordinateValidationStayBounded() {
        val now = 1_800_000_000_000L
        assertNull(NavigationRetention.normalize(NavigationPlace(" ", null, 0.0, 0.0)))
        val invalid = requireNotNull(NavigationRetention.normalize(NavigationPlace("place", null, Double.NaN, 0.0)))
        assertNull(invalid.latitude); assertNull(invalid.longitude)
        val rows = (0..300).map { NavigationEvidence("n$it", NavigationEvidenceKind.UNDATED_RECENT, "place", null,
            null, null, now - it, null) } + NavigationEvidence("future", NavigationEvidenceKind.ARRIVAL_OBSERVED,
            "place", null, null, null, now + 1, null)
        assertEquals(20, NavigationRetention.bound(rows, now).size)
        assertTrue(NavigationRetention.bound(rows, now + NavigationRetention.MAX_AGE_MILLIS + 1000).isEmpty())
    }
}
