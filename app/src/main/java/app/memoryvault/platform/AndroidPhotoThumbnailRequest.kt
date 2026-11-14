package app.memoryvault.platform

import android.graphics.Bitmap
import android.os.CancellationSignal
import app.memoryvault.ui.PhotoThumbnailRequest

internal class AndroidPhotoThumbnailRequest(private val decode: (CancellationSignal) -> Bitmap) : PhotoThumbnailRequest<Bitmap> {
    private val signal = CancellationSignal()
    override fun read(): Bitmap = decode(signal)
    override fun cancel() = signal.cancel()
}
