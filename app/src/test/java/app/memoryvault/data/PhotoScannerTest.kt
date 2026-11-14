package app.memoryvault.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PhotoScannerTest {
    private val readable = PhotoMeta("content://kept", 10L, null, null)

    // Treating query absence as denial loses still-readable grants under partial access.
    @Test fun knownUriAbsentFromQuery_isReadAgainAndRetained() = runTest {
        val scan = PhotoScanner(Library(emptyList(), mapOf(readable.uri to PhotoRead.Readable(readable))))
            .scan(setOf(readable.uri))
        assertEquals(listOf(readable), scan.photos)
        assertTrue(scan.inaccessibleUris.isEmpty())
    }

    @Test fun onlyPositivelyUnreadableUrisAreMarkedInaccessible() = runTest {
        val scan = PhotoScanner(Library(listOf("new", "broken"), mapOf(
            "new" to PhotoRead.Readable(PhotoMeta("new", null, null, null)),
            "missing" to PhotoRead.Inaccessible,
        ))).scan(setOf("missing", "uncertain"))
        assertEquals(listOf("new"), scan.photos.map { it.uri })
        assertEquals(setOf("missing"), scan.inaccessibleUris)
    }

    @Test fun wholeQueryFailureNeverProducesAnEmptyAuthoritativeScan() = runTest {
        val source = PhotoScanner(object : PhotoLibrary {
            override suspend fun listUris(): List<String> = error("query unavailable")
            override suspend fun read(uri: String): PhotoRead = error("must not read")
        })
        try { source.scan(setOf("kept")); fail("Query failure must propagate") }
        catch (_: IllegalStateException) { }
    }

    @Test fun cancellationIsNotConvertedToInaccessiblePhoto() = runTest {
        val source = PhotoScanner(object : PhotoLibrary {
            override suspend fun listUris() = listOf("first")
            override suspend fun read(uri: String): PhotoRead = throw CancellationException("cancel")
        })
        try { source.scan(emptySet()); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    private class Library(private val uris: List<String>, private val values: Map<String, PhotoRead>) : PhotoLibrary {
        override suspend fun listUris() = uris
        override suspend fun read(uri: String): PhotoRead = values[uri] ?: error("temporary read failure")
    }
}
