package app.memoryvault.share

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** API29+ only. Owns precisely the URIs returned by this adapter's inserts. */
class AndroidShareGalleryIo(private val resolver: ContentResolver) : ShareGalleryIo {
    private val owned = ConcurrentHashMap.newKeySet<String>()

    override fun createPending(displayName: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/기억저장소/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.toString()?.also { owned.add(it) }
    }

    override fun openOutput(uri: String): OutputStream? {
        require(uri in owned) { "Image was not created by this operation" }
        return resolver.openOutputStream(uri.toUri(), "w")
    }

    override fun publish(uri: String): Boolean {
        require(uri in owned) { "Image was not created by this operation" }
        val changed = resolver.update(uri.toUri(), ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            "${MediaStore.Images.Media.IS_PENDING} = ?", arrayOf("1")) == 1
        if (changed) owned.remove(uri)
        return changed
    }

    override fun deletePending(uri: String) {
        if (uri !in owned) return
        // An update can commit then throw; never delete an image whose publication actually succeeded.
        resolver.delete(uri.toUri(), "${MediaStore.Images.Media.IS_PENDING} = ?", arrayOf("1"))
        owned.remove(uri)
    }
}
