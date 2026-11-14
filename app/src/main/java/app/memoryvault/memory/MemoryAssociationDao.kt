package app.memoryvault.memory

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryAssociationDao {
    @Query("SELECT * FROM memory_review_states WHERE noteId = :noteId")
    suspend fun state(noteId: Long): MemoryReviewState?
    @Query("SELECT * FROM memory_review_states ORDER BY noteId")
    suspend fun states(): List<MemoryReviewState>
    @Query("SELECT * FROM memory_review_states ORDER BY noteId")
    fun observeStates(): Flow<List<MemoryReviewState>>
    @Upsert suspend fun saveState(state: MemoryReviewState)
    @Query("SELECT * FROM memory_association_links WHERE noteId = :noteId ORDER BY targetId, startCp, endCp, origin")
    suspend fun links(noteId: Long): List<MemoryAssociationLink>
    @Query("SELECT * FROM memory_association_links ORDER BY noteId, targetId, startCp, endCp, origin")
    fun observeLinks(): Flow<List<MemoryAssociationLink>>
    @Upsert suspend fun saveLink(link: MemoryAssociationLink)
    @Query("DELETE FROM memory_association_links WHERE noteId = :noteId AND origin = 'AI' AND status = 'SUGGESTED'")
    suspend fun deleteSuggestions(noteId: Long)
    @Query("SELECT * FROM memory_photo_descriptions WHERE cacheKey = :key")
    suspend fun photo(key: String): MemoryPhotoDescription?
    @Upsert suspend fun savePhoto(value: MemoryPhotoDescription)
}
