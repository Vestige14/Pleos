package app.memoryvault.memory

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Root registers this alongside MIGRATION_1_2 and bumps AppDatabase to v3. */
object MemoryAssociationMigration {
    val FROM_2_TO_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `memory_review_states` (`noteId` INTEGER NOT NULL, `status` TEXT NOT NULL, `activeJobId` TEXT, `eventDateOverride` TEXT, `pageOffset` INTEGER NOT NULL, `nextOffset` INTEGER NOT NULL, `hasMore` INTEGER NOT NULL, `issues` TEXT NOT NULL, `errorCode` TEXT, PRIMARY KEY(`noteId`), FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `memory_association_links` (`noteId` INTEGER NOT NULL, `targetId` TEXT NOT NULL, `kind` TEXT NOT NULL, `startCp` INTEGER NOT NULL, `endCp` INTEGER NOT NULL, `origin` TEXT NOT NULL, `status` TEXT NOT NULL, `sourceJson` TEXT NOT NULL, `requestId` TEXT, PRIMARY KEY(`noteId`, `targetId`, `kind`, `startCp`, `endCp`, `origin`), FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `memory_photo_descriptions` (`cacheKey` TEXT NOT NULL, `imageSha256` TEXT NOT NULL, `model` TEXT NOT NULL, `analysisVersion` TEXT NOT NULL, `requestId` TEXT NOT NULL, `state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, `leaseUntil` INTEGER, `attemptToken` TEXT, `description` TEXT, `labelsJson` TEXT, `errorCode` TEXT, PRIMARY KEY(`cacheKey`))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_photo_descriptions_imageSha256_model_analysisVersion` ON `memory_photo_descriptions` (`imageSha256`, `model`, `analysisVersion`)")
        }
    }
}
