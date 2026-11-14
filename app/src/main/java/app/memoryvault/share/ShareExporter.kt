package app.memoryvault.share

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

interface ShareGalleryIo {
    fun createPending(displayName: String): String?
    fun openOutput(uri: String): OutputStream?
    fun publish(uri: String): Boolean
    /** Must atomically constrain deletion to this URI while it is still pending. */
    fun deletePending(uri: String)
}
fun interface SharePngRenderer { suspend fun render(draft: ShareDraft, guard: ShareExportGuard): ShareRenderResult }
fun interface SharePhotoAccess { fun canRead(uri: String, guard: ShareExportGuard): Boolean }
fun interface ShareChooser { suspend fun open(uri: String): Boolean }
enum class ShareExportFailure { PHOTO_UNAVAILABLE, TOO_LARGE, RENDER_FAILED, STORAGE_FAILED }
sealed interface ShareExportResult {
    /** A dispatch acknowledgement is not evidence of a completed external share. */
    data class Saved(val uri: String, val chooserDispatched: Boolean, val receiptPersisted: Boolean = true) : ShareExportResult
    data class Failed(val reason: ShareExportFailure) : ShareExportResult
    data object Blocked : ShareExportResult
    data object Busy : ShareExportResult
}

private class ShareOperationBlocked : CancellationException("Sharing is no longer permitted")
private class ShareCleanupFailure : IOException("Pending image cleanup failed")

/** Guards synchronous reads too; cancellation hooks cancel Android CancellationSignals promptly. */
class ShareExportGuard internal constructor(
    private val gate: ShareSafetyGate,
    private val epoch: ShareEpoch,
    private val job: Job,
) {
    private val lock = Any()
    private var cancelled = false
    private val cancellations = mutableSetOf<() -> Unit>()

    fun check() {
        job.ensureActive()
        if (synchronized(lock) { cancelled } || !gate.allows(epoch)) throw ShareOperationBlocked()
    }

    fun onCancel(action: () -> Unit): AutoCloseable {
        synchronized(lock) { if (cancelled) action() else cancellations.add(action) }
        return AutoCloseable { synchronized(lock) { cancellations.remove(action) } }
    }

    internal fun cancel() {
        val actions = synchronized(lock) {
            cancelled = true
            cancellations.toList().also { cancellations.clear() }
        }
        actions.forEach { runCatching { it() } }
    }
}

/** Runs on the caller's worker dispatcher. Only the chooser adapter switches to the main thread. */
class ShareExporter(
    private val gate: ShareSafetyGate,
    private val gallery: ShareGalleryIo,
    private val renderer: SharePngRenderer,
    private val photoAccess: SharePhotoAccess,
    private val chooser: ShareChooser,
    private val displayName: () -> String,
    private val receipts: ShareExportReceiptStore,
) {
    private val exporting = Mutex()

    suspend fun export(draft: ShareDraft): ShareExportResult {
        currentCoroutineContext().ensureActive()
        val epoch = gate.capture() ?: return ShareExportResult.Blocked
        if (!exporting.tryLock()) return ShareExportResult.Busy
        try {
            return coroutineScope {
                val guard = AtomicReference<ShareExportGuard?>()
                val work = async(start = CoroutineStart.LAZY) {
                    val activeGuard = ShareExportGuard(gate, epoch, currentCoroutineContext().job)
                    guard.set(activeGuard)
                    perform(draft, activeGuard)
                }
                // This collector is independent of lifecycle-aware UI collection.
                val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                    gate.changes.collect {
                        if (!gate.allows(epoch)) { guard.get()?.cancel(); work.cancel(ShareOperationBlocked()) }
                    }
                }
                try {
                    work.start()
                    work.await()
                } catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                    if (!gate.allows(epoch)) ShareExportResult.Blocked else throw cancelled
                } finally {
                    guard.get()?.cancel()
                    observer.cancel()
                    work.cancel()
                }
            }
        } finally { exporting.unlock() }
    }

    private suspend fun perform(draft: ShareDraft, guard: ShareExportGuard): ShareExportResult {
        var pending: String? = null
        var published = false
        try {
            guard.check()
            if (!canReadPhotos(draft, guard)) return ShareExportResult.Failed(ShareExportFailure.PHOTO_UNAVAILABLE)
            val result = renderer.render(draft, guard)
            guard.check()
            if (result is ShareRenderResult.Failure) return ShareExportResult.Failed(when (result.reason) {
                ShareRenderFailure.PHOTO_UNAVAILABLE -> ShareExportFailure.PHOTO_UNAVAILABLE
                ShareRenderFailure.TOO_TALL, ShareRenderFailure.MEMORY_LIMIT -> ShareExportFailure.TOO_LARGE
                else -> ShareExportFailure.RENDER_FAILED
            })
            val png = result as ShareRenderResult.Png
            guard.check()
            val filename = displayName()
            val uri = gallery.createPending(filename) ?: return ShareExportResult.Failed(ShareExportFailure.STORAGE_FAILED)
            pending = uri
            guard.check()
            val stream = gallery.openOutput(uri) ?: return ShareExportResult.Failed(ShareExportFailure.STORAGE_FAILED)
            stream.use { output ->
                var offset = 0
                while (offset < png.bytes.size) {
                    guard.check()
                    val count = minOf(16 * 1024, png.bytes.size - offset)
                    output.write(png.bytes, offset, count)
                    guard.check()
                    offset += count
                }
                output.flush()
            }
            guard.check()
            if (!canReadPhotos(draft, guard)) return ShareExportResult.Failed(ShareExportFailure.PHOTO_UNAVAILABLE)
            guard.check()
            if (!gallery.publish(uri)) return ShareExportResult.Failed(ShareExportFailure.STORAGE_FAILED)
            // Set this in the same worker context as the synchronous provider commit, before any suspension.
            published = true
            // Publication is already a fact. Finish this app-private receipt even if departure/ON_STOP cancels the UI.
            val receiptPersisted = withContext(NonCancellable) {
                try { receipts.save(ShareExportReceipt(uri, filename, draft.date)); true }
                catch (_: Exception) { false }
            }
            guard.check()
            if (!receiptPersisted) return ShareExportResult.Saved(uri, chooserDispatched = false, receiptPersisted = false)
            if (!canReadPhotos(draft, guard)) return ShareExportResult.Failed(ShareExportFailure.PHOTO_UNAVAILABLE)
            guard.check()
            val dispatched = try { chooser.open(uri) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            guard.check()
            return ShareExportResult.Saved(uri, dispatched)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ShareExportResult.Failed(ShareExportFailure.STORAGE_FAILED)
        } finally {
            if (pending != null && !published) withContext(NonCancellable) {
                // The provider may commit then throw. A conditional deletion must never remove a public image.
                try { gallery.deletePending(pending) } catch (_: Exception) { throw ShareCleanupFailure() }
            }
        }
    }

    private fun canReadPhotos(draft: ShareDraft, guard: ShareExportGuard): Boolean {
        for (uri in draft.items.flatMap { it.photoUris }.distinct()) {
            guard.check()
            val readable = try { photoAccess.canRead(uri, guard) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            guard.check()
            if (!readable) return false
        }
        return true
    }
}
