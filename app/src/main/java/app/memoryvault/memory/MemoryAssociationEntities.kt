package app.memoryvault.memory

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.memoryvault.data.Note

/** All tables are additive in the existing DB; no original file, note or photo decision is moved. */
@Entity(tableName = "memory_review_states", foreignKeys = [ForeignKey(Note::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE)])
data class MemoryReviewState(
    @PrimaryKey val noteId: Long,
    val status: String = "WAITING",
    val activeJobId: String? = null,
    val eventDateOverride: String? = null,
    val pageOffset: Int = 0,
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    val issues: String = "",
    val errorCode: String? = null,
)

@Entity(tableName = "memory_association_links", primaryKeys = ["noteId", "targetId", "kind", "startCp", "endCp", "origin"],
    foreignKeys = [ForeignKey(Note::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE)])
data class MemoryAssociationLink(
    val noteId: Long, val targetId: String, val kind: String, val startCp: Int, val endCp: Int,
    val origin: String, val status: String, val sourceJson: String, val requestId: String?,
) { override fun toString() = "MemoryAssociationLink([private])" }

/** Receipt persists *before* a paid image call; a lost outcome is never automatically called again. */
@Entity(tableName = "memory_photo_descriptions", indices = [Index(value = ["imageSha256", "model", "analysisVersion"], unique = true)])
data class MemoryPhotoDescription(
    @PrimaryKey val cacheKey: String, val imageSha256: String, val model: String, val analysisVersion: String,
    val requestId: String, val state: String, val attempts: Int, val nextAttemptAt: Long,
    val leaseUntil: Long?, val attemptToken: String?, val description: String?, val labelsJson: String?, val errorCode: String?,
) { override fun toString() = "MemoryPhotoDescription([private])" }
