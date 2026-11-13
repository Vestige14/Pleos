package app.memoryvault.domain

import org.junit.Assert.*
import org.junit.Test

class CandidateSnapshotTest {
    private val restaurant = VisitCandidate("a", "식당", 1, 2)
    private val cafe = VisitCandidate("b", "카페", 3, 4)
    private val canonical = """[{"id":"a","placeName":"식당","arrivedAt":1,"leftAt":2},{"id":"b","placeName":"카페","arrivedAt":3,"leftAt":4}]"""

    @Test fun encoding_isCanonicalAndChronologicalRegardlessOfInputOrder() {
        assertEquals(canonical, CandidateSnapshot.encode(listOf(cafe, restaurant)))
        assertEquals(canonical, CandidateSnapshot.encode(listOf(restaurant, cafe)))
    }

    @Test fun decoding_restoresTheStoredMinimalCandidateSnapshot() {
        assertEquals(listOf(restaurant, cafe), CandidateSnapshot.decode(canonical))
    }

    // Digest derived independently from the literal UTF-8 JSON with Python hashlib.
    @Test fun contextHash_isUtf8Sha256OfCanonicalCandidatesAndChangesWithContext() {
        val expected = "69df7ef15b92037986733406ea780a67046ad71dcb20937d85bd503f2bbcc07a"
        assertEquals(expected, CandidateSnapshot.contextHash(listOf(cafe, restaurant)))
        assertNotEquals(expected, CandidateSnapshot.contextHash(listOf(restaurant.copy(placeName = "다른 식당"), cafe)))
    }
}
