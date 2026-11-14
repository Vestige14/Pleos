package app.memoryvault.platform.capture

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateCaptureStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pcm = byteArrayOf(1, 0, -1, 127, 0, -128)
    private fun store() = PrivateCaptureStore(temporary.root)

    @Test fun completeWritesPlayableWavWithExactlyTheReadPayloadAndRealCaptureTime() {
        val s = store()
        val writer = s.begin("session", 700L, 99L, null)
        writer.append(byteArrayOf(1, 0, -1, 127, 0, -128, 88, 88), 6)
        val artifact = writer.complete()
        assertTrue("Completed capture must exist on disk", File(artifact.privateAudioPath).isFile)
        val wav = File(artifact.privateAudioPath).readBytes()
        assertEquals(50, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(42, b.getInt(4))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28))
        assertEquals(2, b.getShort(32).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(6, b.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        assertEquals(6L, artifact.byteCount)
        assertEquals(hex(pcm), artifact.pcmSha256)
        assertEquals(700L, artifact.capturedAt)
        assertEquals(99L, artifact.noteCapturedAt)
        assertNull(artifact.location)
        assertEquals(CaptureState.PENDING, store().findBySession("session")!!.state)
    }

    @Test fun journalRetainsFinalVerbatimBeforeNoteAndLinksOnlyAfterCommit() = runTest {
        val s = store()
        s.begin("s", 700, 99, null).apply { append(pcm, pcm.size); complete() }
        val text = "  합성 원문\n"
        var calls = 0
        assertEquals(42L, s.persistTranscript("s", text) {
            calls++
            val pending = store().findBySession("s")!!
            assertEquals(CaptureState.NOTE_PENDING, pending.state)
            assertEquals(text, pending.transcript)
            assertNull(pending.noteId)
            42L
        })
        val committed = store().findBySession("s")!!
        assertEquals(CaptureState.COMMITTED, committed.state)
        assertEquals(42L, committed.noteId)
        assertEquals(42L, s.persistTranscript("s", text) { calls++; 99 })
        assertEquals(1, calls)
        assertThrows(IllegalStateException::class.java) { s.begin("s", 800, 800, null) }
        assertThrows(IllegalStateException::class.java) { s.prepareTranscript("s", "changed") }
    }

    @Test fun failedNoteSaveRetainsWavAndTextAndRestartUsesSessionLookup() = runTest {
        val s = store()
        s.begin("s", 700, 99, null).apply { append(pcm, pcm.size); complete() }
        try { s.persistTranscript("s", "raw") { throw IllegalStateException("disk") }; fail() }
        catch (_: IllegalStateException) { }
        val waiting = store().findBySession("s")!!
        assertTrue(File(waiting.privateAudioPath).isFile)
        assertEquals("raw", waiting.transcript)
        val repaired = store().recover { id ->
            assertEquals("s", id)
            CaptureNote(42, "raw", 99)
        }.single()
        assertEquals(CaptureState.COMMITTED, repaired.state)
        assertEquals(42L, repaired.noteId)
    }

    @Test fun restartDoesNotInventNoteOrOverwriteConflictingOriginal() = runTest {
        val s = store()
        s.begin("s", 700, 99, null).apply { append(pcm, pcm.size); complete() }
        s.prepareTranscript("s", "raw")
        assertEquals(CaptureState.NOTE_PENDING, store().recover { null }.single().state)
        val conflict = store().recover { CaptureNote(42, "different", 99) }.single()
        assertEquals(CaptureState.CONFLICT, conflict.state)
        assertEquals("raw", conflict.transcript)
        assertTrue(File(conflict.privateAudioPath).isFile)
    }

    @Test fun completedAudioSurvivesSttFailureAndCancellationButIncompleteOwnTempIsRemoved() {
        val s = store()
        s.begin("complete", 700, 99, null).apply { append(pcm, pcm.size); complete() }
        s.failRecognition("complete")
        val complete = s.findBySession("complete")!!
        s.discardIncomplete("complete")
        assertEquals(CaptureState.STT_FAILED, store().findBySession("complete")!!.state)
        assertTrue(File(complete.privateAudioPath).isFile)
        val other = temporary.newFile("unrelated-user-file")
        other.writeText("preserve")
        s.begin("cancelled", 800, 800, null).append(pcm, pcm.size)
        s.discardIncomplete("cancelled")
        assertNull(store().findBySession("cancelled"))
        assertEquals("preserve", other.readText())
        assertTrue(File(complete.privateAudioPath).isFile)
    }

    @Test fun emptyOddAndOversizedPayloadsCannotBecomeSuccessfulAudio() {
        val s = store()
        assertThrows(IllegalArgumentException::class.java) { s.begin("empty", 1, 1, null).complete() }
        val w = s.begin("odd", 1, 1, null)
        assertThrows(IllegalArgumentException::class.java) { w.append(byteArrayOf(1, 2, 3), 3) }
        val large = s.begin("large", 1, 1, null)
        assertThrows(IllegalArgumentException::class.java) { large.append(ByteArray(480_002), 480_002) }
    }

    @Test fun locationSurvivesRestartAndTraversalSessionCannotEscapePrivateRoot() {
        val fix = CaptureLocation(0.0, 0.0, "gps", 650, 700, 4f)
        val artifact = store().begin("../../escape", 700, 700, fix).apply { append(pcm, pcm.size) }.complete()
        assertEquals(temporary.root.canonicalFile, File(artifact.privateAudioPath).canonicalFile.parentFile)
        assertEquals(fix, store().findBySession("../../escape")!!.location)
    }

    @Test fun corruptionIsNotReportedAsCommittedOrPlayableOnRecovery() = runTest {
        val s = store()
        val a = s.begin("s", 700, 99, null).apply { append(pcm, pcm.size) }.complete()
        s.prepareTranscript("s", "raw")
        File(a.privateAudioPath).writeBytes(byteArrayOf(1, 2))
        val result = store().recover { CaptureNote(42, "raw", 99) }.single()
        assertEquals(CaptureState.BROKEN, result.state)
        assertNull(result.noteId)
    }

    @Test fun restartRepairsTheCrashBetweenWavRenameAndJournalCompletion() = runTest {
        val s = store()
        val writer = s.begin("s", 700, 99, null)
        val manifest = temporary.root.listFiles()!!.single { it.name.endsWith(".json") }
        val initialJournal = manifest.readText()
        writer.append(pcm, pcm.size); val artifact = writer.complete()
        manifest.writeText(initialJournal) // Simulate old journal surviving after the WAV rename.
        val repaired = store().recover { null }.single()
        assertEquals(CaptureState.PENDING, repaired.state)
        assertEquals(6L, repaired.byteCount)
        assertEquals(hex(pcm), repaired.pcmSha256)
        assertTrue(File(artifact.privateAudioPath).isFile)
    }

    @Test fun failedCommitJournalAfterRealNoteCommitIsRepairableWithoutAnotherSave() = runTest {
        val s = store()
        s.begin("s", 700, 99, null).apply { append(pcm, pcm.size); complete() }
        var note: CaptureNote? = null
        val journal = temporary.root.listFiles()!!.single { it.name.endsWith(".json") }
        val obstruction = File(journal.path + ".tmp")
        try {
            s.persistTranscript("s", "raw") {
                note = CaptureNote(42, "raw", 99)
                assertTrue(obstruction.mkdir()) // Fail only the post-Note journal write.
                42
            }
            fail("Journal failure cannot be reported as saved")
        } catch (_: java.io.IOException) { }
        assertEquals(CaptureState.NOTE_PENDING, store().findBySession("s")!!.state)
        assertTrue(obstruction.delete())
        val recovered = store().recover { note }.single()
        assertEquals(CaptureState.COMMITTED, recovered.state)
        assertEquals(42L, recovered.noteId)
    }

    @Test fun restartRepairsFinalizedPartBeforeRenameWithoutTreatingUnfinishedPcmAsComplete() = runTest {
        val s = store()
        val writer = s.begin("s", 700, 99, null)
        val journal = temporary.root.listFiles()!!.single { it.name.endsWith(".json") }
        val initial = journal.readText()
        writer.append(pcm, pcm.size); val completed = writer.complete()
        val audio = File(completed.privateAudioPath)
        val part = File(audio.path.removeSuffix(".wav") + ".part")
        assertTrue(audio.renameTo(part)); journal.writeText(initial)
        val repaired = store().recover { null }.single()
        assertEquals(CaptureState.PENDING, repaired.state)
        assertArrayEquals(pcm, store().readPcm("s"))
        assertTrue(audio.isFile)
        assertFalse(part.exists())
    }

    private fun hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
