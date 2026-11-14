package app.memoryvault.share

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidShareGalleryIoTest {
    // Public/pending paths must be atomic; cleanup must not remove an already published image.
    @Test fun pendingWritePublishAndConditionalCleanupUseOnlyTheNewRow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val io = AndroidShareGalleryIo(resolver)
        val created = mutableListOf<String>()
        var primaryFailure: Throwable? = null
        try {
            val name = "share-gallery-test-${UUID.randomUUID()}.png"
            val uri = requireNotNull(io.createPending(name)); created += uri
            resolver.query(Uri.parse(uri), arrayOf(MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(name, cursor.getString(0))
                assertEquals("image/png", cursor.getString(1))
                assertEquals(Environment.DIRECTORY_PICTURES + "/기억저장소/", cursor.getString(2))
                assertEquals(1, cursor.getInt(3))
            }
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try { io.openOutput(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            assertTrue(io.publish(uri))
            io.deletePending(uri)
            resolver.query(Uri.parse(uri), arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use {
                assertTrue("Published image must survive pending cleanup", it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            val pending = requireNotNull(io.createPending("share-pending-test-${UUID.randomUUID()}.png")); created += pending
            io.deletePending(pending)
            created.remove(pending) // The product already removed this row; do not delete a stale item URI again.
            // Query the collection by our own exact ID. Include pending rows so a failed deletion cannot pass.
            @Suppress("DEPRECATION")
            val includingPending = MediaStore.setIncludePending(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            resolver.query(includingPending, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media._ID} = ?", arrayOf(ContentUris.parseId(Uri.parse(pending)).toString()), null)!!.use {
                assertFalse(it.moveToFirst())
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            created.forEach { uri ->
                try { resolver.delete(Uri.parse(uri), null, null) }
                catch (failure: Throwable) {
                    val original = primaryFailure
                    if (original != null) original.addSuppressed(failure)
                    else if (cleanupFailure == null) cleanupFailure = failure
                    else cleanupFailure!!.addSuppressed(failure)
                }
            }
            // Cleanup errors are reported, never allowed to replace a body's original assertion/error.
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }
}
