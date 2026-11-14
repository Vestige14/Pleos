package app.memoryvault.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

data class PhotoThumbnailEpoch(val requestRevision: Long, val driveGeneration: Long)

internal interface PhotoThumbnailRequest<T> {
    fun read(): T
    fun cancel()
}

internal class PhotoThumbnailLoader(
    private val currentEpoch: () -> PhotoThumbnailEpoch?,
    private val invalidations: Flow<Unit>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun <T> load(epoch: PhotoThumbnailEpoch, request: PhotoThumbnailRequest<T>): T? = coroutineScope {
        if (currentEpoch() != epoch) {
            request.cancel()
            return@coroutineScope null
        }
        val decoding = async(dispatcher, start = CoroutineStart.LAZY) {
            // The producer may have waited on the IO dispatcher while its Activity stopped.
            if (currentEpoch() != epoch) return@async null
            val image = request.read()
            if (isActive && currentEpoch() == epoch) image else null
        }
        // This collector is intentionally independent of the UI's lifecycle-aware state collection.
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            invalidations.collect {
                if (currentEpoch() != epoch) {
                    request.cancel()
                    decoding.cancel()
                }
            }
        }
        try {
            decoding.start()
            val image = decoding.await()
            if (currentEpoch() == epoch) image else null
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            null
        } finally {
            request.cancel()
            observer.cancel()
            decoding.cancel()
        }
    }
}
