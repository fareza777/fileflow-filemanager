package com.fileflow.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RuleEntity::class,
        RuleLogEntity::class,
        TrashEntity::class,
        JournalEntity::class,
        ShortcutEntity::class,
        RecentFolderEntity::class,
        IndexedFileEntity::class,
        StarredFileEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class FileFlowDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun ruleLogDao(): RuleLogDao
    abstract fun trashDao(): TrashDao
    abstract fun journalDao(): JournalDao
    abstract fun shortcutDao(): ShortcutDao
    abstract fun recentFolderDao(): RecentFolderDao
    abstract fun fileIndexDao(): FileIndexDao
    abstract fun starredFileDao(): StarredFileDao
    abstract fun trashJournalDao(): TrashJournalDao

    companion object {
        const val NAME = "fileflow.db"

        /**
         * Adding the file index and starred files.
         *
         * Written out properly rather than shipped as a destructive fallback:
         * once anyone has real rules and history, an update that wipes them is
         * worse than an update that does not ship.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `indexed_files` (
                        `path` TEXT NOT NULL,
                        `parentPath` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `extension` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `modified` INTEGER NOT NULL,
                        `isDirectory` INTEGER NOT NULL DEFAULT 0,
                        `indexedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`path`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_indexed_files_parentPath` ON `indexed_files` (`parentPath`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_indexed_files_name` ON `indexed_files` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_indexed_files_extension` ON `indexed_files` (`extension`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_indexed_files_sizeBytes` ON `indexed_files` (`sizeBytes`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_indexed_files_modified` ON `indexed_files` (`modified`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `starred_files` (
                        `path` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `modified` INTEGER NOT NULL,
                        PRIMARY KEY(`path`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun build(context: Context): FileFlowDatabase =
            Room.databaseBuilder(context, FileFlowDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
