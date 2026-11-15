package app.memoryvault.ui.p2

import app.memoryvault.domain.VoiceCancelReason
import app.memoryvault.domain.VoiceCaptureTiming
import app.memoryvault.domain.VoiceFailure
import app.memoryvault.domain.VoiceMemoOutcome
import app.memoryvault.service.VoiceServiceResult
import app.memoryvault.service.VoiceServiceState
import app.memoryvault.service.VoiceStartBlock
import app.memoryvault.ui.components.P2VoiceNoticeWindow
import org.junit.Assert.*
import org.junit.Test

/** UI-only deadline: no command can reset the service, source timing, or saved records. */
class P2VoiceNoticeWindowTest {
    @Test fun successExpiresAtTwoSecondsWithoutExtendingOnRecomposition() {
        val window = P2VoiceNoticeWindow()
        val timing = VoiceCaptureTiming(100, 500)
        val saved = saved(1)
        assertEquals(2_000L, window.remainingMillis(saved, timing, 1_000))
        assertEquals(1_500L, window.remainingMillis(saved, timing, 1_500))
        assertEquals(1L, window.remainingMillis(saved, timing, 2_999))
        assertEquals(0L, window.remainingMillis(saved, timing, 3_000))
    }

    @Test fun expiredSuccessStaysExpiredWhenTheUiReenters() {
        val window = P2VoiceNoticeWindow()
        val timing = VoiceCaptureTiming(100, 500)
        assertEquals(2_000L, window.remainingMillis(saved(1), timing, 1_000))
        assertEquals(0L, window.remainingMillis(saved(1), timing, 4_000))
        assertEquals(0L, window.remainingMillis(saved(1), timing, 8_000))
    }

    @Test fun sameNoteWithDifferentCaptureTimingGetsANewWindow() {
        val window = P2VoiceNoticeWindow()
        assertEquals(2_000L, window.remainingMillis(saved(1), VoiceCaptureTiming(100, 500), 1_000))
        assertEquals(2_000L, window.remainingMillis(saved(1), VoiceCaptureTiming(4_000, 4_500), 5_000))
    }

    @Test fun lateOldSessionCannotExpireTheNewSuccess() {
        val window = P2VoiceNoticeWindow()
        val old = VoiceCaptureTiming(100, 500)
        val newer = VoiceCaptureTiming(1_500, 1_800)
        assertEquals(2_000L, window.remainingMillis(saved(1), old, 0))
        assertEquals(2_000L, window.remainingMillis(saved(2), newer, 1_900))
        assertEquals(0L, window.remainingMillis(saved(1), old, 2_500))
        assertEquals(1_400L, window.remainingMillis(saved(2), newer, 2_500))
    }

    @Test fun failuresPermissionsCancellationAndCleanupWarningsNeverGetAnAutoResetWindow() {
        val window = P2VoiceNoticeWindow()
        val states = listOf(VoiceServiceState.Idle, VoiceServiceState.CheckingVehicle,
            VoiceServiceState.Preparing, VoiceServiceState.Listening, VoiceServiceState.Processing,
            VoiceServiceState.Finished(VoiceServiceResult.NotStarted(VoiceStartBlock.MICROPHONE)),
            VoiceServiceState.Finished(VoiceServiceResult.Completed(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER))),
            VoiceServiceState.Finished(VoiceServiceResult.Completed(VoiceMemoOutcome.Failed(VoiceFailure.STORAGE))),
            VoiceServiceState.Finished(VoiceServiceResult.Completed(VoiceMemoOutcome.Saved(3, cleanupFailed = true))))
        states.forEach { assertNull(window.remainingMillis(it, VoiceCaptureTiming(100, 500), 10_000)) }
    }

    @Test fun successfulMemoWithoutStartedMarksStillHasTheSameNoticeWindow() {
        val window = P2VoiceNoticeWindow()
        assertEquals(2_000L, window.remainingMillis(saved(4), VoiceCaptureTiming(), 1_000))
        assertEquals(0L, window.remainingMillis(saved(4), VoiceCaptureTiming(), 3_000))
    }

    private fun saved(id: Long) = VoiceServiceState.Finished(VoiceServiceResult.Completed(VoiceMemoOutcome.Saved(id)))
}
