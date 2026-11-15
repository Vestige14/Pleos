package app.memoryvault.ui.components

import app.memoryvault.domain.VoiceCaptureTiming
import app.memoryvault.domain.VoiceMemoOutcome
import app.memoryvault.service.VoiceServiceResult
import app.memoryvault.service.VoiceServiceState

/** UI-only deadlines. Keys contain numeric session marks, never audio or transcript content. */
internal class P2VoiceNoticeWindow {
    private data class SuccessKey(val noteId: Long, val timing: VoiceCaptureTiming)
    private val observedAt = mutableMapOf<SuccessKey, Long>()

    @Synchronized
    fun remainingMillis(voice: VoiceServiceState, timing: VoiceCaptureTiming, nowElapsedMs: Long): Long? {
        val result = (voice as? VoiceServiceState.Finished)?.result as? VoiceServiceResult.Completed ?: return null
        val saved = result.outcome as? VoiceMemoOutcome.Saved ?: return null
        if (saved.cleanupFailed) return null
        val first = observedAt.getOrPut(SuccessKey(saved.noteId, timing)) { nowElapsedMs }
        return (2_000L - (nowElapsedMs - first).coerceAtLeast(0L)).coerceAtLeast(0L)
    }
}
