package app.memoryvault.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Elapsed realtime marks for the current capture. No display duration is stored here. */
data class VoiceCaptureTiming(
    val startedAtElapsedMs: Long? = null,
    val endedAtElapsedMs: Long? = null,
) {
    companion object {
        val EMPTY: StateFlow<VoiceCaptureTiming> = MutableStateFlow(VoiceCaptureTiming()).asStateFlow()
    }
}
