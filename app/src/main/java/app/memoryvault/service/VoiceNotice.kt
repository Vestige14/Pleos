package app.memoryvault.service

import app.memoryvault.domain.VoiceFailure
import app.memoryvault.domain.VoiceMemoOutcome
enum class VoiceNotice {
    CHECKING, PREPARING, RECORDING, PROCESSING, SAVED, SAVED_CLEANUP_WARNING,
    NOTHING_HEARD, TIMED_OUT, INTERRUPTED, STORAGE_FAILED, FAILED,
    FAILED_CLEANUP_WARNING, UNAVAILABLE,
}

fun VoiceServiceState.notice(): VoiceNotice = when (this) {
    VoiceServiceState.Idle -> VoiceNotice.UNAVAILABLE
    VoiceServiceState.CheckingVehicle -> VoiceNotice.CHECKING
    VoiceServiceState.Preparing -> VoiceNotice.PREPARING
    VoiceServiceState.Listening -> VoiceNotice.RECORDING
    VoiceServiceState.Processing -> VoiceNotice.PROCESSING
    is VoiceServiceState.Finished -> when (val result = result) {
        is VoiceServiceResult.NotStarted -> VoiceNotice.UNAVAILABLE
        is VoiceServiceResult.Interrupted -> VoiceNotice.INTERRUPTED
        is VoiceServiceResult.Completed -> when (val outcome = result.outcome) {
            is VoiceMemoOutcome.Saved -> if (outcome.cleanupFailed) VoiceNotice.SAVED_CLEANUP_WARNING else VoiceNotice.SAVED
            else -> when {
                outcome.cleanupFailed -> VoiceNotice.FAILED_CLEANUP_WARNING
                outcome is VoiceMemoOutcome.Cancelled -> VoiceNotice.INTERRUPTED
                outcome is VoiceMemoOutcome.NothingHeard -> VoiceNotice.NOTHING_HEARD
                outcome is VoiceMemoOutcome.TimedOut -> VoiceNotice.TIMED_OUT
                outcome is VoiceMemoOutcome.Failed && outcome.reason == VoiceFailure.STORAGE -> VoiceNotice.STORAGE_FAILED
                outcome is VoiceMemoOutcome.Failed -> VoiceNotice.FAILED
                else -> VoiceNotice.UNAVAILABLE
            }
        }
    }
}
