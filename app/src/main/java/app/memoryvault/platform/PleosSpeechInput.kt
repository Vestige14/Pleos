package app.memoryvault.platform

import android.content.Context
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One-shot, inert until accepted and collected. No audio files or network recognizers. */
class PleosSpeechInput internal constructor(private val sdkFactory: () -> SpeechSdk) : SpeechInput {
    constructor(context: Context) : this({ AndroidPleosSpeechSdk(context.applicationContext) })

    private val lock = Any()
    private var consumed = false
    private var closed = false
    private var captureStopped = false
    private var requested = false
    private var registrationAttempted = false
    private var initializationHandled = false
    private var sdk: SpeechSdk? = null
    private var sink: SendChannel<SpeechEvent>? = null
    private var cleanupFailure: Throwable? = null
    private val listener = object : SpeechSdk.Listener {
        override fun started() = synchronized(lock) {
            if (!captureStopped) deliver(SpeechEvent.Started)
        }
        override fun updated(text: String, final: Boolean) = synchronized(lock) {
            // Partials are intentionally neither emitted nor retained by the production adapter.
            if (final) deliver(SpeechEvent.Final(text))
        }
        override fun ended() = synchronized(lock) {
            if (!closed) {
                requested = false
                captureStopped = true
                deliver(SpeechEvent.RecognitionEnded)
            }
        }
        override fun failed() = synchronized(lock) { deliver(SpeechEvent.Error) }
    }

    override fun events(): Flow<SpeechEvent> = callbackFlow {
        var ownsSession = false
        synchronized(lock) {
            if (consumed || closed) {
                close()
            } else {
                consumed = true
                ownsSession = true
                sink = channel
                try {
                    val acquired = sdkFactory()
                    sdk = acquired
                    acquired.initialize(object : SpeechSdk.Initialization {
                        override fun ready() = synchronized(lock) {
                            if (!closed && !captureStopped && !initializationHandled) {
                                initializationHandled = true
                                try {
                                    acquired.setKoreanLocale()
                                    registrationAttempted = true
                                    acquired.addListener(listener)
                                    // Both flags precede external request, including partial failures.
                                    requested = true
                                    acquired.request()
                                } catch (_: Exception) {
                                    deliver(SpeechEvent.Error)
                                } catch (_: LinkageError) {
                                    deliver(SpeechEvent.Error)
                                }
                            }
                        }
                        override fun failed() = synchronized(lock) {
                            if (!initializationHandled) {
                                initializationHandled = true
                                deliver(SpeechEvent.Error)
                            }
                        }
                    })
                } catch (_: Exception) {
                    deliver(SpeechEvent.Error)
                } catch (_: LinkageError) {
                    deliver(SpeechEvent.Error)
                }
            }
        }
        // Flow cancellation also owns resources. Preserve a cleanup failure for the
        // coordinator's explicit cancel/close so it accompanies the original outcome.
        awaitClose { if (ownsSession) cleanup() }
    }

    override fun stopCapture() = synchronized(lock) {
        captureStopped = true // Prevent a late initialization callback from requesting the mic.
        if (!closed && requested) {
            requested = false
            sdk?.stop()
        }
    }

    override fun cancel() {
        cleanup()
        synchronized(lock) { cleanupFailure }?.let { throw it }
    }

    override fun close() = cancel()

    private fun deliver(event: SpeechEvent) {
        if (!closed) sink?.trySend(event)
    }

    private fun cleanup() = synchronized(lock) {
        if (closed) return
        // Invalidate before calling the SDK; even synchronous teardown callbacks are stale.
        closed = true
        captureStopped = true
        val output = sink
        sink = null
        output?.close()
        val acquired = sdk
        sdk = null
        if (acquired != null) {
            val failures = mutableListOf<Throwable>()
            if (requested) runCatching { acquired.stop() }.exceptionOrNull()?.let(failures::add)
            requested = false
            if (registrationAttempted) {
                runCatching { acquired.removeListener(listener) }.exceptionOrNull()?.let(failures::add)
            }
            runCatching { acquired.release() }.exceptionOrNull()?.let(failures::add)
            cleanupFailure = failures.firstOrNull()
        }
    }
}
