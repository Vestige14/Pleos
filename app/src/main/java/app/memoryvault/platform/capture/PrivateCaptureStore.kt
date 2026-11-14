package app.memoryvault.platform.capture

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One application-owned instance; all filesystem writes are serialized, outside the main thread. */
class PrivateCaptureStore(private val root: File) {
    private val json = Json { encodeDefaults = true }
    private val writers = mutableMapOf<String, CaptureWriter>()
    init { check(root.isDirectory || root.mkdirs()) { "Capture directory unavailable" } }

    @Synchronized
    fun begin(sessionId: String, capturedAt: Long, noteCapturedAt: Long, location: CaptureLocation?): CaptureWriter {
        require(sessionId.isNotBlank() && sessionId.length <= 256)
        check(findBySession(sessionId) == null && !audio(sessionId).exists() && !part(sessionId).exists()) { "Capture session exists" }
        val a = CaptureArtifact(sessionId, audio(sessionId).canonicalPath, capturedAt, noteCapturedAt, location = location)
        // The journal precedes audio. Recovery also handles a finished WAV with this initial journal.
        write(a)
        return CaptureWriter(this, a, part(sessionId)).also { writers[sessionId] = it }
    }

    @Synchronized
    fun findBySession(sessionId: String): CaptureArtifact? {
        val path = manifest(sessionId)
        if (!path.exists()) return null
        return read(path).also { check(it.sessionId == sessionId) { "Capture session mismatch" } }
    }

    @Synchronized
    fun all(): List<CaptureArtifact> = checkNotNull(root.listFiles { f -> f.name.endsWith(".json") }) {
        "Capture directory unreadable"
    }.map(::read).sortedByDescending { it.capturedAt }

    @Synchronized
    fun prepareTranscript(sessionId: String, text: String): CaptureArtifact {
        require(text.isNotBlank())
        val a = requireNotNull(findBySession(sessionId))
        check(a.state !in setOf(CaptureState.CAPTURING, CaptureState.INTERRUPTED, CaptureState.BROKEN, CaptureState.CONFLICT)) { "Capture incomplete" }
        check(a.transcript == null || a.transcript == text) { "Capture transcript is immutable" }
        check(verifiedAudio(a) != null) { "Capture audio unavailable" }
        if (a.state == CaptureState.COMMITTED) return a
        return a.copy(state = CaptureState.NOTE_PENDING, transcript = text).also(::write)
    }

    suspend fun persistTranscript(sessionId: String, text: String, saveNote: suspend () -> Long): Long {
        val prepared = prepareTranscript(sessionId, text)
        prepared.noteId?.let { return it }
        val id = saveNote() // Room is independently idempotent by voiceSessionId; never delete on cancellation.
        commit(sessionId, text, id)
        return id
    }

    @Synchronized
    private fun commit(sessionId: String, text: String, noteId: Long) {
        require(noteId > 0)
        val a = requireNotNull(findBySession(sessionId))
        check(a.transcript == text && verifiedAudio(a) != null) { "Capture changed before commit" }
        write(a.copy(state = CaptureState.COMMITTED, noteId = noteId))
    }

    /** Re-query Note by voiceSessionId; a missing Note remains pending, never silently recreated. */
    suspend fun recover(findNote: suspend (String) -> CaptureNote?): List<CaptureArtifact> {
        for (snapshot in all()) {
            // Never recover an active writer in this process.
            if (synchronized(this) { writers.containsKey(snapshot.sessionId) }) continue
            val valid = synchronized(this) {
                if (!audio(snapshot.sessionId).exists() && snapshot.state in setOf(CaptureState.CAPTURING, CaptureState.INTERRUPTED)) {
                    // Only complete() writes a valid header. Unfinished .part data retains a zero header.
                    verifiedAudio(snapshot, part(snapshot.sessionId))?.let { move(part(snapshot.sessionId), audio(snapshot.sessionId)) }
                }
                verifiedAudio(snapshot)
            }
            if (valid == null) {
                synchronized(this) {
                    write(snapshot.copy(state = if (snapshot.state == CaptureState.CAPTURING && !audio(snapshot.sessionId).exists())
                        CaptureState.INTERRUPTED else CaptureState.BROKEN, noteId = null))
                }
                continue
            }
            val note = findNote(snapshot.sessionId)
            val recovered = when {
                note != null && snapshot.transcript != null && note.text == snapshot.transcript && note.capturedAt == snapshot.noteCapturedAt ->
                    valid.copy(state = CaptureState.COMMITTED, noteId = note.id)
                note != null -> valid.copy(state = CaptureState.CONFLICT, noteId = null)
                snapshot.transcript != null -> valid.copy(state = CaptureState.NOTE_PENDING, noteId = null)
                snapshot.state == CaptureState.STT_FAILED -> valid.copy(state = CaptureState.STT_FAILED)
                else -> valid.copy(state = CaptureState.PENDING, noteId = null)
            }
            synchronized(this) { write(recovered) }
        }
        return all()
    }

