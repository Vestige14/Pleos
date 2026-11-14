package app.memoryvault.share

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveReadinessReason
import app.memoryvault.platform.DriveSnapshot
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real MediaStore failures; only UUID-owned rows/preferences and an isolated safety flow are changed. */
@RunWith(AndroidJUnit4::class)
class AndroidShareFailureBoundaryTest {
    // Missing the post-write photo recheck would publish pixels from a source that no longer exists.
    @Test fun deletedPhotoAfterReadAndWriteCleansOnlyItsPendingOutput() = runBlocking {
        withFixture {
            val source = createPhoto("source", validPng)
            afterOutputClosed = { deleteOwned(source) }

            assertEquals(ShareExportResult.Failed(ShareExportFailure.PHOTO_UNAVAILABLE), export(source))

            assertEquals("The real source was readable before its deletion", listOf(true, false), photoReads)
            assertTrue(rendered is ShareRenderResult.Png)
            assertTrue("A complete pending PNG must exist before deleting the source", gallery.completePendingWritten)
            assertNull(row(source))
            assertEquals(gallery.created, gallery.cleaned)
            assertEquals(1, gallery.created.size)
            assertAbortedWithoutSideEffects()
        }
    }

    // Treating a readable URI as a decodable image would turn corrupt input into a partial/successful export.
    @Test fun corruptReadablePhotoFailsRenderingWithoutCreatingOutput() = runBlocking {
        withFixture {
            val corrupt = "MemoryVault synthetic non-image bytes".toByteArray(Charsets.UTF_8)
            val source = createPhoto("corrupt", corrupt)
            assertArrayEquals(corrupt, read(source))

            assertEquals(ShareExportResult.Failed(ShareExportFailure.RENDER_FAILED), export(source))

            assertEquals(listOf(true), photoReads)
            assertEquals(ShareRenderResult.Failure(ShareRenderFailure.INVALID_PHOTO, 0), rendered)
            assertTrue("Corrupt input must fail before MediaStore output insertion", gallery.created.isEmpty())
            assertTrue(gallery.cleaned.isEmpty())
            assertArrayEquals("The corrupt source is not an output to be cleaned", corrupt, read(source))
            assertAbortedWithoutSideEffects()
        }
    }

