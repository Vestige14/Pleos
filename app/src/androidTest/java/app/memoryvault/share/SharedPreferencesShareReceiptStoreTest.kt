package app.memoryvault.share

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesShareReceiptStoreTest {
    // Activity-local or asynchronous-only state cannot restore a published receipt in another instance.
    @Test fun reconstructedStoreRestoresOnlyThePublishedImageMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "share-receipt-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val receipt = ShareExportReceipt("content://media/external/images/media/41", "saved.png", "2026-11-08")
        try {
            assertNull(SharedPreferencesShareReceiptStore(preferences).latest.value)
            SharedPreferencesShareReceiptStore(preferences).save(receipt)
            assertEquals(receipt, SharedPreferencesShareReceiptStore(preferences).latest.value)
            val stored = preferences.all.values.single() as String
            assertEquals(setOf("uri", "displayName", "date"), Json.parseToJsonElement(stored).jsonObject.keys)
        } finally { context.deleteSharedPreferences(name) }
    }
}
