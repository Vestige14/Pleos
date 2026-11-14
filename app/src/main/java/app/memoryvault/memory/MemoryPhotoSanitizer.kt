package app.memoryvault.memory

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.CancellationSignal
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation

class PhotoPreparationException(val code: String) : IllegalStateException(code)
data class SanitizedMemoryPhoto(val bytes: ByteArray, val sha256: String) {
    override fun toString() = "SanitizedMemoryPhoto([pixels])"
}

/** Decode pixels and re-encode JPEG; no original EXIF/file name/URI travels to the API. */
class MemoryPhotoSanitizer(private val resolver: ContentResolver) {
    suspend fun prepare(localRef: String, allowed: () -> Boolean): SanitizedMemoryPhoto = withContext(Dispatchers.IO) {
        fun guard() { if (!allowed()) throw MemoryReviewBlocked() }
        guard()
        val uri = Uri.parse(localRef)
        if (uri.scheme != "content") throw PhotoPreparationException("PHOTO_SOURCE_UNSUPPORTED")
        try {
            val mime = resolver.getType(uri)?.lowercase()
            if (mime !in setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif"))
                throw PhotoPreparationException("PHOTO_MIME_UNSUPPORTED")
            val signal = CancellationSignal()
            val raw = coroutineScope {
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { signal.cancel() }
                }
                try {
                resolver.openAssetFileDescriptor(uri, "r", signal)?.use { descriptor ->
                    if (descriptor.length > 33_554_432) throw PhotoPreparationException("PHOTO_INPUT_TOO_LARGE")
                    descriptor.createInputStream().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive(); guard()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 33_554_432) throw PhotoPreparationException("PHOTO_INPUT_TOO_LARGE")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                } ?: throw PhotoPreparationException("PHOTO_UNREADABLE")
                } finally { cancellation.cancel() }
            }
            guard(); currentCoroutineContext().ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PhotoPreparationException("PHOTO_DAMAGED")
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight).toLong() / sample > 2048) sample *= 2
            var bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size,
                BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: throw PhotoPreparationException("PHOTO_DAMAGED")
            try {
                val orientation = ByteArrayInputStream(raw).use { ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                val transform = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                    }
                }
                fun replace(next: Bitmap) { if (next !== bitmap) { bitmap.recycle(); bitmap = next } }
                replace(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true))
                val ratio = minOf(1.0, 1024.0 / maxOf(bitmap.width, bitmap.height))
                if (ratio < 1) replace(Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true))
                for (quality in listOf(90, 75, 60, 45)) {
                    guard(); currentCoroutineContext().ensureActive()
                    val output = LimitedJpegOutput()
                    val encoded = try { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) }
                        catch (_: IOException) { false }
                    if (encoded && !output.exceeded && output.size() in 1..524_288) {
                        val bytes = output.toByteArray()
                        guard()
                        return@withContext SanitizedMemoryPhoto(bytes, MemoryWire.sha256(bytes))
                    }
                }
                throw PhotoPreparationException("PHOTO_OUTPUT_TOO_LARGE")
            } finally { bitmap.recycle(); raw.fill(0) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (blocked: MemoryReviewBlocked) { throw blocked }
        catch (known: PhotoPreparationException) { throw known }
        catch (_: SecurityException) { throw PhotoPreparationException("PHOTO_PERMISSION_LOST") }
        catch (_: Exception) { throw PhotoPreparationException("PHOTO_UNREADABLE") }
    }
    private class LimitedJpegOutput : ByteArrayOutputStream() {
        var exceeded = false
        private fun reserve(count: Int) { if (count > 524_288 - size()) { exceeded = true; throw IOException("JPEG_LIMIT") } }
        override fun write(value: Int) { reserve(1); super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { reserve(length); super.write(bytes, offset, length) }
    }
}
