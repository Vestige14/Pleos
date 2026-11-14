package app.memoryvault.platform.capture

import android.content.Context
import android.os.SystemClock
import app.memoryvault.platform.SpeechInput
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One private store per application process; never reads keys, preferences or network sessions. */
object CaptureRuntime {
    private var instance: PrivateCaptureStore? = null
    @Synchronized
    fun store(context: Context): PrivateCaptureStore = instance ?: PrivateCaptureStore(
        File(context.applicationContext.noBackupFilesDir, "voice-captures"),
    ).also { instance = it }

    fun newSpeechInput(context: Context): SpeechInput {
        val app = context.applicationContext
        val locations = AndroidCaptureLocationSource(app)
        return PcmSpeechInput(store(app), { AndroidPcmSource(app) }, { AndroidPcmRecognitionEngine(app) },
            locations::invoke, { CaptureMoment(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()) },
            engineDispatcher = Dispatchers.Main.immediate)
    }

    suspend fun recover(context: Context, findNote: suspend (String) -> CaptureNote?): List<CaptureArtifact> =
        withContext(Dispatchers.IO) { store(context).recover(findNote) }
}
