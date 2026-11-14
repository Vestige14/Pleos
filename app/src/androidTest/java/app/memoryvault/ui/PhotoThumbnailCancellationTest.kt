package app.memoryvault.ui

import android.graphics.Bitmap
import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.memoryvault.data.LinkState
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.AndroidPhotoThumbnailRequest
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.platform.PhotoAccess
import app.memoryvault.platform.PhotoPermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoThumbnailCancellationTest {
    // Cancelling only the Compose producer leaves ContentResolver's blocking decoder running.
    @Test fun stopAndCollapsedUnknownCancelTheActualAndroidSignalBeforeDecodeFinishes() = runBlocking {
        val drive = MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = PhotoReviewController(drive, { PhotoPermissions(PhotoAccess.FULL, true) }, {
            object : PhotoReviewStore {
                override suspend fun sync(canApply: () -> Boolean) = Unit
                override suspend fun decide(uri: String, state: LinkState, canApply: () -> Boolean) = true
            }
        }, scope)
        try {
            for (stop in listOf(true, false)) {
                controller.onResume()
                val epoch = controller.state.value.thumbnailEpoch
                assertNotNull("Foreground parked state must expose a thumbnail epoch", epoch)
                val started = CompletableDeferred<CancellationSignal>()
                val cancelled = CountDownLatch(1)
                val release = CountDownLatch(1)
                var bitmap: Bitmap? = null
                val request = AndroidPhotoThumbnailRequest { signal ->
                    signal.setOnCancelListener { cancelled.countDown(); release.countDown() }
                    started.complete(signal)
                    check(release.await(5, TimeUnit.SECONDS)) { "Decoder was not cancelled" }
                    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap = it }
                }
                val work = async { controller.loadThumbnail(requireNotNull(epoch), request) }
                try {
                    val signal = withTimeoutOrNull(2_000) { started.await() }
                    assertNotNull("The guarded reader must start", signal)
                    if (stop) controller.onStop() else {
                        drive.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 1)
                        drive.value = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 1)
                    }
                    assertTrue("CancellationSignal must stop the running decoder",
                        withContext(Dispatchers.IO) { cancelled.await(2, TimeUnit.SECONDS) })
                    assertTrue(requireNotNull(signal).isCanceled)
                    assertNull(work.await())
                } finally {
                    release.countDown()
                    work.cancel()
                    bitmap?.recycle()
                }
            }
        } finally { scope.cancel() }
    }
}
