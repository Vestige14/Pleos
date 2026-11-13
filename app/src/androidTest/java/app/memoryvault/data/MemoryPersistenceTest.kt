package app.memoryvault.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryPersistenceTest {
    // This is deliberately a file DB, with a test-owned name and no destructive migration.
    @Test fun closeAndReopen_retainsPendingVoiceSourceAndQueue() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testName = "mv03a-test-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, testName).build()
        try {
            val originalRepository = MemoryRepository(db)
            originalRepository.seedIfEmpty(seed)
            val id = originalRepository.saveVoiceMemo("restart-session", "  재시작해도 남을 원문\n", t("14:58"))
            assertEquals(1, db.noteDao().getAll().size)
            val original = requireNotNull(db.noteDao().get(id))
            val job = db.aiLinkDao().getAllJobs().single()
            val snapshot = db.aiLinkDao().getAllSnapshots().single()
            db.close()
            assertTrue(context.getDatabasePath(testName).isFile)
            db = Room.databaseBuilder(context, AppDatabase::class.java, testName).build()
            val restoredRepository = MemoryRepository(db)
            restoredRepository.seedIfEmpty(seed)
            val restored = restoredRepository.observeDay("2026-11-08").first()
            assertEquals(listOf("v1", "v2", "v3"), restored.cards.map { it.visit.id })
            assertEquals(original, restored.pendingNotes.single())
            assertEquals("2026-11-08", restored.pendingNotes.single().sourceDate)
            assertEquals(t("14:58"), restored.pendingNotes.single().capturedAt)
            assertEquals(job, db.aiLinkDao().getAllJobs().single())
            assertEquals(snapshot, db.aiLinkDao().getAllSnapshots().single())
            assertEquals(1, db.noteLinkDao().getAllPolicies().size)
            assertEquals(id, restoredRepository.saveVoiceMemo("restart-session", "중복 final", t("16:30")))
            assertEquals(1, db.noteDao().getAll().size)
            assertEquals(1, db.aiLinkDao().getAllJobs().size)
        } finally {
            db.close()
            // Never clear application data or touch a production DB name.
            check(testName.startsWith("mv03a-test-"))
            context.deleteDatabase(testName)
        }
    }
}
