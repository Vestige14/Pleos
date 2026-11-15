package app.memoryvault.platform.capture

import app.memoryvault.domain.*
import app.memoryvault.platform.SpeechEvent
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PcmSpeechInputTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun singleSourceFeedsExactReadBytesWithoutWavHeaderToSttAndJournalsBeforeNote() = runTest {
        val store = PrivateCaptureStore(temporary.root)
        val pcm = Source(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)))
        val engine = Engine()
        var sources = 0
        val input = PcmSpeechInput(store, { sources++; pcm }, { engine }, { null }, { CaptureMoment(700, 700_000_000) }, StandardTestDispatcher(testScheduler))
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { id, text, time ->
            assertEquals("session", id); assertEquals("  원문\n", text); assertEquals(99L, time)
            val ready = store.findBySession(id)!!
            assertEquals(CaptureState.NOTE_PENDING, ready.state)
            assertEquals(text, ready.transcript)
            assertTrue(File(ready.privateAudioPath).isFile)
            42L
        })
        assertEquals(VoiceMemoOutcome.Saved(42), voice.record("session", 99, input))
        assertEquals(1, sources)
        assertEquals(1, pcm.starts)
        assertEquals(1, pcm.closes)
        assertEquals(1, engine.closes)
        assertEquals(1, engine.completes)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), engine.audio)
        assertArrayEquals(engine.audio, store.readPcm("session"))
        val saved = store.findBySession("session")!!
        assertEquals(CaptureState.COMMITTED, saved.state)
        assertEquals(700L, saved.capturedAt)
        assertEquals(99L, saved.noteCapturedAt)
        assertNull(saved.location)
    }

    @Test fun synchronousFinalDuringSendWaitsUntilCompleteAudioSendReturnsAndDuplicateFinalIsIgnored() = runTest {
        val store = PrivateCaptureStore(temporary.root)
        val engine = Engine().apply { finalDuringSend = true }
        var writes = 0
        val input = PcmSpeechInput(store, { Source(listOf(byteArrayOf(1, 2))) }, { engine }, io = StandardTestDispatcher(testScheduler))
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ ->
            assertEquals(1, engine.completes); writes++; 42
        })
        assertEquals(VoiceMemoOutcome.Saved(42), voice.record("s", 99, input))
        engine.callback!!.final("late")
        assertEquals(1, writes)
        assertEquals("  원문\n", store.findBySession("s")!!.transcript)
    }

    @Test fun sttErrorAndBlankFinalKeepCompletedAudioWithoutCreatingNote() = runTest {
        for (blank in listOf(false, true)) {
            val store = PrivateCaptureStore(temporary.newFolder())
            val engine = Engine().apply { error = !blank; finalText = if (blank) " \n" else "raw" }
            var saves = 0
            val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> saves++; 42 })
            val input = PcmSpeechInput(store, { Source(listOf(byteArrayOf(1, 2))) }, { engine }, io = StandardTestDispatcher(testScheduler))
            val result = voice.record("s", 99, input)
            if (blank) assertEquals(VoiceMemoOutcome.NothingHeard(), result)
            else assertEquals(VoiceMemoOutcome.Failed(VoiceFailure.RECOGNITION), result)
            assertEquals(0, saves)
            assertEquals(CaptureState.STT_FAILED, PrivateCaptureStore(File(store.findBySession("s")!!.privateAudioPath).parentFile!!).findBySession("s")!!.state)
            assertArrayEquals(byteArrayOf(1, 2), store.readPcm("s"))
        }
    }

    @Test fun finalTimeoutRetainsRawAndStopsAtFifteenWhileFinalDeadlineRemainsTwenty() = runTest {
        val store = PrivateCaptureStore(temporary.root)
        val source = Source(listOf(byteArrayOf(1, 2)), waitForStop = true)
        val engine = Engine().apply { noFinal = true }
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> fail("No final"); 1 })
        val input = PcmSpeechInput(store, { source }, { engine }, io = StandardTestDispatcher(testScheduler))
        val result = async { voice.record("s", 99, input) }
        runCurrent()
        assertEquals(VoiceState.Listening, voice.state.value)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(1, source.stops)
        assertEquals(VoiceState.Processing, voice.state.value)
        assertFalse(result.isCompleted)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(VoiceMemoOutcome.TimedOut(), result.await())
        assertArrayEquals(byteArrayOf(1, 2), store.readPcm("s"))
        assertEquals(CaptureState.STT_FAILED, store.findBySession("s")!!.state)
    }

    @Test fun explicitCancelAfterUnknownDeletesOnlyOwnUnfinishedSessionAndLateReadyCannotRestart() = runTest {
        for (unknown in listOf(false, true)) {
            val store = PrivateCaptureStore(temporary.newFolder())
            val committed = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> 77L })
            assertEquals(VoiceMemoOutcome.Saved(77L), committed.record("keep", 98L,
                PcmSpeechInput(store, { Source(listOf(byteArrayOf(7, 8))) }, { Engine() },
                    io = StandardTestDispatcher(testScheduler))))
            assertArrayEquals(byteArrayOf(7, 8), store.readPcm("keep"))
            val source = Source(listOf(byteArrayOf(1, 2)), waitForStop = true)
            val engine = Engine()
            val drive = MutableStateFlow(DriveState.DRIVING)
            var saves = 0
            val voice = VoiceMemoUseCase(drive, { _, _, _ -> saves++; 42 })
            val input = PcmSpeechInput(store, { source }, { engine }, io = StandardTestDispatcher(testScheduler))
            val result = async { voice.record("s", 99, input) }
            runCurrent()
            if (unknown) {
                drive.value = DriveState.UNKNOWN
                runCurrent()
                assertFalse("Vehicle uncertainty cannot cancel a user-started capture", result.isCompleted)
                assertNotNull("An unfinished capture is not discarded by vehicle state", store.findBySession("s"))
            }
            voice.cancel()
            runCurrent()
            assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER), result.await())
            engine.ready!!(); engine.callback!!.final("late")
            runCurrent()
            assertEquals(1, source.starts)
            assertEquals(1, source.closes)
            assertEquals(0, saves)
            assertNull(store.findBySession("s"))
            assertArrayEquals(byteArrayOf(7, 8), store.readPcm("keep"))
        }
    }

    @Test fun initPastCaptureDeadlineNeverConstructsRecorder() = runTest {
        val store = PrivateCaptureStore(temporary.root)
        val engine = Engine().apply { immediateReady = false }
        var starts = 0
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> 42 })
        val input = PcmSpeechInput(store, { starts++; Source(emptyList()) }, { engine }, io = StandardTestDispatcher(testScheduler))
        val result = async { voice.record("s", 99, input) }
        runCurrent(); advanceTimeBy(15_000); runCurrent()
        engine.ready!!(); runCurrent()
        assertEquals(0, starts)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(VoiceMemoOutcome.TimedOut(), result.await())
        assertNull(store.findBySession("s"))
    }

    @Test fun byteLimitIsExactlyFifteenSecondsEvenIfSourceReturnsExcess() = runTest {
        val store = PrivateCaptureStore(temporary.root)
        val engine = Engine()
        val source = Source(List(151) { ByteArray(3_200) { 7 } })
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> 42 })
        val input = PcmSpeechInput(store, { source }, { engine }, io = StandardTestDispatcher(testScheduler))
        assertEquals(VoiceMemoOutcome.Saved(42), voice.record("s", 99, input))
        assertEquals(480_000, engine.audio!!.size)
        assertEquals(480_000L, store.findBySession("s")!!.byteCount)
        assertEquals(150, source.reads)
    }

    @Test fun oddFinalPcmAndRecorderFailureNeverBecomeTextOnlySuccess() = runTest {
        for (brokenStart in listOf(false, true)) {
            val store = PrivateCaptureStore(temporary.newFolder())
            val engine = Engine()
            val source = Source(listOf(byteArrayOf(1, 2, 3)), brokenStart = brokenStart)
            val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> fail("Capture failed"); 42 })
            val input = PcmSpeechInput(store, { source }, { engine }, io = StandardTestDispatcher(testScheduler))
            assertEquals(VoiceMemoOutcome.Failed(VoiceFailure.CAPTURE), voice.record("s", 99, input))
            assertNull(engine.audio)
            assertEquals(1, source.closes)
        }
    }

    @Test fun cancellationInsideRecorderAcquisitionCannotStartMicrophoneAfterCancellation() = runTest {
        val store = PrivateCaptureStore(temporary.root)
        val source = Source(listOf(byteArrayOf(1, 2)))
        val engine = Engine()
        lateinit var voice: VoiceMemoUseCase
        voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> fail("Cancelled"); 42 })
        val input = PcmSpeechInput(store, { voice.cancel(); source }, { engine }, io = StandardTestDispatcher(testScheduler))
        assertEquals(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER), voice.record("s", 99, input))
        assertEquals("Cancelled acquisition must not start microphone", 0, source.starts)
        assertEquals(1, source.closes)
        assertNull(store.findBySession("s"))
    }

    @Test fun oddByteBeyondTheFifteenSecondLimitDoesNotCorruptTheCompleteBoundedPcm() = runTest {
        val store = PrivateCaptureStore(temporary.root)
        val engine = Engine()
        val source = Source(List(149) { ByteArray(3_200) { 7 } } + listOf(ByteArray(3_198) { 7 }, byteArrayOf(7, 7, 99)))
        val voice = VoiceMemoUseCase(MutableStateFlow(DriveState.DRIVING), { _, _, _ -> 42 })
        val input = PcmSpeechInput(store, { source }, { engine }, io = StandardTestDispatcher(testScheduler))
        assertEquals(VoiceMemoOutcome.Saved(42), voice.record("s", 99, input))
        assertArrayEquals(ByteArray(480_000) { 7 }, engine.audio)
        assertArrayEquals(engine.audio, store.readPcm("s"))
    }

    private class Source(private val chunks: List<ByteArray>, private val waitForStop: Boolean = false,
        private val brokenStart: Boolean = false) : PcmSource {
        var starts = 0; var stops = 0; var closes = 0; var reads = 0
        private var index = 0
        override fun start() { starts++; if (brokenStart) error("capture unavailable") }
        override fun read(buffer: ByteArray): Int {
            if (index >= chunks.size) return if (waitForStop && stops == 0) 0 else -1
            val bytes = chunks[index++]; reads++
            buffer.fill(88); bytes.copyInto(buffer)
            return bytes.size
        }
        override fun stop() { stops++ }
        override fun close() { closes++ }
    }

    private class Engine : PcmRecognitionEngine {
        var ready: (() -> Unit)? = null
        var callback: PcmRecognitionEngine.Listener? = null
        var immediateReady = true; var finalDuringSend = false; var noFinal = false; var error = false
        var finalText = "  원문\n"; var audio: ByteArray? = null; var completes = 0; var closes = 0
        override fun initialize(listener: PcmRecognitionEngine.Listener, onReady: () -> Unit, onFailure: () -> Unit) {
            callback = listener; ready = onReady
            if (immediateReady) { onReady(); onReady() }
        }
        override fun sendAudio(pcm: ByteArray) { audio = pcm.copyOf(); if (finalDuringSend) callback!!.final(finalText) }
        override fun completeAudioSend() { completes++; if (error) callback!!.failed() else if (!noFinal) { callback!!.final(finalText); callback!!.final("duplicate") } }
        override fun close() { closes++ }
    }
}
