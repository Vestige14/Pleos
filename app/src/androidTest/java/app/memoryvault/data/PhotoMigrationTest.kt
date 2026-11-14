package app.memoryvault.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
    )

    // Recreating v2 instead of migrating would erase voice source, policy, queued jobs and user photos.
    @Test fun v1ToV2PreservesPhotosMemosLinksAndQueuedRequests() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mv-photo-migration-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 1).apply {
                execSQL("INSERT INTO visits VALUES ('v1','2026-11-08','합성 장소',37.0,127.0,100,200)")
                execSQL("INSERT INTO photos VALUES ('content://confirmed',150,37.0,127.0,'v1','CONFIRMED')")
                execSQL("INSERT INTO photos VALUES ('content://rejected',160,NULL,NULL,'v1','REJECTED')")
                execSQL("INSERT INTO notes (id,visitId,text,kind,createdAt,captureVisitId,capturedAt,sourceDate,revision,voiceSessionId) VALUES (1,'v1','직접 메모','TYPED',100,NULL,100,'2026-11-08',2,NULL)")
                execSQL("INSERT INTO notes (id,visitId,text,kind,createdAt,captureVisitId,capturedAt,sourceDate,revision,voiceSessionId) VALUES (2,NULL,'  합성 음성 원문  ','VOICE',150,'v1',150,'2026-11-08',1,'migration-session')")
                execSQL("INSERT INTO note_link_policies VALUES (2,3,1)")
                execSQL("INSERT INTO note_visit_links VALUES (2,'v1',2,4,'USER','CONFIRMED',NULL)")
                execSQL("INSERT INTO ai_link_jobs VALUES ('job',2,1,0,'hash','v1','WAITING',0,0,NULL,NULL,NULL)")
                execSQL("INSERT INTO ai_link_request_snapshots VALUES ('job','[]')")
                close()
            }
            helper.runMigrationsAndValidate(name, 2, true, AppDatabase.MIGRATION_1_2).close()
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, app.memoryvault.memory.MemoryAssociationMigration.FROM_2_TO_3).build()
            try {
                assertEquals(listOf("v1"), db.visitDao().getDay("2026-11-08").map { it.id })
                val photos = db.photoDao().getAll()
                assertEquals(listOf(LinkState.CONFIRMED, LinkState.REJECTED), photos.map { it.linkState })
                assertTrue(photos.all { it.accessible && it.visitId == "v1" })
                assertEquals("직접 메모", db.noteDao().get(1)?.text)
                assertEquals(2, db.noteDao().get(1)?.revision)
                assertEquals("  합성 음성 원문  ", db.noteDao().get(2)?.text)
                assertEquals("migration-session", db.noteDao().get(2)?.voiceSessionId)
                assertEquals(NoteLinkPolicy(2, 3, true), db.noteLinkDao().getPolicy(2))
                assertEquals(1, db.noteLinkDao().observeAll().first().size)
                assertEquals("job", db.aiLinkDao().getAllJobs().single().jobId)
                assertEquals(AiLinkRequestSnapshot("job", "[]"), db.aiLinkDao().getAllSnapshots().single())
            } finally { db.close() }
        } finally {
            check(name.startsWith("mv-photo-migration-"))
            context.deleteDatabase(name)
        }
    }
}
