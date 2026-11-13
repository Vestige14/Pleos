package app.memoryvault.ui

import app.memoryvault.data.Note
import app.memoryvault.data.NoteKind
import app.memoryvault.data.NoteVisitLink
import app.memoryvault.domain.DriveState
import app.memoryvault.domain.NoteLinkStatus

data class MemoEditorState(
    val visitId: String? = null,
    val noteId: Long? = null,
    val text: String = "",
    val isOpen: Boolean = false,
) {
    fun openAdd(visitId: String, drive: DriveState): MemoEditorState = when {
        drive != DriveState.PARKED -> this
        this.visitId == visitId && noteId == null -> copy(isOpen = true)
        else -> MemoEditorState(visitId = visitId, isOpen = true)
    }

    fun openEdit(note: Note, drive: DriveState): MemoEditorState = when {
        drive != DriveState.PARKED || note.kind != NoteKind.TYPED || note.visitId == null -> this
        noteId == note.id -> copy(isOpen = true)
        else -> MemoEditorState(visitId = note.visitId, noteId = note.id, text = note.text, isOpen = true)
    }

    fun changeText(text: String, drive: DriveState): MemoEditorState =
        if (drive == DriveState.PARKED && isOpen) copy(text = text) else this

    fun onDriveChanged(drive: DriveState): MemoEditorState =
        if (drive == DriveState.PARKED) this else copy(isOpen = false)

    fun resume(drive: DriveState): MemoEditorState =
        if (drive == DriveState.PARKED && visitId != null) copy(isOpen = true) else this
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
