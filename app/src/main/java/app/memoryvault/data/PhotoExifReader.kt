package app.memoryvault.data

import java.io.InputStream
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException

internal object PhotoExifReader {
    fun read(uri: String, stream: InputStream, includeLocation: Boolean): PhotoMeta {
        return try {
            val exif = ExifInterface(stream)
            val time = ExifTime.parse(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
            val location = if (includeLocation) exif.latLong else null
            PhotoMeta(uri, time, location?.get(0), location?.get(1))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The stream was opened successfully; unsupported/damaged metadata is still a readable photo.
            PhotoMeta(uri, null, null, null)
        }
    }
}
