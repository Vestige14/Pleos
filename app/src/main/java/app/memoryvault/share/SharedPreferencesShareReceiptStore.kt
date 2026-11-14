package app.memoryvault.share

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredShareReceipt(val uri: String, val displayName: String, val date: String)

/** A single, app-private confirmed-publication receipt; existing sharedpref backup exclusions apply. */
class SharedPreferencesShareReceiptStore(private val preferences: SharedPreferences) : ShareExportReceiptStore {
    private val saving = Mutex()
    private val mutableLatest = MutableStateFlow(readReceipt(preferences))
    override val latest = mutableLatest.asStateFlow()

    override suspend fun save(receipt: ShareExportReceipt) = withContext(Dispatchers.IO) {
        saving.withLock {
            val stored = Json.encodeToString(StoredShareReceipt(receipt.uri, receipt.displayName, receipt.date))
            // Chooser launch must not overtake the durable write; apply() would only acknowledge memory.
            if (!preferences.edit().putString(KEY, stored).commit()) throw IOException("Export receipt could not be saved")
            mutableLatest.value = receipt
        }
    }

    companion object {
        private const val KEY = "latest_confirmed_export"
        @Volatile private var instance: SharedPreferencesShareReceiptStore? = null

        fun open(context: Context): SharedPreferencesShareReceiptStore = instance ?: synchronized(this) {
            instance ?: SharedPreferencesShareReceiptStore(context.applicationContext.getSharedPreferences(
                "share_export_receipt", Context.MODE_PRIVATE)).also { instance = it }
        }

        private fun readReceipt(preferences: SharedPreferences): ShareExportReceipt? = try {
            preferences.getString(KEY, null)?.let { raw ->
                Json.decodeFromString<StoredShareReceipt>(raw).let { ShareExportReceipt(it.uri, it.displayName, it.date) }
            }
        } catch (_: Exception) { null }
    }
}