    @Synchronized
    fun failRecognition(sessionId: String) {
        findBySession(sessionId)?.takeIf { it.state == CaptureState.PENDING }?.let { write(it.copy(state = CaptureState.STT_FAILED)) }
    }

    /** Only the explicitly cancelled unfinished session's temporary objects may be removed. */
    @Synchronized
    fun discardIncomplete(sessionId: String) {
        val a = findBySession(sessionId) ?: return
        if (a.state != CaptureState.CAPTURING || audio(sessionId).exists()) return
        writers.remove(sessionId)?.interrupt()
        check(!part(sessionId).exists() || part(sessionId).delete()) { "Capture temporary file unavailable" }
        check(manifest(sessionId).delete()) { "Capture temporary journal unavailable" }
    }

    @Synchronized
    internal fun complete(writer: CaptureWriter, initial: CaptureArtifact, count: Long, hash: String): CaptureArtifact {
        check(writers[initial.sessionId] === writer) { "Capture no longer owned" }
        move(part(initial.sessionId), audio(initial.sessionId))
        // Remove ownership even if journal write fails: the finished WAV is then recoverable.
        writers.remove(initial.sessionId)
        return initial.copy(byteCount = count, pcmSha256 = hash, state = CaptureState.PENDING).also(::write)
    }

    @Synchronized
    internal fun interrupt(sessionId: String) { writers.remove(sessionId)?.interrupt() }

    /** Reads the exact file payload, verifying header, length and digest before STT or playback. */
    @Synchronized
    fun readPcm(sessionId: String): ByteArray {
        val a = requireNotNull(findBySession(sessionId))
        check(verifiedAudio(a) != null) { "Capture audio unavailable" }
        return audio(sessionId).inputStream().use { stream ->
            check(stream.skip(44) == 44L)
            stream.readBytes()
        }
    }

    private fun verifiedAudio(a: CaptureArtifact, f: File = audio(a.sessionId)): CaptureArtifact? = runCatching {
        check(f.length() in 46L..480_044L)
        val bytes = f.readBytes()
        val count = bytes.size - 44
        check(count % 2 == 0)
        check(bytes.copyOfRange(0, 44).contentEquals(wavHeader(count)))
        val hash = digest(bytes.copyOfRange(44, bytes.size))
        if (a.byteCount != 0L) check(a.byteCount == count.toLong() && a.pcmSha256 == hash)
        a.copy(byteCount = count.toLong(), pcmSha256 = hash)
    }.getOrNull()

    private fun read(path: File): CaptureArtifact = json.decodeFromString<CaptureArtifact>(path.readText()).also {
        check(path == manifest(it.sessionId) && it.privateAudioPath == audio(it.sessionId).canonicalPath) { "Invalid capture path" }
        check(it.sampleRate == 16_000 && it.bitsPerSample == 16 && it.channels == 1) { "Unsupported capture format" }
    }
    private fun write(a: CaptureArtifact) {
        val target = manifest(a.sessionId)
        val temp = File(root, target.name + ".tmp")
        FileOutputStream(temp).use { out -> out.write(json.encodeToString(a).toByteArray(Charsets.UTF_8)); out.fd.sync() }
        move(temp, target)
    }
    private fun manifest(sessionId: String) = File(root, digest(sessionId.toByteArray(Charsets.UTF_8)) + ".json")
    private fun audio(sessionId: String) = File(root, digest(sessionId.toByteArray(Charsets.UTF_8)) + ".wav")
    private fun part(sessionId: String) = File(root, digest(sessionId.toByteArray(Charsets.UTF_8)) + ".part")
    private fun move(from: File, to: File) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
}

class CaptureWriter internal constructor(private val store: PrivateCaptureStore, private val artifact: CaptureArtifact, file: File) {
    private val output = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private val hash = MessageDigest.getInstance("SHA-256")
    private var count = 0L
    private var closed = false
    fun append(bytes: ByteArray, count: Int) = synchronized(store) {
        check(!closed)
        require(count > 0 && count <= bytes.size && count % 2 == 0 && this.count + count <= 480_000)
        output.write(bytes, 0, count)
        hash.update(bytes, 0, count)
        this.count += count
    }
    fun complete(): CaptureArtifact = synchronized(store) {
        check(!closed)
        require(count > 0)
        output.seek(0); output.write(wavHeader(count.toInt())); output.fd.sync()
        output.close(); closed = true
        store.complete(this, artifact, count, hash.digest().hex())
    }
    internal fun interrupt() = synchronized(store) { if (!closed) { closed = true; output.close() } }
}

internal fun wavHeader(count: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
    put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(count + 36)
    put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(1)
    putInt(16_000); putInt(32_000); putShort(2); putShort(16)
    put("data".toByteArray(Charsets.US_ASCII)); putInt(count)
}.array()
internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
