package app.memoryvault.share.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.util.Size
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.memoryvault.R
import app.memoryvault.platform.AndroidPhotoThumbnailRequest
import app.memoryvault.share.ShareEpoch
import app.memoryvault.share.ShareSafetyGate
import app.memoryvault.ui.PhotoThumbnailEpoch
import app.memoryvault.ui.PhotoThumbnailLoader
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class ShareThumbnailLoader(private val resolver: ContentResolver, private val gate: ShareSafetyGate) {
    private val loader = PhotoThumbnailLoader(
        currentEpoch = { gate.capture()?.let { PhotoThumbnailEpoch(it.foreground, it.driveGeneration) } },
        invalidations = gate.changes,
    )

    suspend fun load(uri: String, epoch: ShareEpoch): ImageBitmap? {
        val decoded = AtomicReference<Bitmap?>()
        var handedOff = false
        try {
            val bitmap = loader.load(PhotoThumbnailEpoch(epoch.foreground, epoch.driveGeneration),
                AndroidPhotoThumbnailRequest { signal ->
                    if (!gate.allows(epoch)) throw CancellationException("Thumbnail is no longer permitted")
                    resolver.loadThumbnail(uri.toUri(), Size(400, 300), signal).also { decoded.set(it) }
                }) ?: return null
            currentCoroutineContext().ensureActive()
            if (!gate.allows(epoch)) return null
            val image = bitmap.asImageBitmap()
            handedOff = true
            return image
        } finally { if (!handedOff) decoded.get()?.recycle() }
    }
}

private sealed interface ShareThumbnailState {
    data object Loading : ShareThumbnailState
    data class Ready(val image: ImageBitmap) : ShareThumbnailState
    data object Failed : ShareThumbnailState
}

@Composable
internal fun SharePhotoThumbnail(uri: String, thumbnail: suspend (String) -> ImageBitmap?, modifier: Modifier = Modifier) {
    key(uri, thumbnail) {
        val state by produceState<ShareThumbnailState>(ShareThumbnailState.Loading, uri, thumbnail) {
            value = ShareThumbnailState.Loading
            value = try {
                thumbnail(uri)?.let { ShareThumbnailState.Ready(it) } ?: ShareThumbnailState.Failed
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ShareThumbnailState.Failed }
        }
        Box(modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            when (val shown = state) {
                ShareThumbnailState.Loading -> Text(stringResource(R.string.photo_thumbnail_loading))
                ShareThumbnailState.Failed -> Text(stringResource(R.string.photo_thumbnail_unavailable))
                is ShareThumbnailState.Ready -> Image(shown.image,
                    contentDescription = stringResource(R.string.photo_thumbnail_description),
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(180.dp))
            }
        }
    }
}
