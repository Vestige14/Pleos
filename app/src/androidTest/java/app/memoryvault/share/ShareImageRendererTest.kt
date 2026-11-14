package app.memoryvault.share

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareImageRendererTest {
    private val createdFiles = mutableListOf<File>()

    @After fun removeOnlyThisTestsFixtures() {
        createdFiles.forEach { it.delete() }
    }

    // Reading input photos instead of the filtered snapshot would request the excluded café URI.
    @Test fun filteredSnapshotProduces1080PngAndReadsOnlyIncludedSelectedUris() = runBlocking {
        val source = TrackingSource(mapOf("p1" to jpeg(), "p2" to jpeg(orientation = 6)))
        val input = ShareInput(DATE, listOf(
            ShareVisitInput("a", DATE, "대부도 칼국수집", 1, listOf(SharePhotoInput("p1", SharePhotoState.CANDIDATE, true))),
            ShareVisitInput("beach", DATE, "방아머리해변", 2, listOf(SharePhotoInput("p2", SharePhotoState.CONFIRMED, true))),
            ShareVisitInput("cafe", DATE, "제외 카페", 2, listOf(SharePhotoInput("private", SharePhotoState.CONFIRMED, true))),
        ))
        val snapshot = requireNotNull(ShareDraftBuilder.build(
            ShareSelection(DATE, setOf("a", "beach"), setOf("p1", "p2", "private"), mapOf(
                "beach" to "바다에서 한참 놀았어요. 가족과 해변을 걸으며 하루를 기록했어요 🌊. ".repeat(4),
                "cafe" to "개인 메모")), input))

        val png = png(ShareImageRenderer(source).render(snapshot))

        assertEquals(1080, png.width)
        assertTrue(png.height in 1..12000)
        assertEquals(setOf("p1", "p2"), source.requested.toSet())
        assertEquals(0, source.active)
        decode(png) { assertEquals(1080, it.width); assertEquals(png.height, it.height) }
        // Test-only artifact under the executing target app's UID, never MediaStore.
        val externalFiles = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val preview = File(externalFiles, "share-renderer-preview-synthetic.png")
        preview.writeBytes(png.bytes)
        assertArrayEquals(png.bytes, preview.readBytes())
        exportSyntheticPreviewIfRequested(png)
    }

    // Ignoring any EXIF rotation/reflection changes the observed four colored quadrants.
    @Test fun allEightExifOrientationsAreAppliedWithoutCropping() = runBlocking {
        val expected = listOf(
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
        )
        for (orientation in 1..8) {
            val source = TrackingSource(mapOf("p1" to jpeg(orientation = orientation)))
            val png = png(ShareImageRenderer(source).render(draft("p1")))
            decode(png) { bitmap ->
                val bounds = photoBounds(bitmap)
                assertEquals(if (orientation >= 5) 2.0 else 0.5,
                    bounds.height().toDouble() / bounds.width(), 0.02)
                val points = listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3)
                points.forEachIndexed { index, (x, y) ->
                    assertColor(expected[orientation - 1][index],
                        bitmap.getPixel(bounds.left + bounds.width() * x / 4, bounds.top + bounds.height() * y / 4))
                }
            }
            assertEquals(0, source.active)
        }
    }

    // Omitting downsampling would decode 32MB, exceed the per-photo limit, and fail this render.
    @Test fun largeOriginalIsDownsampledBeforeDrawing() = runBlocking {
        val source = TrackingSource(mapOf("large" to jpeg(width = 4000, height = 2000)))
        val result = png(ShareImageRenderer(source).render(draft("large")))
        decode(result) { bitmap ->
            val bounds = photoBounds(bitmap)
            assertEquals(0.5, bounds.height().toDouble() / bounds.width(), 0.02)
        }
        assertEquals(0, source.active)
    }

    // Single-line drawing or ellipsizing would leave only a few text bands despite the long caption.
    @Test fun longKoreanAndEmojiCaptionWrapsAndRemainsVisible() = runBlocking {
        val source = TrackingSource(emptyMap())
        val short = png(ShareImageRenderer(source).render(draft(caption = "바다")))
        val long = png(ShareImageRenderer(source).render(draft(
            caption = "바다에서함께보낸하루를오래기억하고싶어요 🌊 가족과걸었던해변길을기록해요. ".repeat(30))))
        assertTrue(long.height > short.height + 300)
        decode(long) { bitmap -> assertTrue(inkBands(bitmap) > 12) }
    }

    // Silently clipping a portrait or caption to the output height must never return a successful PNG.
    @Test fun portraitExceedingHeightLimitReturnsExplicitError() = runBlocking {
        val source = TrackingSource(mapOf("tall" to jpeg(width = 80, height = 980)))
        assertEquals(ShareRenderFailure.TOO_TALL, failure(ShareImageRenderer(source).render(draft("tall"))).reason)
        assertEquals(0, source.active)
    }

    @Test fun captionExceedingHeightLimitReturnsExplicitError() = runBlocking {
        assertEquals(ShareRenderFailure.TOO_TALL,
            failure(ShareImageRenderer(TrackingSource(emptyMap())).render(draft(caption = "한글\n".repeat(400)))).reason)
    }

    // Skipping the allocation budget could allocate a canvas despite insufficient permitted memory.
    @Test fun insufficientMemoryBudgetRejectsBeforeReturningImage() = runBlocking {
        assertEquals(ShareRenderFailure.MEMORY_LIMIT,
            failure(ShareImageRenderer(TrackingSource(emptyMap()), memoryBudgetBytes = 1024).render(draft())).reason)
    }

    // Removing the UTF-16 guard or counting code points instead would try to open this photo.
    @Test fun textAbove64000Utf16UnitsFailsBeforeOpeningPhotos() = runBlocking {
        val source = TrackingSource(emptyMap())
        // "장소" (2) + 31,999 surrogate pairs (63,998) + "가" (1) = 64,001 UTF-16 units.
        val input = ShareDraft(DATE, listOf(ShareItem(1, "장소", listOf("must-not-open"),
            "🌊".repeat(31_999) + "가")))

        val result = failure(ShareImageRenderer(source).render(input))

        assertEquals(ShareRenderFailure.MEMORY_LIMIT, result.reason)
        assertTrue(source.requested.isEmpty())
        assertEquals(0, source.active)
    }

    @Test fun missingPhotoFailsWholeRenderAndClosesEarlierStreams() = runBlocking {
        val source = TrackingSource(mapOf("p1" to jpeg()))
        val result = failure(ShareImageRenderer(source).render(draft("p1", "missing")))
        assertEquals(ShareRenderFailure.PHOTO_UNAVAILABLE, result.reason)
        assertEquals(1, result.photoIndex)
        assertEquals(0, source.active)
        assertFalse(result.toString().contains("missing"))
    }

    // Access can disappear after EXIF/bounds; no partial canvas may be returned as successful.
    @Test fun permissionLossAtDecodeReturnsReselectErrorAndClosesStreams() = runBlocking {
        val source = TrackingSource(mapOf("p1" to jpeg()), failAtOpen = 3)
        val result = failure(ShareImageRenderer(source).render(draft("p1")))
        assertEquals(ShareRenderFailure.PHOTO_UNAVAILABLE, result.reason)
        assertEquals(0, result.photoIndex)
        assertEquals(0, source.active)
    }

    @Test fun corruptPhotoReturnsExplicitErrorRatherThanBlankPlaceholder() = runBlocking {
        val source = TrackingSource(mapOf("corrupt" to byteArrayOf(1, 2, 3, 4)))
        assertEquals(ShareRenderFailure.INVALID_PHOTO, failure(ShareImageRenderer(source).render(draft("corrupt"))).reason)
        assertEquals(0, source.active)
    }

    // Copying source metadata during PNG encoding would expose these synthetic GPS/date/comment fields.
    @Test fun newlyEncodedPngContainsNoOriginalExifOrGpsMetadata() = runBlocking {
        val source = TrackingSource(mapOf("p1" to jpeg()))
        val result = png(ShareImageRenderer(source).render(draft("p1")))
        val exif = ExifInterface(ByteArrayInputStream(result.bytes))
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull(exif.getAttribute(ExifInterface.TAG_USER_COMMENT))
        assertFalse(pngChunkTypes(result.bytes).any { it in setOf("eXIf", "tEXt", "iTXt", "zTXt") })
    }

    // Swallowing coroutine cancellation would return an error/success and leave source streams alive.
    @Test fun cancellationPropagatesAndClosesOpenedStream() = runBlocking {
        val source = TrackingSource(mapOf("p1" to jpeg()))
        val rendering = async {
            val job = currentCoroutineContext().job
            val cancellingSource = SharePhotoSource { uri -> job.cancel(); source.open(uri) }
            ShareImageRenderer(cancellingSource).render(draft("p1"))
        }
        try {
            rendering.await()
            fail("Rendering must propagate cancellation")
        } catch (_: CancellationException) {
            assertTrue(source.requested.isNotEmpty())
            assertEquals(0, source.active)
        }
    }

    private fun draft(vararg uris: String, caption: String? = null) = ShareDraft(DATE,
        listOf(ShareItem(1, "방아머리해변", uris.toList(), caption)))

    private fun png(result: ShareRenderResult): ShareRenderResult.Png {
        assertTrue("Expected a complete PNG, got $result", result is ShareRenderResult.Png)
        return result as ShareRenderResult.Png
    }

    private fun failure(result: ShareRenderResult): ShareRenderResult.Failure {
        assertTrue("Expected an explicit failure", result is ShareRenderResult.Failure)
        return result as ShareRenderResult.Failure
    }

    private fun decode(png: ShareRenderResult.Png, inspect: (Bitmap) -> Unit) {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png.bytes, 0, png.bytes.size))
        try { inspect(bitmap) } finally { bitmap.recycle() }
    }

    /** Opt-in transport of this generated fixture for inspection; never a product export path. */
    private fun exportSyntheticPreviewIfRequested(png: ShareRenderResult.Png) {
        if (InstrumentationRegistry.getArguments().getString("exportSyntheticPreview") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val name = "share-renderer-synthetic-${UUID.randomUUID()}.png"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MemoryVaultTest/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(collection, values))
        var published = false
        try {
            requireNotNull(resolver.openOutputStream(uri, "w")).use { it.write(png.bytes) }
            requireNotNull(resolver.openInputStream(uri)).use { assertArrayEquals(png.bytes, it.readBytes()) }
            val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            assertEquals(1, resolver.update(uri, complete, null, null))
            published = true
            instrumentation.sendStatus(2, Bundle().apply {
                putString("syntheticPreviewUri", uri.toString())
                putString("syntheticPreviewDisplayName", name)
                putInt("syntheticPreviewBytes", png.bytes.size)
            })
        } finally {
            // Only this insert's URI can be removed, never an existing image or a queried collection.
            if (!published) resolver.delete(uri, null, null)
        }
    }

    private fun jpeg(width: Int = 80, height: Int = 40, orientation: Int = 1): ByteArray {
        val file = File.createTempFile("share-renderer-test-", ".jpg", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        createdFiles += file
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint()
            val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
            colors.forEachIndexed { index, color ->
                paint.color = color
                val left = if (index % 2 == 0) 0f else width / 2f
                val top = if (index < 2) 0f else height / 2f
                canvas.drawRect(left, top, left + width / 2f, top + height / 2f, paint)
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally { bitmap.recycle() }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:08 14:05:00")
            setAttribute(ExifInterface.TAG_USER_COMMENT, "PRIVATE-SOURCE-METADATA")
            setLatLong(37.2846, 126.5779)
            saveAttributes()
        }
        return file.readBytes()
    }

    private fun photoBounds(bitmap: Bitmap): Rect {
        var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            row.forEachIndexed { x, color ->
                val red = Color.red(color); val green = Color.green(color); val blue = Color.blue(color)
                val max = maxOf(red, green, blue)
                if (max > 150 && max - minOf(red, green, blue) > 100) {
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue("Selected photo must have visible pixels", right >= left && bottom >= top)
        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun assertColor(expected: Int, actual: Int) {
        assertEquals(Color.red(expected).toDouble(), Color.red(actual).toDouble(), 60.0)
        assertEquals(Color.green(expected).toDouble(), Color.green(actual).toDouble(), 60.0)
        assertEquals(Color.blue(expected).toDouble(), Color.blue(actual).toDouble(), 60.0)
    }

    private fun inkBands(bitmap: Bitmap): Int {
        var bands = 0; var wasInk = false
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            val ink = row.any { Color.red(it) < 150 && Color.green(it) < 150 && Color.blue(it) < 150 }
            if (ink && !wasInk) bands++
            wasInk = ink
        }
        return bands
    }

    private fun pngChunkTypes(bytes: ByteArray): List<String> {
        val types = mutableListOf<String>()
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val length = (0..3).fold(0) { value, index -> (value shl 8) or (bytes[offset + index].toInt() and 255) }
            types += String(bytes, offset + 4, 4, Charsets.US_ASCII)
            offset += length + 12
        }
        return types
    }

    private class TrackingSource(private val images: Map<String, ByteArray>, private val failAtOpen: Int? = null) : SharePhotoSource {
        val requested = mutableListOf<String>()
        var active = 0
            private set

        override fun open(uri: String): InputStream {
            requested += uri
            if (requested.size == failAtOpen) throw SecurityException("Synthetic permission loss")
            val bytes = images[uri] ?: throw FileNotFoundException("Synthetic inaccessible photo")
            active++
            return object : ByteArrayInputStream(bytes) {
                var closed = false
                override fun close() {
                    if (!closed) { closed = true; active-- }
                    super.close()
                }
            }
        }
    }

    private companion object { const val DATE = "2026-11-08" }
}
