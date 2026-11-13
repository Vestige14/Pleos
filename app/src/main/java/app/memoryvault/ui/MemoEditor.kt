package app.memoryvault.ui

import app.memoryvault.data.Note
import app.memoryvault.data.NoteKind
import app.memoryvault.data.NoteVisitLink
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.NoteLinkStatus

data class MemoEditorTarget(val visitId: String, val noteId: Long? = null, val initialText: String = "")

data class MemoEditorState(
    val visitId: String? = null,
    val noteId: Long? = null,
    val text: String = "",
    val isOpen: Boolean = false,
    val openedGeneration: Long = 0L,
    val pendingTarget: MemoEditorTarget? = null,
) {
    fun openAdd(visitId: String, drive: DriveState, generation: Long = 0L): MemoEditorState =
        openTarget(MemoEditorTarget(visitId), drive, generation)

    fun openEdit(note: Note, drive: DriveState, generation: Long = 0L): MemoEditorState {
        if (note.kind != NoteKind.TYPED || note.visitId == null) return onDriveChanged(drive, generation)
        return openTarget(MemoEditorTarget(note.visitId, note.id, note.text), drive, generation)
    }

    fun changeText(text: String, drive: DriveState, generation: Long = openedGeneration): MemoEditorState {
        val current = onDriveChanged(drive, generation)
        return if (current.isOpen) current.copy(text = text) else current
    }

    fun onDriveChanged(drive: DriveState, generation: Long = openedGeneration): MemoEditorState =
        if (drive == DriveState.PARKED && generation == openedGeneration) this else close()

    fun resume(drive: DriveState, generation: Long = openedGeneration): MemoEditorState =
        if (drive == DriveState.PARKED && visitId != null) copy(isOpen = true, openedGeneration = generation, pendingTarget = null)
        else onDriveChanged(drive, generation)

    fun confirmDiscard(drive: DriveState, generation: Long): MemoEditorState {
        val current = onDriveChanged(drive, generation)
        val target = current.pendingTarget ?: return current
        return MemoEditorState(target.visitId, target.noteId, target.initialText, true, generation)
    }

    fun cancelDiscard(): MemoEditorState = close()
    fun close(): MemoEditorState = copy(isOpen = false, pendingTarget = null)

    fun canSubmit(drive: DriveState, generation: Long): Boolean =
        drive == DriveState.PARKED && generation == openedGeneration && isOpen && pendingTarget == null &&
            visitId != null && text.isNotBlank()

    private fun openTarget(target: MemoEditorTarget, drive: DriveState, generation: Long): MemoEditorState = when {
        drive != DriveState.PARKED -> close()
        visitId == target.visitId && noteId == target.noteId -> resume(drive, generation)
        visitId != null && text.isNotBlank() -> copy(isOpen = false, openedGeneration = generation, pendingTarget = target)
        else -> MemoEditorState(target.visitId, target.noteId, target.initialText, true, generation)
    }
}

enum class NotePresentation(val editable: Boolean) {
    TYPED(true), VOICE_PENDING(false), VOICE_SUGGESTED(false), VOICE_CONFIRMED(false),
}

fun presentationOf(note: Note, links: List<NoteVisitLink>): NotePresentation = when {
    note.kind == NoteKind.TYPED -> NotePresentation.TYPED
    links.any { it.noteId == note.id && it.status == NoteLinkStatus.CONFIRMED } -> NotePresentation.VOICE_CONFIRMED
    links.any { it.noteId == note.id && it.status == NoteLinkStatus.SUGGESTED } -> NotePresentation.VOICE_SUGGESTED
    else -> NotePresentation.VOICE_PENDING
}
