package app.memoryvault.share

import android.content.ContentResolver
import android.os.CancellationSignal
import androidx.core.net.toUri
import java.io.FilterInputStream
import java.io.InputStream

class AndroidSharePngRenderer(private val resolver: ContentResolver) : SharePngRenderer {
    override suspend fun render(draft: ShareDraft, guard: ShareExportGuard): ShareRenderResult =
        ShareImageRenderer(GuardedSharePhotoSource(resolver, guard)).render(draft)
}

/** Reopens selected URIs at publication/chooser boundaries, rather than trusting stale DB accessibility. */
class AndroidSharePhotoAccess(private val resolver: ContentResolver) : SharePhotoAccess {
    override fun canRead(uri: String, guard: ShareExportGuard): Boolean =
        GuardedSharePhotoSource(resolver, guard).open(uri)?.use { it.read() >= 0 } ?: false
}

/** CancellationSignal covers provider open; guarded reads cover synchronous stream/native decode boundaries. */
private class GuardedSharePhotoSource(
    private val resolver: ContentResolver,
    private val guard: ShareExportGuard,
) : SharePhotoSource {
    override fun open(uri: String): InputStream? {
        guard.check()
        val signal = CancellationSignal()
        val registration = guard.onCancel(signal::cancel)
        val descriptor = try {
            resolver.openAssetFileDescriptor(uri.toUri(), "r", signal)
        } catch (failure: Throwable) { registration.close(); throw failure }
        if (descriptor == null) { registration.close(); guard.check(); return null }
        try {
            guard.check()
            return object : FilterInputStream(descriptor.createInputStream()) {
                override fun read(): Int { guard.check(); val value = super.read(); guard.check(); return value }
                override fun read(bytes: ByteArray): Int = read(bytes, 0, bytes.size)
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    guard.check(); val count = super.read(bytes, offset, length); guard.check(); return count
                }
                override fun skip(count: Long): Long { guard.check(); val skipped = super.skip(count); guard.check(); return skipped }
                override fun close() {
                    try { super.close() } finally { try { descriptor.close() } finally { registration.close() } }
                }
            }
        } catch (failure: Throwable) {
            try { descriptor.close() } finally { registration.close() }
            throw failure
        }
    }
}
