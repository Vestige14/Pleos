package app.memoryvault.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import java.io.InputStream
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Opens a fresh stream on every call; the renderer closes every stream it opens. */
fun interface SharePhotoSource {
    fun open(uri: String): InputStream?
}

enum class ShareRenderFailure { INVALID_DRAFT, PHOTO_UNAVAILABLE, INVALID_PHOTO, TOO_TALL, MEMORY_LIMIT, ENCODING_FAILED }

sealed interface ShareRenderResult {
    /** Complete PNG only. The caller owns [bytes]; no Bitmap is retained by the renderer. */
    class Png(val bytes: ByteArray, val width: Int, val height: Int) : ShareRenderResult
    data class Failure(val reason: ShareRenderFailure, val photoIndex: Int? = null) : ShareRenderResult
}

class ShareImageRenderer(
    private val source: SharePhotoSource,
    private val memoryBudgetBytes: Long = 128L * 1024 * 1024,
) {
    /**
     * Renders in a worker context. Callers serialize exports and recheck their own safety gate.
     * Source pixels are rendered as selected; this does not redact text/faces inside a photo.
     * Native decode/encode is synchronous; cancellation is checked at each surrounding boundary.
     */
    suspend fun render(draft: ShareDraft): ShareRenderResult = withContext(Dispatchers.Default) {
        var output: Bitmap? = null
        try {
            currentCoroutineContext().ensureActive()
            if (draft.items.isEmpty()) problem(ShareRenderFailure.INVALID_DRAFT)
            if (memoryBudgetBytes < ENCODED_BYTES * 2L + OVERHEAD_BYTES) problem(ShareRenderFailure.MEMORY_LIMIT)
            val layout = measure(draft)
            val canvasBytes = WIDTH.toLong() * layout.height * 4L
            val photoBytes = if (layout.blocks.any { it is PhotoBlock }) PHOTO_BYTES else 0L
            // Reserve the canvas, one decoded photo, a capped PNG buffer and its returned copy.
            if (canvasBytes + photoBytes + ENCODED_BYTES * 2L + OVERHEAD_BYTES > memoryBudgetBytes) {
                problem(ShareRenderFailure.MEMORY_LIMIT)
            }
            currentCoroutineContext().ensureActive()
            val bitmap = Bitmap.createBitmap(WIDTH, layout.height, Bitmap.Config.ARGB_8888)
            output = bitmap
            val canvas = Canvas(bitmap)
            try {
                canvas.drawColor(Color.WHITE)
                for (block in layout.blocks) {
                    currentCoroutineContext().ensureActive()
                    when (block) {
                        is TextBlock -> {
                            val saveCount = canvas.save()
                            try {
                                canvas.translate(PADDING.toFloat(), block.top.toFloat())
                                block.layout.draw(canvas)
                            } finally { canvas.restoreToCount(saveCount) }
                        }
                        is PhotoBlock -> drawPhoto(canvas, block)
                    }
                }
            } finally { canvas.setBitmap(null) }
            currentCoroutineContext().ensureActive()
            val png = BoundedPngOutput(ENCODED_BYTES)
            val compressed = try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
            } catch (_: IOException) {
                false
            }
            // Native encoders can turn a stream IOException into false; the flag preserves its cause.
            if (png.limitReached) problem(ShareRenderFailure.MEMORY_LIMIT)
            if (!compressed) problem(ShareRenderFailure.ENCODING_FAILED)
            currentCoroutineContext().ensureActive()
            val bytes = png.toByteArray()
            currentCoroutineContext().ensureActive()
            ShareRenderResult.Png(bytes, WIDTH, layout.height)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RenderProblem) {
            ShareRenderResult.Failure(failure.reason, failure.photoIndex)
        } catch (_: DateTimeParseException) {
            ShareRenderResult.Failure(ShareRenderFailure.INVALID_DRAFT)
        } catch (_: OutOfMemoryError) {
            ShareRenderResult.Failure(ShareRenderFailure.MEMORY_LIMIT)
        } catch (_: RuntimeException) {
            ShareRenderResult.Failure(ShareRenderFailure.ENCODING_FAILED)
        } finally {
            output?.recycle()
        }
    }

    private suspend fun measure(draft: ShareDraft): ImageLayout {
        if (draft.items.size > MAX_HEIGHT / 46) problem(ShareRenderFailure.TOO_TALL)
        val textCharacters = draft.items.sumOf { it.placeName.length.toLong() + (it.caption?.length ?: 0) }
        if (textCharacters > MAX_TEXT_CHARACTERS) problem(ShareRenderFailure.MEMORY_LIMIT)
        val lines = ShareImageText.lines(draft)
        val blocks = mutableListOf<Block>()
        var top = PADDING
        var lineIndex = 0
        var photoIndex = 0

        fun advance(height: Int, gap: Int) {
            val next = top.toLong() + height + gap
            if (next + PADDING > MAX_HEIGHT) problem(ShareRenderFailure.TOO_TALL)
            top = next.toInt()
        }

        fun text(value: String, size: Float, bold: Boolean = false) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(30, 38, 46)
                textSize = size
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textLocale = Locale.KOREA
            }
            val textLayout = StaticLayout.Builder.obtain(value, 0, value.length, paint, CONTENT_WIDTH)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setIncludePad(true)
                .setLineSpacing(8f, 1f)
                .build()
            val start = top
            advance(textLayout.height, TEXT_GAP)
            blocks += TextBlock(start, textLayout)
        }

        text(lines[lineIndex++], 42f, bold = true)
        advance(0, SECTION_GAP)
        for (item in draft.items) {
            currentCoroutineContext().ensureActive()
            text(lines[lineIndex++], 46f, bold = true)
            for (uri in item.photoUris) {
                val info = inspectPhoto(uri, photoIndex)
                val orientedWidth = if (info.rotation % 180 == 0) info.width else info.height
                val orientedHeight = if (info.rotation % 180 == 0) info.height else info.width
                val height = ceil(CONTENT_WIDTH.toDouble() * orientedHeight / orientedWidth)
                if (height > MAX_HEIGHT) problem(ShareRenderFailure.TOO_TALL, photoIndex)
                val start = top
                advance(height.toInt().coerceAtLeast(1), PHOTO_GAP)
                blocks += PhotoBlock(start, uri, photoIndex++, info, height.toInt().coerceAtLeast(1))
            }
            if (item.caption != null) text(lines[lineIndex++], 34f)
            advance(0, SECTION_GAP)
        }
        text("기억 저장소", 28f)
        return ImageLayout(top + PADDING, blocks)
    }

    private suspend fun inspectPhoto(uri: String, index: Int): PhotoInfo {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        withPhotoStream(uri, index) { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) problem(ShareRenderFailure.INVALID_PHOTO, index)
        val orientation = if (ExifInterface.isSupportedMimeType(bounds.outMimeType.orEmpty())) {
            withPhotoStream(uri, index) { stream ->
                val exif = ExifInterface(stream)
                exif.rotationDegrees to exif.isFlipped
            }
        } else 0 to false
        return PhotoInfo(bounds.outWidth, bounds.outHeight, orientation.first, orientation.second)
    }

    private suspend fun drawPhoto(canvas: Canvas, block: PhotoBlock) {
        val rotated = block.info.rotation % 180 != 0
        val desiredWidth = if (rotated) block.height else CONTENT_WIDTH
        val desiredHeight = if (rotated) CONTENT_WIDTH else block.height
        var sample = 1
        while (sample <= Int.MAX_VALUE / 2 && block.info.width / (sample * 2) >= desiredWidth &&
            block.info.height / (sample * 2) >= desiredHeight) sample *= 2
        while (sampledPixels(block.info, sample) > PHOTO_BYTES / 4L) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        withPhotoStream(block.uri, block.index) { stream ->
            val photo = BitmapFactory.decodeStream(stream, null, options)
                ?: problem(ShareRenderFailure.INVALID_PHOTO, block.index)
            try {
                if (photo.allocationByteCount.toLong() > PHOTO_BYTES) problem(ShareRenderFailure.MEMORY_LIMIT, block.index)
                val matrix = Matrix()
                if (block.info.flipped) matrix.postScale(-1f, 1f)
                matrix.postRotate(block.info.rotation.toFloat())
                val bounds = RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
                matrix.mapRect(bounds)
                matrix.postTranslate(-bounds.left, -bounds.top)
                matrix.postScale(CONTENT_WIDTH / bounds.width(), block.height / bounds.height())
                matrix.postTranslate(PADDING.toFloat(), block.top.toFloat())
                canvas.drawBitmap(photo, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            } finally { photo.recycle() }
        }
    }

    private fun sampledPixels(info: PhotoInfo, sample: Int): Long {
        val width = (info.width.toLong() + sample - 1) / sample
        val height = (info.height.toLong() + sample - 1) / sample
        return width * height
    }

    private suspend fun <T> withPhotoStream(uri: String, index: Int, read: (InputStream) -> T): T {
        currentCoroutineContext().ensureActive()
        try {
            val stream = source.open(uri) ?: problem(ShareRenderFailure.PHOTO_UNAVAILABLE, index)
            return stream.use {
                currentCoroutineContext().ensureActive()
                read(it)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RenderProblem) {
            throw failure
        } catch (_: SecurityException) {
            problem(ShareRenderFailure.PHOTO_UNAVAILABLE, index)
        } catch (_: IOException) {
            problem(ShareRenderFailure.PHOTO_UNAVAILABLE, index)
        } catch (_: RuntimeException) {
            problem(ShareRenderFailure.INVALID_PHOTO, index)
        }
    }

    private sealed interface Block { val top: Int }
    private data class TextBlock(override val top: Int, val layout: StaticLayout) : Block
    private data class PhotoBlock(override val top: Int, val uri: String, val index: Int, val info: PhotoInfo, val height: Int) : Block
    private data class PhotoInfo(val width: Int, val height: Int, val rotation: Int, val flipped: Boolean)
    private data class ImageLayout(val height: Int, val blocks: List<Block>)
    private class RenderProblem(val reason: ShareRenderFailure, val photoIndex: Int?) : RuntimeException()

    private fun problem(reason: ShareRenderFailure, photoIndex: Int? = null): Nothing = throw RenderProblem(reason, photoIndex)

    private companion object {
        const val WIDTH = 1080
        const val MAX_HEIGHT = 12000
        const val PADDING = 48
        const val CONTENT_WIDTH = WIDTH - PADDING * 2
        const val TEXT_GAP = 20
        const val PHOTO_GAP = 24
        const val SECTION_GAP = 32
        const val MAX_TEXT_CHARACTERS = 64000L
        const val PHOTO_BYTES = 16L * 1024 * 1024
        const val ENCODED_BYTES = 16 * 1024 * 1024
        const val OVERHEAD_BYTES = 8L * 1024 * 1024
    }
}
