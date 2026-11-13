package app.memoryvault.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import java.util.Locale

/** The sole vendor boundary. Only ON_DEVICE Korean; no registration or fallback API. */
internal class AndroidPleosSpeechSdk(context: Context) : SpeechSdk {
    private val handler = Handler(Looper.getMainLooper())
    private val sdk = SpeechToText(context, Mode.ON_DEVICE)
    private var bridge: ResultListener? = null

    override fun initialize(callback: SpeechSdk.Initialization) {
        sdk.initialize(object : OnInitializeListener {
            override fun onSuccess() = dispatch { callback.ready() }
            override fun onFailure(errorType: ErrorType) = dispatch { callback.failed() }
        })
    }

    override fun setKoreanLocale() = sdk.setLocale(Locale.KOREA)

    override fun addListener(listener: SpeechSdk.Listener) {
        val result = object : ResultListener {
            override fun onReady() = Unit
            override fun onStartedRecognition() = dispatch { listener.started() }
            override fun onUpdated(stt: String, completed: Boolean) {
                if (completed) dispatch { listener.updated(stt, true) }
            }
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onEndedRecognition() = dispatch { listener.ended() }
            override fun onError(errorType: ErrorType) = dispatch { listener.failed() }
        }
        bridge = result
        sdk.addListener(result)
    }

    override fun request() = sdk.request()
    override fun stop() = sdk.stop()
    override fun removeListener(listener: SpeechSdk.Listener) {
        bridge?.let(sdk::removeListener)
    }
    override fun release() {
        try { sdk.release() } finally {
            bridge = null
            handler.removeCallbacksAndMessages(null)
        }
    }
    private fun dispatch(callback: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) callback() else handler.post { callback() }
    }
}
