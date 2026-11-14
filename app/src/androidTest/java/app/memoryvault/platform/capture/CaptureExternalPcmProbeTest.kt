package app.memoryvault.platform.capture

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Coordinator-only device spike. Digital silence, no AudioRecord, permission grant or network call.
 * ERROR is an explicit silence-recognition outcome, not successful Korean transcription.
 * Run this class separately from capture storage/UI checks and report its terminal kind honestly.
 */
class CaptureExternalPcmProbeTest {
    @Test fun onDeviceExternalPcmInitializesAndProducesBoundedTerminalWithoutAppMicrophone() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val sequence = AtomicInteger()
        val statusLock = Any()
        fun mark(phase: String) = synchronized(statusLock) {
            instrumentation.sendStatus(2, Bundle().apply {
                putString("external_pcm_sequence", sequence.incrementAndGet().toString())
                putString("external_pcm_elapsed_ms", ((SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000).toString())
                putString("external_pcm_phase", phase)
            })
        }
        val ready = CompletableDeferred<Boolean>()
        val terminal = CompletableDeferred<String>()
        mark("CONSTRUCT_BEFORE")
        val engine = withContext(Dispatchers.Main) { AndroidPcmRecognitionEngine(instrumentation.targetContext) }
        mark("CONSTRUCT_RETURNED")
        try {
            withTimeout(20_000) {
                withContext(Dispatchers.Main) {
                    mark("INITIALIZE_BEFORE")
                    engine.initialize(object : PcmRecognitionEngine.Listener {
                        override fun final(text: String) { mark("TERMINAL_FINAL_CALLBACK"); terminal.complete("FINAL") }
                        override fun failed() { mark("TERMINAL_ERROR_CALLBACK"); terminal.complete("ERROR") }
                    }, { mark("ON_READY"); ready.complete(true) }, { mark("ON_INITIALIZATION_FAILURE"); ready.complete(false) })
                    mark("INITIALIZE_RETURNED")
                }
                mark("AWAIT_READY_BEFORE")
                assertTrue("ON_DEVICE external PCM engine must initialize", ready.await())
                mark("AWAIT_READY_RETURNED")
                val pcm = ByteArray(32_000) // One second PCM16LE/16kHz/mono; no WAV header or voice.
                withContext(Dispatchers.Main) {
                    mark("SEND_BEFORE")
                    engine.sendAudio(pcm)
                    mark("SEND_RETURNED")
                    mark("COMPLETE_BEFORE")
                    engine.completeAudioSend()
                    mark("COMPLETE_RETURNED")
                }
                mark("AWAIT_TERMINAL_BEFORE")
                val outcome = terminal.await()
                mark("AWAIT_TERMINAL_RETURNED")
                assertTrue(outcome == "FINAL" || outcome == "ERROR")
                mark("TERMINAL_ASSERTION_PASSED")
            }
        } finally {
            mark("CLEANUP_BEFORE")
            try {
                withContext(Dispatchers.Main) { engine.close() }
                mark("CLEANUP_RETURNED")
            } finally { mark("CLEANUP_FINALLY_EXIT") }
        }
    }
}
