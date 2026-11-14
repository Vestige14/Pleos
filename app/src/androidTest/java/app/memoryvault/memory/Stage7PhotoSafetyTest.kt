package app.memoryvault.memory

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage7PhotoSafetyTest {
    @Test fun syntheticLargeJpegIsBoundedReencodedAndMetadataFree() = runBlocking {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val name = "stage7-synthetic-${UUID.randomUUID()}.jpg"
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Stage7Synthetic/"); put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        try {
            val bitmap = Bitmap.createBitmap(3200, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            try { resolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
            finally { bitmap.recycle() }
            resolver.openFileDescriptor(uri, "rw")!!.use { fd ->
                ExifInterface(fd.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:09 12:00:00")
                    setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "STAGE7_SYNTHETIC_PRIVATE_METADATA")
                    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                    setLatLong(37.0, 127.0); saveAttributes()
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val result = MemoryPhotoSanitizer(resolver).prepare(uri.toString()) { true }
            assertTrue(result.bytes.size in 1..524288); assertEquals(MemoryWire.sha256(result.bytes), result.sha256)
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size))
            try { assertTrue(maxOf(decoded.width, decoded.height) <= 1024); assertTrue(decoded.height > decoded.width) }
            finally { decoded.recycle() }
            val exif = ExifInterface(result.bytes.inputStream())
            assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)); assertNull(exif.latLong)
            assertNull(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
            assertFalse(result.bytes.toString(Charsets.ISO_8859_1).contains("STAGE7_SYNTHETIC_PRIVATE"))
            try { MemoryPhotoSanitizer(resolver).prepare(uri.toString()) { false }; fail("parked guard") }
            catch (_: MemoryReviewBlocked) { }
        } finally { assertEquals(1, resolver.delete(uri, null, null)) }
    }
}
