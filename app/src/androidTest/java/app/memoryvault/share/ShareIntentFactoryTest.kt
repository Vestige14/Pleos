package app.memoryvault.share

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentFactoryTest {
    // Without the outer chooser policy, a single resolving target can bypass explicit selection.
    @Test fun chooserRequestsExplicitTargetSelection() {
        val chooser = ShareIntentFactory.chooser(Uri.parse("content://media/external/images/media/1234"))
        assertFalse("The outer chooser must request explicit target selection",
            chooser.getBooleanExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, true))
    }

    // Without ClipData/read flags, the receiving app cannot read the saved image URI.
    @Test fun sendAndChooserCarryOnlyPngStreamWithTemporaryReadPermission() {
        val uri = Uri.parse("content://media/external/images/media/1234")
        val send = ShareIntentFactory.send(uri)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertEquals(uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(uri, requireNotNull(send.clipData).getItemAt(0).uri)
        assertEquals(1, send.clipData!!.itemCount)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertFalse(send.hasExtra(Intent.EXTRA_TEXT))
        val chooser = ShareIntentFactory.chooser(uri)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, chooser.clipData!!.getItemAt(0).uri)
    }
}
