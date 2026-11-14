package app.memoryvault.share

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "share_selections")
data class ShareSelectionRow(@PrimaryKey val date: String, val visitsJson: String, val photosJson: String, val captionsJson: String)

@Dao
interface ShareSelectionDao {
    @Query("SELECT * FROM share_selections WHERE date = :date")
    suspend fun load(date: String): ShareSelectionRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: ShareSelectionRow)
}

@Database(entities = [ShareSelectionRow::class], version = 1, exportSchema = true)
abstract class ShareSelectionDatabase : RoomDatabase() {
    abstract fun selectionDao(): ShareSelectionDao
    internal val selectionMutex = Mutex()

    companion object {
        @Volatile private var instance: ShareSelectionDatabase? = null
        fun open(context: Context): ShareSelectionDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ShareSelectionDatabase::class.java,
                "share-selections.db").build().also { instance = it }
        }
    }
}

class RoomShareSelectionStore(private val database: ShareSelectionDatabase) : ShareSelectionStore {
    override suspend fun load(date: String): ShareSelection? = database.selectionMutex.withLock {
        val row = database.selectionDao().load(date) ?: return@withLock null
        ShareSelection(row.date, Json.decodeFromString<List<String>>(row.visitsJson).toSet(),
            Json.decodeFromString<List<String>>(row.photosJson).toSet(),
            Json.decodeFromString<Map<String, String>>(row.captionsJson)).ownedCopy()
    }

    override suspend fun save(selection: ShareSelection) {
        val snapshot = selection.ownedCopy()
        database.selectionMutex.withLock {
            database.selectionDao().save(ShareSelectionRow(snapshot.date,
                Json.encodeToString(snapshot.includedVisitIds.toList()),
                Json.encodeToString(snapshot.selectedPhotoUris.toList()), Json.encodeToString(snapshot.captions)))
        }
    }
}
