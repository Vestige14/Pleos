package app.memoryvault.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri

object ShareIntentFactory {
    fun send(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("공유 이미지", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun chooser(uri: Uri): Intent = Intent.createChooser(send(uri), null)
        .putExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, false)
}
