package app.memoryvault.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.memoryvault.domain.DriveState
import org.junit.Assert.*
import org.junit.Test

class MemoEditorLifecycleObserverTest {
    // Missing ON_STOP closure exposes the editor when lifecycle collection misses D -> P.
    @Test fun stoppedEditorCannotReopenAfterAnUnobservedDrivingRoundTrip_withoutExplicitResume() {
        val owner = Owner()
        var editor = MemoEditorState().openAdd("v2", DriveState.PARKED)
            .changeText("  미전송 초안\n", DriveState.PARKED)
        var inputClosures = 0
        owner.registry.addObserver(MemoEditorLifecycleObserver({ editor }, { editor = it }, { inputClosures++ }))
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(editor.isOpen)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        // The source returns to P while UI collection is stopped; no D callback reaches this editor.
        editor = editor.onDriveChanged(DriveState.PARKED)
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertFalse(editor.isOpen)
        assertEquals("  미전송 초안\n", editor.text)
        assertEquals("v2", editor.visitId)
        assertEquals(1, inputClosures)
        assertTrue(editor.resume(DriveState.PARKED).isOpen)
    }

    @Test fun pauseDoesNotDiscardEdit_andStopPreservesTheEditTargetAndBuffer() {
        val owner = Owner()
        var editor = MemoEditorState(visitId = "v1", noteId = 17, text = "변경 중인 문장", isOpen = true)
        var inputClosures = 0
        owner.registry.addObserver(MemoEditorLifecycleObserver({ editor }, { editor = it }, { inputClosures++ }))
        owner.registry.currentState = Lifecycle.State.RESUMED
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        assertTrue(editor.isOpen)
        assertEquals(0, inputClosures)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertFalse(editor.isOpen)
        assertEquals(17L, editor.noteId)
        assertEquals("변경 중인 문장", editor.text)
        assertEquals(1, inputClosures)
    }

    @Test fun stoppingDiscardConfirmationPreservesOnlyTheOriginalDraft() {
        val owner = Owner()
        var editor = MemoEditorState(visitId = "v1", text = "원래 초안", openedGeneration = 3,
            pendingTarget = MemoEditorTarget("v2"))
        owner.registry.addObserver(MemoEditorLifecycleObserver({ editor }, { editor = it }, {}))
        owner.registry.currentState = Lifecycle.State.RESUMED
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertNull(editor.pendingTarget)
        assertEquals("v1", editor.visitId)
        assertEquals("원래 초안", editor.text)
        assertFalse(editor.isOpen)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
