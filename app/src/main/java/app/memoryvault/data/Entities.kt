package app.memoryvault.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.memoryvault.domain.AiLinkJobState
import app.memoryvault.domain.LinkOrigin
import app.memoryvault.domain.NoteLinkStatus

@Entity(tableName = "visits")
data class Visit(
    @PrimaryKey val id: String,
    val date: String,
    val placeName: String,
    val lat: Double,
    val lng: Double,
    val arrivedAt: Long,
    val leftAt: Long,
)

enum class LinkState { CANDIDATE, CONFIRMED, REJECTED, UNLINKED }
val LinkState.isUserDecided: Boolean get() = this == LinkState.CONFIRMED || this == LinkState.REJECTED
enum class NoteKind { TYPED, VOICE }

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(Visit::class, ["id"], ["visitId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("visitId")],
)
data class Photo(
    @PrimaryKey val uri: String,
    val takenAt: Long?,
    val lat: Double?,
    val lng: Double?,
    val visitId: String?,
    val linkState: LinkState,
    @ColumnInfo(defaultValue = "1") val accessible: Boolean = true,
)

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(Visit::class, ["id"], ["visitId"]),
        ForeignKey(Visit::class, ["id"], ["captureVisitId"]),
    ],
    indices = [Index("visitId"), Index("captureVisitId"), Index("sourceDate"), Index("voiceSessionId", unique = true)],
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val visitId: String?,
    val text: String,
    val kind: NoteKind,
    val createdAt: Long,
    val captureVisitId: String? = null,
    val capturedAt: Long = createdAt,
    val sourceDate: String,
    val revision: Int = 1,
    val voiceSessionId: String? = null,
    val refinedText: String? = null,
)

@Entity(
    tableName = "note_visit_links",
    primaryKeys = ["noteId", "visitId", "startCp", "endCp", "origin"],
    foreignKeys = [
        ForeignKey(Note::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Visit::class, ["id"], ["visitId"]),
    ],
    indices = [Index("visitId")],
)
data class NoteVisitLink(
    val noteId: Long,
    val visitId: String,
    val startCp: Int,
    val endCp: Int,
    val origin: LinkOrigin,
    val status: NoteLinkStatus,
    val requestId: String? = null,
)

@Entity(
    tableName = "note_link_policies",
    foreignKeys = [ForeignKey(Note::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE)],
)
data class NoteLinkPolicy(@PrimaryKey val noteId: Long, val userRevision: Int = 0, val userLocked: Boolean = false)

@Entity(
    tableName = "ai_link_jobs",
    foreignKeys = [ForeignKey(Note::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["noteId", "noteRevision", "userRevision"])],
)
data class AiLinkJob(
    @PrimaryKey val jobId: String,
    val noteId: Long,
    val noteRevision: Int,
    val userRevision: Int,
    val contextHash: String,
    val promptVersion: String,
    val state: AiLinkJobState,
    val attempts: Int,
    val nextAttemptAt: Long,
    val leaseUntil: Long? = null,
    val attemptToken: String? = null,
    val lastErrorCode: String? = null,
)

@Entity(
    tableName = "ai_link_request_snapshots",
    foreignKeys = [ForeignKey(AiLinkJob::class, ["jobId"], ["jobId"], onDelete = ForeignKey.CASCADE)],
)
data class AiLinkRequestSnapshot(@PrimaryKey val jobId: String, val candidatesJson: String)

data class VisitCard(
    val visit: Visit,
    val photos: List<Photo>,
    val notes: List<Note>,
    val noteLinks: List<NoteVisitLink> = emptyList(),
)
data class DayDraft(
    val date: String,
    val cards: List<VisitCard>,
    val pendingNotes: List<Note> = emptyList(),
    val unlinkedPhotos: List<Photo> = emptyList(),
    val inaccessiblePhotoCount: Int = 0,
)
