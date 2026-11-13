package app.memoryvault.ui

import app.memoryvault.data.Note
import app.memoryvault.data.NoteKind
import app.memoryvault.domain.DriveState
import org.junit.Assert.*
import org.junit.Test

class MemoEditorSafetyTest {
    // Replacing a different target without confirmation destroys the only unsent buffer.
    @Test fun switchingAddTargetRequestsConfirmation_andCancellationKeepsTheOriginalDraft() {
        val draft = originalDraft()
        val requested = draft.openAdd("v2", DriveState.PARKED, 3)
        assertEquals("v1", requested.visitId)
        assertEquals("  보관할 초안\n", requested.text)
        assertFalse(requested.isOpen)
        assertEquals("v2", requested.pendingTarget?.visitId)
        val cancelled = requested.cancelDiscard()
        assertEquals("v1", cancelled.visitId)
        assertEquals("  보관할 초안\n", cancelled.text)
        assertNull(cancelled.pendingTarget)
        assertFalse(cancelled.isOpen)
        assertTrue(cancelled.resume(DriveState.PARKED, 3).isOpen)
    }

    @Test fun switchingToAnEditKeepsOriginalBuffer_untilExplicitCurrentParkedConfirmation() {
        val note = Note(17, "v2", "다른 저장 메모", NoteKind.TYPED, 0, sourceDate = "2026-11-08")
        val requested = originalDraft().openEdit(note, DriveState.PARKED, 3)
        assertEquals("  보관할 초안\n", requested.text)
        assertEquals(17L, requested.pendingTarget?.noteId)
        val confirmed = requested.confirmDiscard(DriveState.PARKED, 3)
        assertEquals("v2", confirmed.visitId)
        assertEquals(17L, confirmed.noteId)
        assertEquals("다른 저장 메모", confirmed.text)
        assertTrue(confirmed.isOpen)
        assertNull(confirmed.pendingTarget)
    }

    @Test fun reopeningTheSameTargetResumesItsBuffer_withoutDiscardConfirmation() {
        val reopened = originalDraft().openAdd("v1", DriveState.PARKED, 3)
        assertEquals("  보관할 초안\n", reopened.text)
        assertTrue(reopened.isOpen)
        assertNull(reopened.pendingTarget)
    }

    @Test fun staleDiscardConfirmationCannotReplaceTheBuffer_afterLeavingParkOrGenerationChange() {
        val requested = originalDraft().openAdd("v2", DriveState.PARKED, 3)
        val staleStates = listOf(DriveState.DRIVING to 3L, DriveState.UNKNOWN to 4L,
            DriveState.TRANSITION to 3L, DriveState.PARKED to 4L)
        for ((drive, generation) in staleStates) {
            val rejected = requested.confirmDiscard(drive, generation)
            assertEquals("v1", rejected.visitId)
            assertEquals("  보관할 초안\n", rejected.text)
            assertFalse(rejected.isOpen)
            assertNull(rejected.pendingTarget)
        }
    }

    // The UI can see only final P; the retained generation must still close and lock stale callbacks.
    @Test fun generationChangeWhileStillParkedClosesEditor_preservesBufferAndRequiresManualResume() {
        val opened = originalDraft().resume(DriveState.PARKED, 3)
        assertTrue(opened.canSubmit(DriveState.PARKED, 3))
        assertFalse(opened.canSubmit(DriveState.PARKED, 4))
        val observed = opened.onDriveChanged(DriveState.PARKED, 4)
        assertFalse(observed.isOpen)
        assertEquals("  보관할 초안\n", observed.text)
        assertEquals(observed, opened.changeText("stale IME", DriveState.PARKED, 4))
        assertFalse(observed.onDriveChanged(DriveState.PARKED, 4).isOpen)
        val resumed = observed.resume(DriveState.PARKED, 4)
        assertTrue(resumed.isOpen)
        assertTrue(resumed.canSubmit(DriveState.PARKED, 4))
        assertEquals("  보관할 초안\n", resumed.text)
    }

    @Test fun generationChangeClosesDiscardConfirmation_withoutDroppingOriginalDraft() {
        val requested = originalDraft().openAdd("v2", DriveState.PARKED, 3)
        val closed = requested.onDriveChanged(DriveState.PARKED, 4)
        assertNull(closed.pendingTarget)
        assertFalse(closed.isOpen)
        assertEquals("v1", closed.visitId)
        assertEquals("  보관할 초안\n", closed.text)
    }

    private fun originalDraft() = MemoEditorState(visitId = "v1", text = "  보관할 초안\n", openedGeneration = 3)
}
