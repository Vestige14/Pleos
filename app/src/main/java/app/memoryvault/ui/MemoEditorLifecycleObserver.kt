package app.memoryvault.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** Closes only the UI editor; voice-session ownership stays with the service. */
internal class MemoEditorLifecycleObserver(
    private val currentEditor: () -> MemoEditorState,
    private val updateEditor: (MemoEditorState) -> Unit,
    private val closeInput: () -> Unit,
) : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_STOP) {
            updateEditor(currentEditor().close())
            closeInput()
        }
    }
}
