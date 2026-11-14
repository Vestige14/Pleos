package app.memoryvault.share

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareSelectionDatabaseTest {
    // Missing serialization/date isolation or volatile-only storage loses exclusions after process recreation.
    @Test fun closeAndReopenPreservesExactSelectionsForEachDate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "share-selection-test-${UUID.randomUUID()}.db"
        val first = ShareSelection("2026-11-08", setOf("restaurant", "beach"), setOf("p1", "p2"),
            mapOf("beach" to "  바다 😀\n설명  ", "restaurant" to " \n "))
        val next = ShareSelection("2026-11-09", emptySet(), emptySet(), emptyMap())
        var database: ShareSelectionDatabase? = null
        try {
            database = Room.databaseBuilder(context, ShareSelectionDatabase::class.java, name).build()
            RoomShareSelectionStore(database).apply { save(first); save(next) }
            database.close()
            database = Room.databaseBuilder(context, ShareSelectionDatabase::class.java, name).build()
            val store = RoomShareSelectionStore(database)
            assertEquals(first, store.load("2026-11-08"))
            assertEquals(next, store.load("2026-11-09"))
            assertNull(store.load("2026-11-10"))
        } finally { database?.close(); context.deleteDatabase(name) }
    }
}
