package app.memoryvault.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisitDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
    }

    @After fun tearDown() = db.close()

    // Removing IGNORE/idempotent insertion would duplicate or abort the second launch.
    @Test fun seedTwice_keepsThreeVisits() = runBlocking {
        val repository = MemoryRepository(db)
        repository.seedIfEmpty(seed.copy(visits = day.reversed()))
        repository.seedIfEmpty(seed)
        assertEquals(listOf("v1", "v2", "v3"), db.visitDao().getDay("2026-11-08").map { it.id })
        assertEquals(listOf("v1", "v2", "v3"), db.visitDao().observeDay("2026-11-08").first().map { it.id })
        assertEquals(emptyList<Visit>(), db.visitDao().getDay("2026-11-09"))
    }
}
