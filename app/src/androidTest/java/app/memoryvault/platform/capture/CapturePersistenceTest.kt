package app.memoryvault.platform.capture

import android.os.Bundle
import android.os.SystemClock
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.data.AppDatabase
import app.memoryvault.data.MemoryRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** No microphone, SDK request, network, user DB or system-location mutation. */
class CapturePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realPrivateWavAndRoomReopenRepairCommitWindowWithOneImmutableNote() = runBlocking {
        val id = "capture-test-${UUID.randomUUID()}"
        val root = File(context.noBackupFilesDir, id).apply { mkdirs() }
        var db = Room.databaseBuilder(context, AppDatabase::class.java, "$id.db").build()
        try {
            val s = PrivateCaptureStore(root)
            val pcm = byteArrayOf(0, 0, -1, 127, 0, -128)
            val artifact = s.begin(id, 900L, 700L, null).apply { append(pcm, pcm.size) }.complete()
            val raw = "  합성 캡처 원문\n"
            var noteId = 0L
            try {
                s.persistTranscript(id, raw) {
                    noteId = MemoryRepository(db).saveVoiceMemo(id, raw, 700L)
                    throw IllegalStateException("simulated process boundary after Room commit")
                }
                fail("The missing commit acknowledgement cannot be successful")
            } catch (_: IllegalStateException) { }
            assertEquals(CaptureState.NOTE_PENDING, s.findBySession(id)!!.state)
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, "$id.db").build()
            val reopened = PrivateCaptureStore(root)
            val result = reopened.recover { session -> db.noteDao().getByVoiceSession(session)?.let { CaptureNote(it.id, it.text, it.capturedAt) } }.single()
            assertEquals(CaptureState.COMMITTED, result.state)
            assertEquals(noteId, result.noteId)
            assertNull(result.location)
            assertEquals(900L, result.capturedAt)
            assertEquals(700L, result.noteCapturedAt)
            assertArrayEquals(pcm, reopened.readPcm(id))
            val file = File(artifact.privateAudioPath)
            assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
            assertEquals("RIFF", file.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) })
            assertEquals(noteId, MemoryRepository(db).saveVoiceMemo(id, "ignored duplicate", 800L))
            assertEquals(1, db.noteDao().getAll().size)
            assertEquals(raw, db.noteDao().getAll().single().text)
            assertEquals(1, db.aiLinkDao().getAllJobs().size)
            assertEquals(1, db.aiLinkDao().getAllSnapshots().size)
        } finally {
            db.close(); context.deleteDatabase("$id.db")
            root.deleteRecursively() // This test's UUID directory only; no product directory access.
        }
    }

    @Test fun syntheticFreshLocationAndExplicitlyMissingLocationBothSurviveActualFilesystemReopen() = runBlocking {
        val root = File(context.noBackupFilesDir, "capture-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val fix = FreshCaptureLocation.select(listOf(LocationFix(0.0, 0.0, "synthetic", 95_000, 55_000_000_000, 4f)), CaptureMoment(100_000, 60_000_000_000))
            val s = PrivateCaptureStore(root)
            for ((id, loc) in listOf("with-location" to fix, "without-location" to null)) {
                s.begin(id, 100_000, 100_000, loc).apply { append(byteArrayOf(1, 2), 2); complete() }
            }
            val reopened = PrivateCaptureStore(root)
            assertEquals(fix, reopened.findBySession("with-location")!!.location)
            assertNull(reopened.findBySession("without-location")!!.location)
            assertEquals(2, reopened.recover { null }.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun observeStandardLocationSupportWithoutRequestingPermissionUpdatesOrMicrophone() {
        val source = AndroidCaptureLocationSource(context)
        val support = source.support()
        val now = CaptureMoment(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
        val fix = source(now)
        if (fix != null) {
            assertTrue(fix.fixTime <= now.wallTime && now.wallTime - fix.fixTime <= 30_000)
            assertTrue(fix.source in support.enabledProviders)
        }
        // Observation only: no coordinates, personal data or speech enter status output.
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("capture_location_permission", support.permissionGranted.toString())
            putString("capture_location_providers", support.enabledProviders.joinToString(","))
            putString("capture_location_fresh_fix", (fix != null).toString())
        })
    }
}
