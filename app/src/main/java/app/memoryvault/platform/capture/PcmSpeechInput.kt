package app.memoryvault.platform.capture

import app.memoryvault.platform.SpeechInput
import app.memoryvault.platform.SpeechEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface PcmSource {
    fun start()
    fun read(buffer: ByteArray): Int
    fun stop()
    fun close()
}

/** External-PCM interface deliberately has no microphone request() operation. */
interface PcmRecognitionEngine {
    interface Listener { fun final(text: String); fun failed() }
    fun initialize(listener: Listener, onReady: () -> Unit, onFailure: () -> Unit)
    fun sendAudio(pcm: ByteArray)
    fun completeAudioSend()
    fun close()
}

class PcmSpeechInput(
    private val store: PrivateCaptureStore,
    private val sourceFactory: () -> PcmSource,
    private val engineFactory: () -> PcmRecognitionEngine,
    private val location: (CaptureMoment) -> CaptureLocation? = { null },
    private val clock: () -> CaptureMoment = { CaptureMoment(System.currentTimeMillis(), System.nanoTime()) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val engineDispatcher: CoroutineDispatcher = io,
) : SpeechInput {
    private val lock = Any()
    private var sessionId: String? = null
    private var noteCapturedAt = 0L
    private var consumed = false
    private var closed = false
    private var captureStopped = false
    private var sourceStopRequested = false
    private var source: PcmSource? = null
    private var engine: PcmRecognitionEngine? = null
    private var sink: SendChannel<SpeechEvent>? = null
    private var submitting = false
    private var submitted = false
    private var terminalDelivered = false
    private var pendingTerminal: SpeechEvent? = null
    private var cleanupFailure: Throwable? = null

    override fun bindSession(sessionId: String, capturedAt: Long) = synchronized(lock) {
        check(!consumed && !closed && this.sessionId == null) { "Capture already bound" }
        this.sessionId = sessionId
        noteCapturedAt = capturedAt
    }

    override fun events(): Flow<SpeechEvent> = callbackFlow {
        val owns = synchronized(lock) {
            if (consumed || closed) false else { consumed = true; sink = channel; true }
        }
        if (!owns) { close(); return@callbackFlow }
        val ready = CompletableDeferred<Boolean>()
        val id = synchronized(lock) { checkNotNull(sessionId) { "Capture session unbound" } }
        try {
            synchronized(lock) {
                if (!closed) {
                    val acquired = engineFactory()
                    engine = acquired
                    acquired.initialize(object : PcmRecognitionEngine.Listener {
                        override fun final(text: String) = receiveTerminal(SpeechEvent.Final(text))
                        override fun failed() = receiveTerminal(SpeechEvent.Error)
                    }, { ready.complete(true) }, { ready.complete(false) })
                }
            }
        } catch (_: Exception) { ready.complete(false) }
        catch (_: LinkageError) { ready.complete(false) }

        launch(io) {
            var acquired: PcmSource? = null
            try {
                if (!ready.await()) { deliver(SpeechEvent.Error); return@launch }
                if (synchronized(lock) { closed || captureStopped }) return@launch
                val moment = clock()
                val fix = try { location(moment) } catch (_: Exception) { null }
                currentCoroutineContext().ensureActive()
                if (synchronized(lock) { closed || captureStopped }) return@launch
                val writer = store.begin(id, moment.wallTime, noteCapturedAt, fix)
                val captureContext = currentCoroutineContext()
                synchronized(lock) {
                    captureContext.ensureActive()
                    if (!closed && !captureStopped) {
                        acquired = sourceFactory()
                        source = acquired
                        captureContext.ensureActive()
                        acquired!!.start()
                        captureContext.ensureActive()
                        deliver(SpeechEvent.Started)
                    }
                }
                val microphone = acquired ?: return@launch
                val buffer = ByteArray(3_200)
                var total = 0
                var odd: Byte? = null
                while (total < 480_000 && !synchronized(lock) { closed || captureStopped }) {
                    currentCoroutineContext().ensureActive()
                    val read = microphone.read(buffer)
                    if (read == -1) break
                    check(read in 0..buffer.size) { "Invalid PCM read" }
                    if (read == 0) { delay(5); continue }
                    // Preserve a split PCM16 sample; never pad, send stale buffer bytes or a WAV header.
                    val combined = ByteArray(read + if (odd == null) 0 else 1)
                    odd?.let { combined[0] = it }
                    buffer.copyInto(combined, if (odd == null) 0 else 1, 0, read)
                    val count = minOf(combined.size - combined.size % 2, 480_000 - total)
                    odd = if (total + count < 480_000 && combined.size % 2 != 0) combined.last() else null
                    if (count > 0) { writer.append(combined, count); total += count }
                }
                currentCoroutineContext().ensureActive()
                if (synchronized(lock) { closed }) return@launch
                check(odd == null) { "Incomplete PCM16 sample" }
                writer.complete()
                stopSource()
                deliver(SpeechEvent.RecognitionEnded)
                val pcm = store.readPcm(id)
                withContext(engineDispatcher) {
                    synchronized(lock) {
                        if (!closed) {
                            submitting = true
                            try {
                                engine!!.sendAudio(pcm)
                                engine!!.completeAudioSend()
                                submitted = true
                            } catch (_: Exception) { pendingTerminal = SpeechEvent.Error; submitted = true }
                            catch (_: LinkageError) { pendingTerminal = SpeechEvent.Error; submitted = true }
                            finally { submitting = false }
                            pendingTerminal?.let(::deliverTerminal)
                        }
                    }
                }
                awaitCancellation() // Keep the bounded late-final listener until the coordinator completes.
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { deliver(SpeechEvent.CaptureError) }
            finally {
                withContext(NonCancellable + io) {
                    runCatching { stopSource() }.exceptionOrNull()?.let(::rememberCleanupFailure)
                    runCatching { acquired?.close() }.exceptionOrNull()?.let(::rememberCleanupFailure)
                    synchronized(lock) { source = null }
                    runCatching { store.interrupt(id); store.failRecognition(id) }.exceptionOrNull()?.let(::rememberCleanupFailure)
                }
            }
        }
        awaitClose { teardown() }
    }

    override suspend fun persistFinal(text: String, saveNote: suspend () -> Long): Long = withContext(io) {
        val id = synchronized(lock) { checkNotNull(sessionId) }
        store.persistTranscript(id, text) { currentCoroutineContext().ensureActive(); saveNote() }
    }

    override suspend fun discardIncomplete() = withContext(io) {
        synchronized(lock) { sessionId }?.let(store::discardIncomplete)
        Unit
    }

    override fun stopCapture() { synchronized(lock) { captureStopped = true }; stopSource() }
    override fun cancel() { teardown(); synchronized(lock) { cleanupFailure }?.let { throw it } }
    override fun close() = cancel()

    private fun receiveTerminal(event: SpeechEvent) = synchronized(lock) {
        if (closed || terminalDelivered || (!submitting && !submitted)) return@synchronized
        if (pendingTerminal == null) pendingTerminal = event
        if (submitted && !submitting) deliverTerminal(checkNotNull(pendingTerminal))
    }
    private fun deliverTerminal(event: SpeechEvent) {
        if (!terminalDelivered && !closed) { terminalDelivered = true; sink?.trySend(event) }
    }
    private fun deliver(event: SpeechEvent) = synchronized(lock) { if (!closed) sink?.trySend(event); Unit }
    private fun rememberCleanupFailure(failure: Throwable) = synchronized(lock) { if (cleanupFailure == null) cleanupFailure = failure }
    private fun stopSource() {
        val current = synchronized(lock) {
            if (sourceStopRequested || source == null) null else { sourceStopRequested = true; source }
        }
        current?.stop()
    }
    private fun teardown() {
        val acquired = synchronized(lock) {
            if (closed) return
            closed = true; captureStopped = true
            sink?.close(); sink = null
            engine.also { engine = null }
        }
        runCatching { stopSource() }.exceptionOrNull()?.let(::rememberCleanupFailure)
        runCatching { acquired?.close() }.exceptionOrNull()?.let(::rememberCleanupFailure)
    }
}
