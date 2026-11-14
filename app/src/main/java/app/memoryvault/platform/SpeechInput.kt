package app.memoryvault.platform

import kotlinx.coroutines.flow.Flow

sealed interface SpeechEvent {
    /** The adapter confirms that its sole microphone capture has actually started. */
    data object Started : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    /** Capture ended, but a final recognition callback may still follow. */
    data object RecognitionEnded : SpeechEvent
    /** The adapter classifies recognition failure without exposing external payloads. */
    data object Error : SpeechEvent
    data object CaptureError : SpeechEvent
}

/**
 * One session's speech boundary, owned only after the coordinator accepts its record call.
 * Collecting [events] starts recognition. Adapters must support cooperative cancellation,
 * keep callbacks scoped to this instance, and avoid logging speech or exception payloads.
 * Resource methods are synchronous and must return promptly.
 */
interface SpeechInput {
    /** Bound only after driving/single-session admission; legacy text-only adapters remain compatible. */
    fun bindSession(sessionId: String, capturedAt: Long) = Unit

    fun events(): Flow<SpeechEvent>

    /** Production capture persists its journal before [saveNote], then confirms the linked artifact. */
    suspend fun persistFinal(text: String, saveNote: suspend () -> Long): Long = saveNote()

    /** Explicit cancellation may discard only this session's unfinished temporary capture. */
    suspend fun discardIncomplete() = Unit

    /** Stops capture while retaining the listener that can deliver a late final result. */
    fun stopCapture()

    /** Stops the entire session, including capture and recognition callbacks. */
    fun cancel()

    /** Releases this session's resources, including listeners, even after [cancel]. */
    fun close()
}
