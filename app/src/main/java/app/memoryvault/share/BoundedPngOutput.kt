package app.memoryvault.share

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Preallocating the cap prevents buffer growth/copy spikes while native PNG encoding writes. */
internal class BoundedPngOutput(private val limitBytes: Int) : ByteArrayOutputStream(limitBytes) {
    var limitReached: Boolean = false
        private set

    override fun write(value: Int) {
        checkSize(1)
        super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkSize(length)
        super.write(bytes, offset, length)
    }

    private fun checkSize(additionalBytes: Int) {
        if (additionalBytes > limitBytes - count) {
            limitReached = true
            throw IOException("PNG byte budget exceeded")
        }
    }
}
