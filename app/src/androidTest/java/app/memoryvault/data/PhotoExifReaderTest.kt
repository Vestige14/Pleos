package app.memoryvault.data

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoExifReaderTest {
    // Using MediaStore's date or ignoring offset would shift the candidate even with correct EXIF.
    @Test fun realJpegExifUsesExplicitOffsetAndLocation() = withJpeg { file ->
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:08 05:05:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
            setLatLong(37.2846, 126.5779)
            saveAttributes()
        }
        val meta = file.inputStream().use { PhotoExifReader.read("content://synthetic", it, includeLocation = true) }
        assertEquals(t("14:05"), meta.takenAt)
        assertEquals(37.2846, requireNotNull(meta.lat), 0.000001)
        assertEquals(126.5779, requireNotNull(meta.lng), 0.000001)
    }

    @Test fun missingGpsPermissionDoesNotExposeCoordinatesEvenIfStreamContainsGps() = withJpeg { file ->
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:08 14:05:00")
            setLatLong(37.2846, 126.5779)
            saveAttributes()
        }
        val meta = file.inputStream().use { PhotoExifReader.read("content://synthetic", it, includeLocation = false) }
        assertEquals(t("14:05"), meta.takenAt)
        assertNull(meta.lat)
        assertNull(meta.lng)
    }

    @Test fun missingAndDamagedExifStayReadableWithNullMetadata() = withJpeg { file ->
        val expected = PhotoMeta("content://synthetic", null, null, null)
        assertEquals(expected, file.inputStream().use { PhotoExifReader.read(expected.uri, it, true) })
        assertEquals(expected, ByteArrayInputStream(byteArrayOf(1, 2, 3)).use { PhotoExifReader.read(expected.uri, it, true) })
    }

    private fun withJpeg(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("photo-exif-test-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            block(file)
        } finally { bitmap.recycle(); check(file.name.startsWith("photo-exif-test-")); file.delete() }
    }
}
