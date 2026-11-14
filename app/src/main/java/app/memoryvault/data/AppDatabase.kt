package app.memoryvault.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.memoryvault.memory.MemoryReviewState
import app.memoryvault.memory.MemoryAssociationLink
import app.memoryvault.memory.MemoryPhotoDescription
import app.memoryvault.memory.MemoryAssociationDao

@Database(
    entities = [Visit::class, Photo::class, Note::class, NoteVisitLink::class, NoteLinkPolicy::class,
        AiLinkJob::class, AiLinkRequestSnapshot::class, MemoryReviewState::class,
        MemoryAssociationLink::class, MemoryPhotoDescription::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN accessible INTEGER NOT NULL DEFAULT 1")
            }
        }
    }

    abstract fun visitDao(): VisitDao
    abstract fun photoDao(): PhotoDao
    abstract fun noteDao(): NoteDao
    abstract fun noteLinkDao(): NoteLinkDao
    abstract fun aiLinkDao(): AiLinkDao
    abstract fun memoryAssociationDao(): MemoryAssociationDao
}