    // Checking PARKED alone, or omitting pending cleanup, leaks output after a short invalidation/recovery.
    @Test fun recoveredGuardInvalidationAfterWriteCleansOnlyItsPendingOutput() = runBlocking {
        withFixture {
            val source = createPhoto("source", validPng)
            val originalEpoch = requireNotNull(gate.capture())
            afterOutputClosed = {
                drive.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 1)
                drive.value = parked.copy(invalidationGeneration = 1)
            }

            assertEquals(ShareExportResult.Blocked, export(source))

            assertEquals(DriveState.PARKED, drive.value.state)
            assertFalse("Recovery cannot revive the old export epoch", gate.allows(originalEpoch))
            assertEquals(listOf(true), photoReads)
            assertTrue(rendered is ShareRenderResult.Png)
            assertTrue("Invalidate only after real PNG write/close, before publication", gallery.completePendingWritten)
            assertEquals(1, gallery.created.size)
            assertEquals(gallery.created, gallery.cleaned)
            assertArrayEquals("Guard cancellation must not delete the input", validPng, read(source))
            assertAbortedWithoutSideEffects()
        }
    }

    private suspend fun withFixture(test: suspend Fixture.() -> Unit) {
        val fixture = Fixture()
        var primaryFailure: Throwable? = null
        try {
            fixture.prepare()
            fixture.test()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            fixture.cleanup(primaryFailure)
        }
    }

    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        private val id = UUID.randomUUID().toString()
        private val preferencesName = "share-failure-$id-receipt"
        private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        private val receipts = SharedPreferencesShareReceiptStore(preferences)
        private val owned = linkedMapOf<Uri, String>()
        private lateinit var earlierImage: Uri
        private lateinit var earlierReceipt: ShareExportReceipt
        private lateinit var earlierPreferences: Map<String, *>
        val outputName = "share-failure-$id-output.png"
        val validPng: ByteArray = ByteArrayOutputStream().use { bytes ->
            val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                bytes.toByteArray()
            } finally { bitmap.recycle() }
        }
        val parked = DriveSnapshot(state = DriveState.PARKED, requiresDistractionOptimization = false,
            activeRestrictions = 0, readinessReason = DriveReadinessReason.READY)
        val drive = MutableStateFlow(parked)
        val gate = ShareSessionGate(drive).apply { resume() }
        val gallery = ObservedGallery(this)
        val photoReads = mutableListOf<Boolean>()
        var rendered: ShareRenderResult? = null
        var afterOutputClosed: () -> Unit = {}
        private var chooserCalls = 0

        suspend fun prepare() {
            earlierImage = createPhoto("earlier-public", validPng)
            earlierReceipt = ShareExportReceipt(earlierImage.toString(), requireNotNull(owned[earlierImage]), "2026-11-07")
            receipts.save(earlierReceipt)
            earlierPreferences = preferences.all.toMap()
        }

        suspend fun export(source: Uri): ShareExportResult {
            val realRenderer = AndroidSharePngRenderer(resolver)
            val realAccess = AndroidSharePhotoAccess(resolver)
            val exporter = ShareExporter(gate, gallery, SharePngRenderer { draft, guard ->
                realRenderer.render(draft, guard).also { rendered = it }
            }, SharePhotoAccess { uri, guard ->
                // Observe calls, but never substitute success/failure for the actual ContentResolver read.
                try { realAccess.canRead(uri, guard).also { photoReads += it } }
                catch (failure: Exception) { photoReads += false; throw failure }
            }, ShareChooser { chooserCalls++; true }, { outputName }, receipts)
            return exporter.export(ShareDraft("2026-11-08",
                listOf(ShareItem(1, "합성 시험 방문", listOf(source.toString()), null))))
        }

        fun createPhoto(label: String, bytes: ByteArray): Uri {
            val name = "share-failure-$id-$label.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, FIXTURE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            remember(uri, name)
            requireNotNull(resolver.openOutputStream(uri, "w")).use { it.write(bytes) }
            assertEquals(1, resolver.update(uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null))
            assertEquals(Row(name, "image/png", FIXTURE_PATH, 0), row(uri))
            assertArrayEquals(bytes, read(uri))
            return uri
        }

        fun remember(uri: Uri, displayName: String) {
            require(displayName.startsWith("share-failure-$id-"))
            owned[uri] = displayName
        }

        fun read(uri: Uri): ByteArray = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }

        // Collection query explicitly includes pending; a leaked pending row must not disappear from the assertion.
        @Suppress("DEPRECATION")
        private val includingPending = MediaStore.setIncludePending(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)

        fun row(uri: Uri): Row? = requireNotNull(resolver.query(includingPending,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING),
            "${MediaStore.Images.Media._ID} = ?", arrayOf(ContentUris.parseId(uri).toString()), null)).use { cursor ->
            if (!cursor.moveToFirst()) null
            else Row(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3))
        }

        fun assertCompletePending(uri: String) {
            val parsed = Uri.parse(uri)
            assertEquals(Row(outputName, "image/png", "Pictures/기억저장소/", 1), row(parsed))
            val bitmap = requireNotNull(resolver.openInputStream(parsed)).use {
                requireNotNull(BitmapFactory.decodeStream(it)) { "Pending output is not a complete PNG" }
            }
            try { assertEquals(1080, bitmap.width); assertTrue(bitmap.height in 1..12000) }
            finally { bitmap.recycle() }
        }

        fun assertAbortedWithoutSideEffects() {
            assertEquals("No publication call after a pre-publication failure", 0, gallery.publishCalls)
            assertEquals("No automatic chooser after a failed export", 0, chooserCalls)
            gallery.created.forEach { assertNull("The operation's pending output leaked", row(Uri.parse(it))) }
            requireNotNull(resolver.query(includingPending, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(outputName), null)).use {
                assertFalse("Neither pending nor public output may remain", it.moveToFirst())
            }
            assertEquals(earlierReceipt, receipts.latest.value)
            assertEquals("A reconstructed real receipt store must still show the earlier success", earlierReceipt,
                SharedPreferencesShareReceiptStore(preferences).latest.value)
            assertEquals("An unpublished failure cannot write a new receipt", earlierPreferences, preferences.all)
            assertEquals(Row(requireNotNull(owned[earlierImage]), "image/png", FIXTURE_PATH, 0), row(earlierImage))
            assertArrayEquals("An unrelated public image must survive unchanged", validPng, read(earlierImage))
        }

        fun deleteOwned(uri: Uri) {
            val expectedName = requireNotNull(owned[uri]) { "Refusing to delete an unowned URI" }
            val current = row(uri)
            if (current != null) {
                assertEquals("Only this fixture's exact UUID row may be deleted", expectedName, current.name)
                assertEquals(1, resolver.delete(uri, "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(expectedName)))
                assertNull("Fixture cleanup left its row behind", row(uri))
            }
            owned.remove(uri) // Do not delete a stale item URI after product cleanup or our earlier deletion.
        }

        fun cleanup(primaryFailure: Throwable?) {
            var cleanupFailure: Throwable? = null
            fun attempt(action: () -> Unit) {
                try { action() }
                catch (failure: Throwable) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(failure)
                    else if (cleanupFailure == null) cleanupFailure = failure
                    else cleanupFailure!!.addSuppressed(failure)
                }
            }
            owned.keys.toList().asReversed().forEach { uri -> attempt { deleteOwned(uri) } }
            attempt { assertTrue("Only the UUID test preferences must be removed", context.deleteSharedPreferences(preferencesName)) }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }

    /** Delegates every MediaStore operation; the hook injects the fault only after a real write and close. */
    private class ObservedGallery(private val fixture: Fixture) : ShareGalleryIo {
        private val real = AndroidShareGalleryIo(fixture.resolver)
        val created = mutableListOf<String>()
        val cleaned = mutableListOf<String>()
        var publishCalls = 0
        var completePendingWritten = false

        override fun createPending(displayName: String): String? = real.createPending(displayName)?.also {
            fixture.remember(Uri.parse(it), displayName)
            created += it
        }

        override fun openOutput(uri: String): OutputStream? = real.openOutput(uri)?.let { stream ->
            object : FilterOutputStream(stream) {
                override fun close() {
                    super.close()
                    fixture.assertCompletePending(uri)
                    completePendingWritten = true
                    fixture.afterOutputClosed()
                }
            }
        }

        override fun publish(uri: String): Boolean { publishCalls++; return real.publish(uri) }
        override fun deletePending(uri: String) { cleaned += uri; real.deletePending(uri) }
    }

    private data class Row(val name: String, val mimeType: String, val relativePath: String, val pending: Int)

    private companion object {
        const val FIXTURE_PATH = "Pictures/MemoryVaultFailureTest/"
    }
}
