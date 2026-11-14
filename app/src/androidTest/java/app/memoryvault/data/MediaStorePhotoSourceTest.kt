package app.memoryvault.data

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStorePhotoSourceTest {
    // Skipping the real query/open, or assuming absence alone means denial, breaks this URI lifecycle.
    @Test fun realMediaStoreQueryReadsExifAndKnownDeletedUriIsPositivelyInaccessible() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = LibraryPermissionContext(target)
        val resolver = target.contentResolver
        val file = File.createTempFile("photo-source-test-", ".jpg", target.cacheDir)
        val bitmap = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
        var created: Uri? = null
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:08 05:05:00")
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
                saveAttributes()
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "photo-source-test-${UUID.randomUUID()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MemoryVaultTests")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val inserted = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
            created = inserted
            requireNotNull(resolver.openOutputStream(inserted)).use { output -> file.inputStream().use { it.copyTo(output) } }
            resolver.update(inserted, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val canonical = "content://media/external/images/media/${inserted.lastPathSegment}"
            val source = MediaStorePhotoSource(context)
            val discovered = source.scan(emptySet()).photos.single { it.uri == canonical }
            assertEquals(1794114300000L, discovered.takenAt)
            assertNull(discovered.lat)
            assertNull(discovered.lng)
            assertFalse(canonical in source.scan(setOf(canonical)).inaccessibleUris)
            assertEquals(1, resolver.delete(inserted, null, null))
            created = null
            val removed = source.scan(setOf(canonical))
            assertTrue(canonical in removed.inaccessibleUris)
            assertFalse(removed.photos.any { it.uri == canonical })
        } finally {
            // Only this test's provider row and cache file are owned here; demo fixtures remain intact.
            created?.let { resolver.delete(it, null, null) }
            bitmap.recycle()
            check(file.name.startsWith("photo-source-test-"))
            file.delete()
        }
    }

    @Test fun currentSafetyDenialCancelsInsteadOfReturningAnAuthoritativeEmptyScan() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            MediaStorePhotoSource(context, canRead = { false }).scan(emptySet())
            fail("Photo source must not read while its current safety guard is false")
        } catch (_: CancellationException) { }
    }

    /** The actual provider/URI I/O stays real; this facade drives the adapter's full-library branch.
     * Android permits querying and opening this app's own media even without a broad media grant.
     * No OS grant is changed and no private or demo photo is created, modified or deleted here.
     */
    private class LibraryPermissionContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(permission: String): Int = when (permission) {
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_EXTERNAL_STORAGE" -> PackageManager.PERMISSION_GRANTED
            "android.permission.ACCESS_MEDIA_LOCATION", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" -> PackageManager.PERMISSION_DENIED
            else -> super.checkSelfPermission(permission)
        }
    }
}
