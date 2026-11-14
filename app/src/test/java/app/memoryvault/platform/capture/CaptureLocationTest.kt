package app.memoryvault.platform.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureLocationTest {
    private val now = CaptureMoment(100_000, 60_000_000_000)
    private fun fix(lat: Double = 0.0, lng: Double = 0.0, time: Long = 95_000,
        elapsed: Long = 55_000_000_000, accuracy: Float? = 4f, provider: String = "gps") =
        LocationFix(lat, lng, provider, time, elapsed, accuracy)

    @Test fun freshZeroCoordinatesAreRealLocationWithProvenance() {
        assertEquals(CaptureLocation(0.0, 0.0, "gps", 95_000, 100_000, 4f),
            FreshCaptureLocation.select(listOf(fix()), now))
    }

    @Test fun absentStaleFutureAndInvalidFixesMeanLocationMissing() {
        val invalid = listOf(fix(time = 69_999), fix(elapsed = 29_999_999_999), fix(time = 100_001),
            fix(elapsed = 60_000_000_001), fix(lat = 91.0), fix(lng = Double.NaN),
            fix(accuracy = -1f), fix(accuracy = Float.NaN), fix(provider = ""), fix(time = 0))
        assertNull(FreshCaptureLocation.select(emptyList(), now))
        invalid.forEach { assertNull("Invalid location accepted: $it", FreshCaptureLocation.select(listOf(it), now)) }
    }

    @Test fun exactlyThirtySecondsIsAcceptedButNoAccuracyIsExplicitlyNullable() {
        assertEquals(CaptureLocation(0.0, 0.0, "gps", 70_000, 100_000, null),
            FreshCaptureLocation.select(listOf(fix(time = 70_000, elapsed = 30_000_000_000, accuracy = null)), now))
    }

    @Test fun newestEligibleFixWinsAndNoPreviousSessionLocationLeaks() {
        assertEquals("network", FreshCaptureLocation.select(listOf(fix(), fix(provider = "network", time = 99_000, elapsed = 59_000_000_000)), now)!!.source)
        assertNull(FreshCaptureLocation.select(emptyList(), CaptureMoment(110_000, 70_000_000_000)))
    }
}
