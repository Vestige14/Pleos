package app.memoryvault.ui

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoThumbnailLoaderTest {
    private val epoch = PhotoThumbnailEpoch(3, 0)

    // A queued IO job must not start after the Activity stops or a short UNKNOWN invalidates it.
    @Test fun queuedReadIsCancelledBeforeDecodeWhenItsLiveEpochChanges() = runTest {
        for (replacement in listOf(null, PhotoThumbnailEpoch(3, 1), PhotoThumbnailEpoch(5, 0))) {
            val current = MutableStateFlow<PhotoThumbnailEpoch?>(epoch)
            val request = Request()
            val loader = PhotoThumbnailLoader({ current.value }, current.map { Unit }, StandardTestDispatcher(testScheduler))
            val work = async(start = CoroutineStart.UNDISPATCHED) { loader.load(epoch, request) }
            current.value = replacement
            runCurrent()
            assertNull(work.await())
            assertEquals(0, request.reads)
            assertTrue(request.cancelled)
        }
    }

    @Test fun currentForegroundEpochCanDecodeAndReturnTheImage() = runTest {
        val current = MutableStateFlow<PhotoThumbnailEpoch?>(epoch)
        val request = Request()
        val loader = PhotoThumbnailLoader({ current.value }, current.map { Unit }, StandardTestDispatcher(testScheduler))
        assertEquals("image", loader.load(epoch, request))
        assertEquals(1, request.reads)
    }

    // A decoder may complete despite cancellation; its old image still must not be published.
    @Test fun lateReadResultIsDroppedAfterCollapsedUnknownOrStopAndResume() = runTest {
        for (replacement in listOf(PhotoThumbnailEpoch(3, 1), PhotoThumbnailEpoch(5, 0))) {
            val current = MutableStateFlow<PhotoThumbnailEpoch?>(epoch)
            val request = Request { current.value = replacement }
            val loader = PhotoThumbnailLoader({ current.value }, current.map { Unit }, StandardTestDispatcher(testScheduler))
            assertNull(loader.load(epoch, request))
            assertEquals(1, request.reads)
            assertTrue(request.cancelled)
        }
    }

    private class Request(private val whileReading: () -> Unit = {}) : PhotoThumbnailRequest<String> {
        var reads = 0
        var cancelled = false
        override fun read(): String { reads++; whileReading(); return "image" }
        override fun cancel() { cancelled = true }
    }
}
