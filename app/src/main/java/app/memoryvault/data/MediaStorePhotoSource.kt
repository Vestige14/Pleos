package app.memoryvault.data

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import app.memoryvault.platform.PhotoAccess
import app.memoryvault.platform.readPhotoPermissions
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class MediaStorePhotoSource(context: Context, private val canRead: () -> Boolean = { true }) : PhotoSource {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val scanner = PhotoScanner(object : PhotoLibrary {
        override suspend fun listUris() = queryUris()
        override suspend fun read(uri: String) = readPhoto(uri)
    })

    override suspend fun scan(knownUris: Set<String>): PhotoScan = withContext(Dispatchers.IO) {
        requireCurrentAccess()
        scanner.scan(knownUris)
    }

    private suspend fun queryUris(): List<String> {
        requireCurrentAccess()
        if (readPhotoPermissions(appContext).access == PhotoAccess.DENIED) return emptyList()
        val cursor = resolver.query(collection, arrayOf(MediaStore.Images.Media._ID), null, null,
            "${MediaStore.Images.Media._ID} ASC") ?: throw IOException("Photo library query unavailable")
        return cursor.use { rows ->
            val column = rows.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val result = mutableListOf<String>()
            while (rows.moveToNext()) {
                requireCurrentAccess()
                result += ContentUris.withAppendedId(collection, rows.getLong(column)).toString()
            }
            result
        }
    }

    private suspend fun readPhoto(value: String): PhotoRead {
        requireCurrentAccess()
        return try {
            val opened = openPhoto(Uri.parse(value), readPhotoPermissions(appContext).location)
            opened.stream.use { input ->
                requireCurrentAccess()
                val metadata = PhotoExifReader.read(value, input,
                    includeLocation = opened.original && readPhotoPermissions(appContext).location)
                requireCurrentAccess()
                PhotoRead.Readable(metadata)
            }
        } catch (_: FileNotFoundException) {
            PhotoRead.Inaccessible
        } catch (_: SecurityException) {
            PhotoRead.Inaccessible
        }
    }

    private data class OpenedPhoto(val stream: InputStream, val original: Boolean)

    private fun openPhoto(uri: Uri, requestOriginal: Boolean): OpenedPhoto {
        if (requestOriginal) {
            try {
                resolver.openInputStream(MediaStore.setRequireOriginal(uri))?.let { return OpenedPhoto(it, true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Losing GPS access, or an unsupported original, must still allow a redacted image.
            }
        }
        val stream = resolver.openInputStream(uri) ?: throw IOException("Photo provider unavailable")
        return OpenedPhoto(stream, false)
    }

    private suspend fun requireCurrentAccess() {
        currentCoroutineContext().ensureActive()
        if (!canRead()) throw CancellationException("Photo access requires current parked state")
    }
}
