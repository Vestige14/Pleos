package app.memoryvault.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VisitDao {
    @Query("SELECT DISTINCT date FROM visits ORDER BY date")
    fun observeDates(): Flow<List<String>>

    @Query("UPDATE visits SET placeName = :newName WHERE id = :id AND date = :date AND placeName = :oldName")
    suspend fun renamePreparedVisit(id: String, date: String, oldName: String, newName: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(visits: List<Visit>)

    @Query("SELECT * FROM visits WHERE date = :date ORDER BY arrivedAt, id")
    fun observeDay(date: String): Flow<List<Visit>>

    @Query("SELECT * FROM visits WHERE date = :date ORDER BY arrivedAt, id")
    suspend fun getDay(date: String): List<Visit>

    @Query("SELECT * FROM visits ORDER BY arrivedAt, id")
    suspend fun getAll(): List<Visit>
}

@Dao
interface PhotoDao {
    @Upsert
    suspend fun upsert(photos: List<Photo>)

    @Query("SELECT * FROM photos WHERE uri = :uri")
    suspend fun get(uri: String): Photo?

    @Query("SELECT * FROM photos ORDER BY takenAt, uri")
    suspend fun getAll(): List<Photo>

    @Query("SELECT * FROM photos ORDER BY takenAt, uri")
    fun observeAll(): Flow<List<Photo>>
}

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: Note): Long

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): Note?

    @Query("SELECT * FROM notes ORDER BY capturedAt, id")
    suspend fun getAll(): List<Note>

    @Query("SELECT * FROM notes ORDER BY capturedAt, id")
    fun observeAll(): Flow<List<Note>>

    @Query("UPDATE notes SET text = :text, revision = revision + 1 WHERE id = :id AND kind = 'TYPED'")
    suspend fun updateTypedText(id: Long, text: String): Int

    @Query("SELECT * FROM notes WHERE voiceSessionId = :sessionId LIMIT 1")
    suspend fun getByVoiceSession(sessionId: String): Note?
}

@Dao
interface NoteLinkDao {
    @Query("SELECT * FROM note_visit_links WHERE noteId = :noteId")
    suspend fun getForNote(noteId: Long): List<NoteVisitLink>

    @Query("DELETE FROM note_visit_links WHERE noteId = :noteId AND origin = 'AI' AND status = 'SUGGESTED'")
    suspend fun deleteAiSuggestions(noteId: Long)

    @Insert
    suspend fun insert(link: NoteVisitLink)

    @Query("SELECT * FROM note_visit_links ORDER BY noteId, visitId, startCp, endCp, origin")
    fun observeAll(): Flow<List<NoteVisitLink>>

    @Insert
    suspend fun insertPolicy(policy: NoteLinkPolicy)

    @Query("SELECT * FROM note_link_policies WHERE noteId = :noteId")
    suspend fun getPolicy(noteId: Long): NoteLinkPolicy?

    @Query("SELECT * FROM note_link_policies ORDER BY noteId")
    suspend fun getAllPolicies(): List<NoteLinkPolicy>

    @Upsert
    suspend fun savePolicy(policy: NoteLinkPolicy)

    @Query("UPDATE note_visit_links SET status = :status WHERE noteId = :noteId AND origin = 'AI' AND status = 'SUGGESTED'")
    suspend fun decideSuggestions(noteId: Long, status: String)

    @Upsert
    suspend fun saveLinkForReview(link: NoteVisitLink)

    @Query("UPDATE note_visit_links SET status = 'REJECTED' WHERE noteId = :noteId AND status != 'REJECTED'")
    suspend fun rejectActiveForReview(noteId: Long)
}

@Dao
interface AiLinkDao {
    @Query("SELECT * FROM ai_link_jobs WHERE jobId = :jobId")
    suspend fun getJob(jobId: String): AiLinkJob?

    @Update
    suspend fun updateJob(job: AiLinkJob)

    @Query("SELECT * FROM ai_link_jobs ORDER BY jobId")
    fun observeJobs(): Flow<List<AiLinkJob>>

    @Insert
    suspend fun insertJob(job: AiLinkJob)

    @Insert
    suspend fun insertSnapshot(snapshot: AiLinkRequestSnapshot)

    @Query("SELECT * FROM ai_link_jobs ORDER BY jobId")
    suspend fun getAllJobs(): List<AiLinkJob>

    @Query("SELECT * FROM ai_link_jobs WHERE noteId = :noteId AND noteRevision = :noteRevision AND userRevision = :userRevision AND promptVersion = 'memory-links-v1' LIMIT 1")
    suspend fun getForRevision(noteId: Long, noteRevision: Int, userRevision: Int): AiLinkJob?

    @Query("SELECT * FROM ai_link_request_snapshots WHERE jobId = :jobId")
    suspend fun getSnapshot(jobId: String): AiLinkRequestSnapshot?

    @Query("SELECT * FROM ai_link_request_snapshots ORDER BY jobId")
    suspend fun getAllSnapshots(): List<AiLinkRequestSnapshot>
}
