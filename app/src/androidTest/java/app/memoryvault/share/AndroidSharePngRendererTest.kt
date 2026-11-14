package app.memoryvault.share

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSharePngRendererTest {
    // The product adapter must read the selected content URI, then publish only the new bounded PNG.
    @Test fun realContentSourceAndGalleryExportProduceReadable1080Png() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "share-source-test-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MemoryVaultTest/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val photo = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        var exported: Uri? = null
        try {
            val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            try { resolver.openOutputStream(photo)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            val gate = ShareSessionGate(MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))).apply { resume() }
            val exporter = ShareExporter(gate, AndroidShareGalleryIo(resolver), AndroidSharePngRenderer(resolver),
                SharePhotoAccess { _, _ -> true }, ShareChooser { false }, { "share-export-test-${UUID.randomUUID()}.png" },
                object : ShareExportReceiptStore {
                    override val latest = MutableStateFlow<ShareExportReceipt?>(null)
                    override suspend fun save(receipt: ShareExportReceipt) { latest.value = receipt }
                })
            val result = exporter.export(ShareDraft("2026-11-08", listOf(ShareItem(1, "식당", listOf(photo.toString()), "공유 설명"))))
            assertTrue("Real adapter must produce a saved PNG", result is ShareExportResult.Saved)
            exported = Uri.parse((result as ShareExportResult.Saved).uri)
            resolver.openInputStream(exported)!!.use {
                val decoded = requireNotNull(BitmapFactory.decodeStream(it))
                try { assertEquals(1080, decoded.width); assertTrue(decoded.height in 1..12000) }
                finally { decoded.recycle() }
            }
        } finally {
            exported?.let { resolver.delete(it, null, null) }
            resolver.delete(photo, null, null)
        }
    }
}
