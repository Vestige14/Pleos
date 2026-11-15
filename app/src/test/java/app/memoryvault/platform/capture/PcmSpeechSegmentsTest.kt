package app.memoryvault.platform.capture

import app.memoryvault.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SDK double models the observed one-utterance FINAL, not a whole-recording transcript. */
@OptIn(ExperimentalCoroutinesApi::class)
class PcmSpeechSegmentsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val audio = tone(400) + ByteArray(19_200) + tone(400)

    @Test fun savesBothUtterancesOnceAndPreservesEveryOriginalAudioByte() = runTest {
        val first = Engine("first"); val second = Engine("second")
        val engines = mutableListOf(first, second)
        val store = PrivateCaptureStore(temporary.root)
        var saved: String? = null; var writes = 0
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, text, _ -> saved = text; writes++; 7 })
        val input = input(audio, store, StandardTestDispatcher(testScheduler)) { engines.removeAt(0) }
        assertEquals(VoiceMemoOutcome.Saved(7), voice.record("multi", 99, input))
        assertEquals("first second", saved)
        assertEquals(1, writes)
        assertArrayEquals(audio, store.readPcm("multi"))
        assertArrayEquals(audio, requireNotNull(first.audio) + requireNotNull(second.audio))
        assertEquals(1, first.closes); assertEquals(1, second.closes)
    }

    @Test fun secondUtteranceFailureNeverCommitsTheFirstAsACompleteMemo() = runTest {
        val engines = mutableListOf(Engine("first"), Engine(null))
        val store = PrivateCaptureStore(temporary.root)
        var writes = 0
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> writes++; 7 })
        val outcome = voice.record("multi", 99, input(audio, store, StandardTestDispatcher(testScheduler)) { engines.removeAt(0) })
        assertEquals(VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION), outcome)
        assertEquals(0, writes)
        assertArrayEquals(audio, store.readPcm("multi"))
        assertEquals(CaptureState.STT_FAILED, store.findBySession("multi")!!.state)
    }

    @Test fun blankSecondUtteranceDoesNotSilentlySavePartialText() = runTest {
        val engines = mutableListOf(Engine("first"), Engine(" \n"))
        val store = PrivateCaptureStore(temporary.root)
        var writes = 0
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> writes++; 7 })
        assertEquals(VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION), voice.record("multi", 99,
            input(audio, store, StandardTestDispatcher(testScheduler)) { engines.removeAt(0) }))
        assertEquals(0, writes)
    }

    @Test fun previousEnginesLateCallbacksCannotBecomeTheNextUtterance() = runTest {
        val first = Engine("first")
        val second = Engine("second", onSend = { first.listener!!.final("stale"); first.listener!!.failed() })
        val engines = mutableListOf(first, second)
        val store = PrivateCaptureStore(temporary.root)
        var saved: String? = null
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, text, _ -> saved = text; 7 })
        assertEquals(VoiceMemoOutcome.Saved(7), voice.record("multi", 99,
            input(audio, store, StandardTestDispatcher(testScheduler)) { engines.removeAt(0) }))
        assertEquals("first second", saved)
    }

    @Test fun cancellationWhileSecondUtteranceWaitsReleasesItWithoutSaving() = runTest {
        val first = Engine("first"); val second = Engine("second", wait = true)
        val engines = mutableListOf(first, second)
        val store = PrivateCaptureStore(temporary.root)
        var writes = 0
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> writes++; 7 })
        val work = async { voice.record("multi", 99, input(audio, store, StandardTestDispatcher(testScheduler)) { engines.removeAt(0) }) }
        runCurrent()
        assertNotNull(second.audio)
        voice.cancel(); runCurrent()
        assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER), work.await())
        assertEquals(0, writes); assertEquals(1, first.closes); assertEquals(1, second.closes)
    }

    @Test fun secondUtteranceIsStillBoundByTheOriginalOverallDeadline() = runTest {
        val first = Engine("first"); val second = Engine("second", wait = true)
        val engines = mutableListOf(first, second)
        val store = PrivateCaptureStore(temporary.root)
        var writes = 0
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> writes++; 7 })
        val work = async { voice.record("multi", 99, input(audio, store, StandardTestDispatcher(testScheduler)) { engines.removeAt(0) }) }
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        assertEquals(VoiceMemoOutcome.TimedOut(), work.await())
        assertEquals(0, writes); assertEquals(1, second.closes)
        assertArrayEquals(audio, store.readPcm("multi"))
    }

    @Test fun unknownDuringSecondUtteranceCancelsWithoutDiscardingTheCompletedAudio() = runTest {
        val first = Engine("first"); val second = Engine("second", wait = true)
        val engines = mutableListOf(first, second)
        val store = PrivateCaptureStore(temporary.root)
        val drive = MutableStateFlow(DriveState.DRIVING)
        var writes = 0
        val voice = VoiceMemoUseCase(drive, { _, _, _ -> writes++; 7 })
        val work = async { voice.record("multi", 99, input(audio, store, StandardTestDispatcher(testScheduler)) { engines.removeAt(0) }) }
        runCurrent(); assertNotNull(second.audio)
        drive.value = DriveState.UNKNOWN; runCurrent()
        assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.UNKNOWN), work.await())
        second.listener!!.final("too late")
        assertEquals(0, writes); assertEquals(1, first.closes); assertEquals(1, second.closes)
        assertArrayEquals(audio, store.readPcm("multi"))
    }

    private fun input(bytes: ByteArray, store: PrivateCaptureStore, dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        factory: () -> PcmRecognitionEngine) = PcmSpeechInput(store, {
        object : PcmSource {
            var offset = 0
            override fun start() = Unit
            override fun read(buffer: ByteArray): Int {
                if (offset == bytes.size) return -1
                val count = minOf(buffer.size, bytes.size - offset)
                bytes.copyInto(buffer, 0, offset, offset + count); offset += count
                return count
            }
            override fun stop() = Unit
            override fun close() = Unit
        }
    }, factory, io = dispatcher, engineDispatcher = dispatcher)

    private class Engine(val result: String?, val wait: Boolean = false, val onSend: () -> Unit = {}) : PcmRecognitionEngine {
        var listener: PcmRecognitionEngine.Listener? = null
        var audio: ByteArray? = null
        var closes = 0
        override fun initialize(listener: PcmRecognitionEngine.Listener, onReady: () -> Unit, onFailure: () -> Unit) {
            this.listener = listener; onReady()
        }
        override fun sendAudio(pcm: ByteArray) { audio = pcm.copyOf(); onSend() }
        override fun completeAudioSend() {
            if (!wait) { if (result == null) listener!!.failed() else listener!!.final(result) }
        }
        override fun close() { closes++; listener?.final("late-at-close") }
    }

    private fun tone(millis: Int): ByteArray = ByteArray(millis * 32).also { bytes ->
        for (i in bytes.indices step 2) { bytes[i] = 0xA0.toByte(); bytes[i + 1] = 0x0F }
    }
}
