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
        ContentIndexEntity::class,
        ContentVectorEntity::class,
        PhotoIndexEntity::class,
        PhotoVectorEntity::class,
    ],
    version = 5,
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
    abstract fun contentIndexDao(): ContentIndexDao
    abstract fun photoIndexDao(): PhotoIndexDao
    abstract fun photoVectorDao(): PhotoVectorDao

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

        /**
         * Adding the content index and its vectors.
         *
         * Two plain tables: the text of every readable document, and the vector
         * that stands in for "about the same thing". Both are rebuildable from
         * the filesystem, so an update that cannot migrate them would only cost
         * a re-index — but they are created properly anyway, because a database
         * version that fails to open takes the rules and the journal with it.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `content_index` (
                        `path` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `extension` TEXT NOT NULL,
                        `modified` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `body` TEXT NOT NULL,
                        `folded` TEXT NOT NULL,
                        `indexedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`path`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_index_modified` ON `content_index` (`modified`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_index_name` ON `content_index` (`name`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `content_vectors` (
                        `path` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `dimension` INTEGER NOT NULL,
                        `vector` BLOB NOT NULL,
                        `indexedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`path`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Adding the photo index.
         *
         * One table, rebuildable from the photos themselves: dropping it would
         * cost a re-index and nothing else, so a migration that fails to match
         * the generated schema would be a self-inflicted wound. Written the way
         * Room would have written it.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `photo_index` (
                        `path` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `modified` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `folded` TEXT NOT NULL,
                        `labels` TEXT NOT NULL,
                        `indexedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`path`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_index_modified` ON `photo_index` (`modified`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_index_name` ON `photo_index` (`name`)")
            }
        }

        /**
         * Adding the semantic vectors.
         *
         * A table that can be dropped at any time by deleting the model, which
         * is why it is separate from the text index: the cheap tier stays when
         * the expensive one goes.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `photo_vectors` (
                        `path` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `modified` INTEGER NOT NULL,
                        `dimension` INTEGER NOT NULL,
                        `vector` BLOB NOT NULL,
                        `model` TEXT NOT NULL,
                        `indexedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`path`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun build(context: Context): FileFlowDatabase =
            Room.databaseBuilder(context, FileFlowDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
