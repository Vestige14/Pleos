package app.memoryvault.platform.capture

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import java.io.FileInputStream

/** Only the explicit parked play button creates a player. Late prepare rechecks live safety. */
class AndroidCaptureAudioPlayer : CaptureAudioPlayer {
    private var current: MediaPlayer? = null
    override fun play(file: File, allowed: () -> Boolean, onEnded: (Boolean) -> Unit) {
        close()
        if (!allowed()) return
        val acquired = MediaPlayer()
        current = acquired
        try {
            acquired.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            FileInputStream(file).use { acquired.setDataSource(it.fd) }
            acquired.setOnPreparedListener {
                if (current === acquired && allowed()) {
                    try { acquired.start() } catch (_: Exception) { close(); onEnded(false) }
                } else { if (current === acquired) close(); onEnded(false) }
            }
            acquired.setOnCompletionListener { if (current === acquired) { close(); onEnded(true) } }
            acquired.setOnErrorListener { _, _, _ -> if (current === acquired) { close(); onEnded(false) }; true }
            acquired.prepareAsync()
        } catch (failure: Exception) { close(); throw failure }
    }
    override fun close() { current?.let { current = null; it.release() } }
}
