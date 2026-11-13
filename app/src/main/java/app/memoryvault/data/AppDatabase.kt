package app.memoryvault.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Visit::class, Photo::class, Note::class, NoteVisitLink::class, NoteLinkPolicy::class,
        AiLinkJob::class, AiLinkRequestSnapshot::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun visitDao(): VisitDao
    abstract fun photoDao(): PhotoDao
    abstract fun noteDao(): NoteDao
    abstract fun noteLinkDao(): NoteLinkDao
    abstract fun aiLinkDao(): AiLinkDao
}
