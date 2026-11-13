package app.memoryvault.platform

import kotlinx.coroutines.flow.Flow

sealed interface SpeechEvent {
    /** The SDK confirms that microphone recognition has actually started. */
    data object Started : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    /** Capture ended, but a final recognition callback may still follow. */
    data object RecognitionEnded : SpeechEvent
    /** The adapter classifies recognition failure without exposing external payloads. */
    data object Error : SpeechEvent
}

/**
 * One session's speech boundary, owned only after the coordinator accepts its record call.
 * Collecting [events] starts recognition. Adapters must support cooperative cancellation,
 * keep callbacks scoped to this instance, and avoid logging speech or exception payloads.
 * Resource methods are synchronous and must return promptly.
 */
interface SpeechInput {
    fun events(): Flow<SpeechEvent>

    /** Stops capture while retaining the listener that can deliver a late final result. */
    fun stopCapture()

    /** Stops the entire session, including capture and recognition callbacks. */
    fun cancel()

    /** Releases this session's resources, including listeners, even after [cancel]. */
    fun close()
}
