package app.memoryvault.platform.capture

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Coordinator-only contrast probe. Local synthetic Korean fixture, no AudioRecord or external API.
 * Only a nonblank FINAL passes; ERROR and timeout remain failures. No transcript enters status logs.
 * Run this class separately from capture storage/UI checks and report its terminal kind honestly.
 */
class CaptureSyntheticSpeechProbeTest {
    @Test fun onDeviceSyntheticKoreanPcmProducesNonblankFinalWithoutAppMicrophone() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expected = "오늘 바다에서 본 노을이 좋았어"
        fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).filter { it.isLetterOrDigit() }
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val sequence = AtomicInteger()
        val statusLock = Any()
        fun mark(phase: String, characters: Int? = null, matches: Boolean? = null) = synchronized(statusLock) {
            instrumentation.sendStatus(2, Bundle().apply {
                putString("external_pcm_sequence", sequence.incrementAndGet().toString())
                putString("external_pcm_elapsed_ms", ((SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000).toString())
                putString("external_pcm_phase", phase)
                characters?.let { putString("external_pcm_final_chars", it.toString()) }
                matches?.let { putString("external_pcm_expected_normalized_match", it.toString()) }
            })
        }
        val ready = CompletableDeferred<Boolean>()
        val terminal = CompletableDeferred<String>()
        val finalText = AtomicReference<String?>(null)
        mark("CONSTRUCT_BEFORE")
        val engine = withContext(Dispatchers.Main) { AndroidPcmRecognitionEngine(instrumentation.targetContext) }
        mark("CONSTRUCT_RETURNED")
        try {
            withTimeout(20_000) {
                withContext(Dispatchers.Main) {
                    mark("INITIALIZE_BEFORE")
                    engine.initialize(object : PcmRecognitionEngine.Listener {
                        override fun final(text: String) {
                            mark("TERMINAL_FINAL_CALLBACK", text.length, normalized(text) == normalized(expected))
                            if (!terminal.isCompleted) finalText.set(text)
                            terminal.complete("FINAL")
                        }
                        override fun failed() { mark("TERMINAL_ERROR_CALLBACK"); terminal.complete("ERROR") }
                    }, { mark("ON_READY"); ready.complete(true) }, { mark("ON_INITIALIZATION_FAILURE"); ready.complete(false) })
                    mark("INITIALIZE_RETURNED")
                }
                mark("AWAIT_READY_BEFORE")
                assertTrue("ON_DEVICE external PCM engine must initialize", ready.await())
                mark("AWAIT_READY_RETURNED")
                val pcm = instrumentation.context.assets.open("capture-probe/synthetic-ko.pcm").use { it.readBytes() }
                assertEquals("Verified synthetic fixture size", 91_842, pcm.size)
                val digest = MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it) }
                assertEquals("c2b30ca68e5039137261a75837519727a7c88b6aa3be1053e43c586e791ea81f", digest)
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
                val text = finalText.get().orEmpty()
                mark("AWAIT_TERMINAL_RETURNED", text.length, normalized(text) == normalized(expected))
                assertTrue("Synthetic speech requires a nonblank FINAL", outcome == "FINAL" && text.isNotBlank())
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
