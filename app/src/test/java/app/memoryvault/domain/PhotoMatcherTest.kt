package app.memoryvault.domain

import app.memoryvault.data.PhotoMeta
import app.memoryvault.data.Visit
import org.junit.Assert.*
import org.junit.Test

class PhotoMatcherTest {
    private val visit = Visit("v1", "2026-11-08", "장소", 0.0, 0.0, 100L, 200L)
    private fun photo(time: Long? = 150L, lat: Double? = 0.0, lng: Double? = 0.0) =
        PhotoMeta("content://photo", time, lat, lng)

    // Exclusive interval/radius checks would reject the documented boundary photos.
    @Test fun intervalIncludesBothEnds() {
        assertEquals("v1", PhotoMatcher.match(photo(time = 100), listOf(visit)))
        assertEquals("v1", PhotoMatcher.match(photo(time = 200), listOf(visit)))
        assertNull(PhotoMatcher.match(photo(time = 99), listOf(visit)))
        assertNull(PhotoMatcher.match(photo(time = 201), listOf(visit)))
    }

    @Test fun radiusIncludes300MetersAndRejectsBeyond() {
        assertEquals(300.0, distanceMeters(0.0, 0.0, 0.002697964817756191, 0.0), 0.000001)
        assertEquals("v1", PhotoMatcher.match(photo(lat = 0.002697964817756191), listOf(visit)))
        assertNull(PhotoMatcher.match(photo(lat = 0.0026981), listOf(visit)))
        assertNull(PhotoMatcher.match(photo(lat = 1.0), listOf(visit)))
    }

    @Test fun overlapUsesArrivalThenIdRegardlessOfInputOrder() {
        val late = visit.copy(id = "a", arrivedAt = 120)
        val firstB = visit.copy(id = "b")
        assertEquals("v1", PhotoMatcher.match(photo(), listOf(late, visit)))
        assertEquals("b", PhotoMatcher.match(photo(), listOf(visit, firstB)))
    }

    @Test fun missingInvalidCoordinatesOrTime_neverLink() {
        listOf(photo(time = null), photo(lat = null), photo(lng = null), photo(lat = Double.NaN),
            photo(lng = Double.POSITIVE_INFINITY), photo(lat = 91.0), photo(lat = -91.0),
            photo(lng = 181.0), photo(lng = -181.0)).forEach {
            assertNull(PhotoMatcher.match(it, listOf(visit)))
        }
        assertNull(PhotoMatcher.match(photo(), listOf(visit.copy(lat = Double.NaN))))
        assertNull(PhotoMatcher.match(photo(), listOf(visit.copy(leftAt = 90))))
    }
}
