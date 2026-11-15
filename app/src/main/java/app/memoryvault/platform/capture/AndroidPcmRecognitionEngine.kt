package app.memoryvault.platform.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import java.util.Locale

/** ON_DEVICE Korean external PCM only. Never calls request(), registration or a fallback. */
class AndroidPcmRecognitionEngine(context: Context) : PcmRecognitionEngine {
    private val sdk = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
    private val handler = Handler(Looper.getMainLooper())
    private var result: ResultListener? = null
    private val cleanup = PcmEngineCleanup(
        stop = { sdk.stop() },
        removeListener = { result?.let(sdk::removeListener) },
        release = { sdk.release() },
        clear = { result = null; handler.removeCallbacksAndMessages(null) },
    )
    private val closed: Boolean get() = cleanup.closed
    private var initialized = false

    override fun initialize(listener: PcmRecognitionEngine.Listener, onReady: () -> Unit, onFailure: () -> Unit) {
        sdk.initialize(object : OnInitializeListener {
            override fun onSuccess() = dispatch {
                if (!closed && !initialized) {
                    initialized = true
                    try {
                        sdk.setLocale(Locale.KOREA)
                        val bridge = object : ResultListener {
                            override fun onUpdated(stt: String, completed: Boolean) {
                                if (completed) dispatch {
                                    if (!closed) { cleanup.completed(); listener.final(stt) }
                                }
                            }
                            override fun onError(errorType: ErrorType) = dispatch { if (!closed) listener.failed() }
                            override fun onReady() = Unit
                            override fun onStartedRecognition() = Unit // SDK processing is not microphone capture.
                            override fun onEndedRecognition() = Unit
                            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                            override fun onUpdatedRms(rms: Double) = Unit
                        }
                        result = bridge // Remove even after a partially failed addListener.
                        sdk.addListener(bridge)
                        onReady()
                    } catch (_: Exception) { onFailure() }
                    catch (_: LinkageError) { onFailure() }
                }
            }
            override fun onFailure(errorType: ErrorType) = dispatch { if (!closed && !initialized) { initialized = true; onFailure() } }
        })
    }

    override fun sendAudio(pcm: ByteArray) { check(!closed && initialized); sdk.sendAudio(pcm) }
    override fun completeAudioSend() { check(!closed && initialized); sdk.completeAudioSend() }
    override fun close() = cleanup.close()
    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }
}
