package app.memoryvault.platform

import app.memoryvault.domain.VoiceCancelReason
import app.memoryvault.domain.VoiceFailure
import app.memoryvault.domain.VoiceMemoOutcome
import app.memoryvault.service.VoiceNotice
import app.memoryvault.service.VoiceServiceResult
import app.memoryvault.service.VoiceServiceState
import app.memoryvault.service.VoiceStartBlock
import app.memoryvault.service.notice
import org.junit.Assert.*
import org.junit.Test

class VoiceNoticeTest {
    @Test fun onlyActualStartedState_canDisplayRecording() {
        assertEquals(VoiceNotice.CHECKING, VoiceServiceState.CheckingVehicle.notice())
        assertEquals(VoiceNotice.PREPARING, VoiceServiceState.Preparing.notice())
        assertEquals(VoiceNotice.RECORDING, VoiceServiceState.Listening.notice())
        assertEquals(VoiceNotice.PROCESSING, VoiceServiceState.Processing.notice())
    }

    @Test fun saveAndCleanup_areReportedIndependently_andCancellationMakesNoStoragePromise() {
        fun notice(outcome: VoiceMemoOutcome) = VoiceServiceState.Finished(VoiceServiceResult.Completed(outcome)).notice()
        assertEquals(VoiceNotice.SAVED, notice(VoiceMemoOutcome.Saved(8L)))
        assertEquals(VoiceNotice.SAVED_CLEANUP_WARNING, notice(VoiceMemoOutcome.Saved(8L, true)))
        assertEquals(VoiceNotice.INTERRUPTED, notice(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER)))
        assertEquals(VoiceNotice.STORAGE_FAILED, notice(VoiceMemoOutcome.Failed(VoiceFailure.STORAGE)))
        assertEquals(VoiceNotice.FAILED_CLEANUP_WARNING, notice(VoiceMemoOutcome.Failed(VoiceFailure.STORAGE, true)))
        assertEquals(VoiceNotice.UNAVAILABLE, VoiceServiceState.Finished(VoiceServiceResult.NotStarted(VoiceStartBlock.UNKNOWN)).notice())
    }
}
