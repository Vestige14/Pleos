package app.memoryvault.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class PhotoScan(val photos: List<PhotoMeta>, val inaccessibleUris: Set<String> = emptySet())

internal sealed interface PhotoRead {
    data class Readable(val photo: PhotoMeta) : PhotoRead
    data object Inaccessible : PhotoRead
}

internal interface PhotoLibrary {
    suspend fun listUris(): List<String>
    suspend fun read(uri: String): PhotoRead
}

internal class PhotoScanner(private val library: PhotoLibrary) {
    suspend fun scan(knownUris: Set<String>): PhotoScan {
        // A failed query is not an authoritative empty library. Let the caller keep its DB intact.
        val uris = (library.listUris() + knownUris).distinct()
        val photos = mutableListOf<PhotoMeta>()
        val inaccessible = mutableSetOf<String>()
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            try {
                when (val result = library.read(uri)) {
                    is PhotoRead.Readable -> photos += result.photo
                    PhotoRead.Inaccessible -> inaccessible += uri
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An uncertain/transient read leaves the previous row untouched, not inaccessible.
            }
        }
        currentCoroutineContext().ensureActive()
        return PhotoScan(photos, inaccessible)
    }
}
