package app.memoryvault.platform.capture

import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureReviewControllerTest {
    private val artifact = CaptureArtifact("s", "/private/s.wav", 700, 99, byteCount = 2, state = CaptureState.PENDING)
    @Test fun parkedExplicitOpenLoadsRawWithoutRequiringTranscriptOrLocation() = runTest {
        val p = Player()
        val c = CaptureReviewController({ DriveSnapshot(DriveState.PARKED) }, { listOf(artifact) }, { File("/private/s.wav") }, p)
        c.onStart(); c.open()
        assertTrue(c.state.value.visible)
        assertEquals(listOf(artifact), c.state.value.items)
        c.play("s")
        assertEquals("s", c.state.value.playingSession)
        assertTrue(p.allowed!!())
        p.ended!!(true)
        assertNull(c.state.value.playingSession)
    }

    @Test fun briefUnknownInvalidationBlocksDelayedPrepareAndRequiresExplicitReopen() = runTest {
        var drive = DriveSnapshot(DriveState.PARKED, invalidationGeneration = 0)
        val p = Player()
        val c = CaptureReviewController({ drive }, { listOf(artifact) }, { File("/private/s.wav") }, p)
        c.onStart(); c.open(); c.play("s")
        drive = DriveSnapshot(DriveState.PARKED, invalidationGeneration = 1) // UNKNOWN was conflated upstream.
        assertFalse(p.allowed!!())
        c.onDriveChanged()
        assertFalse(c.state.value.visible)
        assertTrue(c.state.value.items.isEmpty())
        assertNull(c.state.value.playingSession)
        c.onDriveChanged()
        assertFalse(c.state.value.visible)
        c.open(); assertTrue(c.state.value.visible)
    }

    @Test fun stopDuringDiskLoadNeverPublishesTextOrStartsAudioAfterResume() = runTest {
        val loaded = CompletableDeferred<List<CaptureArtifact>>()
        val p = Player()
        val c = CaptureReviewController({ DriveSnapshot(DriveState.PARKED) }, { loaded.await() }, { File("/private/s.wav") }, p)
        c.onStart()
        val open = async { c.open() }; runCurrent()
        c.onStop(); c.onStart()
        loaded.complete(listOf(artifact.copy(transcript = "private"))); open.await()
        assertFalse(c.state.value.visible)
        assertTrue(c.state.value.items.isEmpty())
        c.play("s")
        assertNull(p.allowed)
    }

    @Test fun drivingUnknownTransitionAndStaleClicksNeverReadPrivateFiles() = runTest {
        for (state in listOf(DriveState.DRIVING, DriveState.UNKNOWN, DriveState.TRANSITION)) {
            var reads = 0
            val c = CaptureReviewController({ DriveSnapshot(state) }, { reads++; listOf(artifact) }, { reads++; File("x") }, Player())
            c.onStart(); c.open(); c.play("s")
            assertEquals(0, reads)
            assertFalse(c.state.value.visible)
        }
    }

    @Test fun latestGenerationHidesPreviouslyLoadedContentBeforeTheObserverRuns() = runTest {
        var drive = DriveSnapshot(DriveState.PARKED)
        val c = CaptureReviewController({ drive }, { listOf(artifact) }, { File("x") }, Player())
        c.onStart(); c.open()
        assertTrue(c.contentAllowed())
        drive = drive.copy(invalidationGeneration = 1)
        assertFalse("Stale UI data cannot wait for a conflated collector", c.contentAllowed())
    }

    @Test fun asynchronousPlaybackErrorIsVisibleAndDoesNotClaimAudioIsPlaying() = runTest {
        val p = Player()
        val c = CaptureReviewController({ DriveSnapshot(DriveState.PARKED) }, { listOf(artifact) }, { File("x") }, p)
        c.onStart(); c.open(); c.play("s")
        p.ended!!(false)
        assertNull(c.state.value.playingSession)
        assertTrue(c.state.value.failed)
    }

    private class Player : CaptureAudioPlayer {
        var allowed: (() -> Boolean)? = null
        var ended: ((Boolean) -> Unit)? = null
        override fun play(file: File, allowed: () -> Boolean, onEnded: (Boolean) -> Unit) { this.allowed = allowed; ended = onEnded }
        override fun close() = Unit
    }
}
