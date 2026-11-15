package app.memoryvault.platform.capture

import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class P2CaptureReviewLoadingTest {
    private val artifact = CaptureArtifact("synthetic", "fixture-only-no-audio", 1794105000000,
        1794105000000, state = CaptureState.COMMITTED, transcript = "합성 원문")

    @Test fun pendingOpenShowsLoadingAndDoesNotReadTwice() = runTest {
        val pending = CompletableDeferred<List<CaptureArtifact>>()
        var reads = 0
        val controller = controller({ DriveSnapshot(DriveState.PARKED) }) {
            reads++
            pending.await()
        }
        controller.onStart()
        val first = async { controller.open() }
        runCurrent()
        val second = async { controller.open() }
        runCurrent()
        assertEquals("one explicit open owns the pending load", 1, reads)
        assertTrue(controller.state.value.loading)
        assertFalse(controller.state.value.visible)
        assertTrue(controller.state.value.items.isEmpty())
        pending.complete(listOf(artifact))
        first.await(); second.await()
        assertFalse(controller.state.value.loading)
        assertTrue(controller.state.value.visible)
        assertEquals(listOf(artifact), controller.state.value.items)
    }

    @Test fun invalidatedOldCompletionCannotClearANewPendingOpen() = runTest {
        var drive = DriveSnapshot(DriveState.PARKED)
        val firstResult = CompletableDeferred<List<CaptureArtifact>>()
        val secondResult = CompletableDeferred<List<CaptureArtifact>>()
        var reads = 0
        val controller = controller({ drive }) {
            reads++
            if (reads == 1) firstResult.await() else secondResult.await()
        }
        controller.onStart()
        val first = async { controller.open() }
        runCurrent()
        assertTrue(controller.state.value.loading)

        drive = DriveSnapshot(DriveState.UNKNOWN, invalidationGeneration = 1)
        controller.onDriveChanged()
        assertFalse(controller.state.value.loading)
        assertFalse(controller.state.value.visible)
        drive = DriveSnapshot(DriveState.PARKED, invalidationGeneration = 2)
        val second = async { controller.open() }
        runCurrent()
        assertEquals(2, reads)
        assertTrue(controller.state.value.loading)

        firstResult.complete(listOf(artifact.copy(transcript = "오래된 합성 결과")))
        first.await()
        assertTrue("the old epoch cannot clear the new loader", controller.state.value.loading)
        assertTrue(controller.state.value.items.isEmpty())
        secondResult.complete(listOf(artifact))
        second.await()
        assertFalse(controller.state.value.loading)
        assertEquals(listOf(artifact), controller.state.value.items)
    }

    @Test fun stopAndCancellationClearOnlyTheirCurrentLoadingState() = runTest {
        val pending = CompletableDeferred<List<CaptureArtifact>>()
        val controller = controller({ DriveSnapshot(DriveState.PARKED) }) { pending.await() }
        controller.onStart()
        val opening = async { controller.open() }
        runCurrent()
        assertTrue(controller.state.value.loading)
        opening.cancelAndJoin()
        assertFalse(controller.state.value.loading)
        assertFalse(controller.state.value.visible)

        val another = async { controller.open() }
        runCurrent()
        assertTrue(controller.state.value.loading)
        controller.onStop()
        assertFalse(controller.state.value.loading)
        pending.complete(listOf(artifact))
        another.await()
        assertFalse(controller.state.value.visible)
        assertTrue(controller.state.value.items.isEmpty())
    }

    @Test fun failedLoadEndsLoadingAndShowsFailureWithoutFalseEmpty() = runTest {
        val controller = controller({ DriveSnapshot(DriveState.PARKED) }) { throw IOException("synthetic") }
        controller.onStart()
        controller.open()
        assertFalse(controller.state.value.loading)
        assertTrue(controller.state.value.visible)
        assertTrue(controller.state.value.failed)
        assertTrue(controller.state.value.items.isEmpty())
    }

    private fun controller(drive: () -> DriveSnapshot, load: suspend () -> List<CaptureArtifact>) =
        CaptureReviewController(drive, load, { File("fixture-only-no-audio") }, object : CaptureAudioPlayer {
            override fun play(file: File, allowed: () -> Boolean, onEnded: (Boolean) -> Unit) = Unit
            override fun close() = Unit
        })
}
