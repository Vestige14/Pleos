package app.memoryvault.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.memoryvault.memory.*
import org.json.JSONObject
import java.time.OffsetDateTime

/** Optional local filming fixture. The asset is debug-only; no recording, network or credentials. */
internal object ScenarioPreparation {
    suspend fun apply(context: Context, db: AppDatabase, allowed: () -> Boolean) {
        val input = runCatching { context.assets.open("ham_story_seed.json").bufferedReader().use { JSONObject(it.readText()) } }.getOrNull() ?: return
        val prefs = context.getSharedPreferences("ham_story_preparation", Context.MODE_PRIVATE)
        if (prefs.getBoolean("20261115-v1", false) || !allowed()) return
        val photos = mutableMapOf<String, String>()
        val imageInput = input.getJSONArray("photos")
        for (i in 0 until imageInput.length()) {
            val item = imageInput.getJSONObject(i)
            val name = item.getString("name")
            val found = context.contentResolver.query(Uri.parse("content://media/external/images/media"),
                arrayOf("_id"), "_display_name = ? AND relative_path = ?", arrayOf(name, "Pictures/HAM/"), null)?.use { cursor ->
                if (cursor.count != 1 || !cursor.moveToFirst()) null else cursor.getLong(0)
            } ?: return
            val uri = "content://media/external/images/media/$found"
            val hash = context.contentResolver.openInputStream(Uri.parse(uri))?.use { MemoryWire.sha256(it.readBytes()) } ?: return
            if (hash != item.getString("sha256")) return
            photos[item.getString("key")] = uri
        }
        db.withTransaction {
            check(allowed())
            val originalNotes = db.noteDao().getAll()
            val visitInput = input.getJSONArray("visits")
            for (i in 0 until visitInput.length()) {
                val v = visitInput.getJSONObject(i)
                db.visitDao().insertAll(listOf(Visit(v.getString("id"), v.getString("date"), v.getString("name"),
                    v.optDouble("lat", 0.0), v.optDouble("lng", 0.0),
                    OffsetDateTime.parse(v.getString("arrived")).toInstant().toEpochMilli(),
                    OffsetDateTime.parse(v.getString("left")).toInstant().toEpochMilli())))
                if (v.has("oldName")) db.visitDao().renamePreparedVisit(v.getString("id"), v.getString("date"),
                    v.getString("oldName"), v.getString("name"))
            }
            for (i in 0 until imageInput.length()) {
                val item = imageInput.getJSONObject(i)
                val uri = photos.getValue(item.getString("key"))
                val previous = db.photoDao().get(uri)
                // Only the five hash-checked imported originals; never alter capture metadata.
                db.photoDao().upsert(listOf((previous ?: Photo(uri, null, null, null, null, LinkState.UNLINKED))
                    .copy(visitId = item.getString("visit"), linkState = LinkState.CONFIRMED, accessible = true)))
            }
            val cutoff = OffsetDateTime.parse(input.getString("recordedAfter")).toInstant().toEpochMilli()
            val notes = originalNotes.filter { it.kind == NoteKind.VOICE && it.capturedAt >= cutoff }
            fun compact(value: String) = value.filter { it.isLetterOrDigit() }
            fun matching(patterns: List<String>) = notes.filter { n -> patterns.any { compact(n.text).contains(compact(it)) } }
            val store = MemoryAssociationStore(db)
            val rules = input.getJSONArray("links")
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                fun strings(key: String): List<String> = rule.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                for (note in matching(strings("contains"))) {
                    store.register(note.id)
                    val item = store.items().single { it.note.id == note.id }
                    if (item.userLocked || item.links.any { it.status == "CONFIRMED" }) continue
                    val sources = mutableListOf<MemorySource>()
                    for (id in strings("visits")) {
                        val visit = db.visitDao().getAll().single { it.id == id }
                        sources += MemorySource(MemoryCandidate("visit:${MemoryWire.digest(id)}", MemoryKind.VISIT,
                            visit.date, MemoryTimeBasis.VISIT, "", MemoryWire.timestamp(visit.arrivedAt), null,
                            visit.placeName, null, null), id, MemorySourceFingerprint.visit(visit))
                    }
                    for (key in strings("photos")) {
                        val photo = checkNotNull(db.photoDao().get(photos.getValue(key)))
                        sources += MemorySource(MemoryCandidate("photo:${MemoryWire.digest(photo.uri)}", MemoryKind.PHOTO,
                            null, MemoryTimeBasis.PHOTO_CAPTURE, "", null, null, null, null, null),
                            photo.uri, MemorySourceFingerprint.photo(photo))
                    }
                    for (other in matching(strings("relatedContains")).filter { it.id != note.id }) {
                        sources += MemorySource(MemoryCandidate("note:${other.id}", MemoryKind.NOTE, null,
                            MemoryTimeBasis.NOTE_EXPERIENCE, other.text, MemoryWire.timestamp(other.capturedAt), null, null, null, null),
                            other.id.toString(), MemorySourceFingerprint.note(other))
                    }
                    if (sources.isNotEmpty()) check(store.correct(note.id, note.revision, item.userRevision, sources,
                        sources.map { it.candidate.id }.toSet(), 0, note.text.codePointCount(0, note.text.length), allowed) == MemoryReviewResult.SAVED)
                }
            }
            check(originalNotes == db.noteDao().getAll()) // Text, timestamps, session IDs and revisions unchanged.
            check(allowed())
        }
        prefs.edit().putBoolean("20261115-v1", true).apply()
    }
}
