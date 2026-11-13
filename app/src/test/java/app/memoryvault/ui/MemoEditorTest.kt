package app.memoryvault.ui

import app.memoryvault.data.Note
import app.memoryvault.data.NoteKind
import app.memoryvault.data.NoteVisitLink
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.LinkOrigin
import app.memoryvault.domain.NoteLinkStatus
import org.junit.Assert.*
import org.junit.Test

class MemoEditorTest {
    // Losing the buffer on a gate change would destroy an unsent memo; keeping open exposes it.
    @Test fun driveTransition_closesEditorAndPreservesUnsentDraftUntilExplicitParkedResume() {
        val typed = MemoEditorState().openAdd("v2", DriveState.PARKED).changeText("  아직 저장 안 함\n", DriveState.PARKED)
        assertTrue(typed.isOpen)
        for (drive in listOf(DriveState.DRIVING, DriveState.UNKNOWN, DriveState.TRANSITION)) {
            val hidden = typed.onDriveChanged(drive)
            assertFalse(hidden.isOpen)
            assertEquals("  아직 저장 안 함\n", hidden.text)
            assertFalse(hidden.resume(drive).isOpen)
            assertEquals("  아직 저장 안 함\n", hidden.changeText("stale IME", drive).text)
            assertTrue(hidden.resume(DriveState.PARKED).isOpen)
        }
    }

    @Test fun voiceMemo_cannotOpenTypedEditorAndPendingPresentationNeverClaimsAVisit() {
        val voice = note(NoteKind.VOICE).copy(visitId = null, captureVisitId = "v2")
        assertFalse(MemoEditorState().openEdit(voice, DriveState.PARKED).isOpen)
        assertEquals(NotePresentation.VOICE_PENDING, presentationOf(voice, emptyList()))
        assertFalse(NotePresentation.VOICE_PENDING.editable)
        val rejected = link(NoteLinkStatus.REJECTED)
        assertEquals(NotePresentation.VOICE_PENDING, presentationOf(voice, listOf(rejected)))
    }

    @Test fun typedEditingIsParkedOnly_andVoiceSuggestionsRemainDistinctFromConfirmation() {
        val typed = note(NoteKind.TYPED)
        assertTrue(MemoEditorState().openEdit(typed, DriveState.PARKED).isOpen)
        for (drive in listOf(DriveState.UNKNOWN, DriveState.TRANSITION, DriveState.DRIVING)) {
            assertFalse(MemoEditorState().openAdd("v1", drive).isOpen)
            assertFalse(MemoEditorState().openEdit(typed, drive).isOpen)
        }
        assertTrue(presentationOf(typed, emptyList()).editable)
        val voice = note(NoteKind.VOICE)
        assertEquals(NotePresentation.VOICE_SUGGESTED, presentationOf(voice, listOf(link(NoteLinkStatus.SUGGESTED))))
        assertEquals(NotePresentation.VOICE_CONFIRMED, presentationOf(voice, listOf(link(NoteLinkStatus.CONFIRMED))))
        assertFalse(NotePresentation.VOICE_SUGGESTED.editable)
        assertFalse(NotePresentation.VOICE_CONFIRMED.editable)
    }

    private fun note(kind: NoteKind) = Note(
        id = 4, visitId = "v2", text = " 원문 ", kind = kind, createdAt = 0, sourceDate = "2026-11-08",
    )
    private fun link(status: NoteLinkStatus) = NoteVisitLink(4, "v2", 0, 2, LinkOrigin.AI, status)
}
