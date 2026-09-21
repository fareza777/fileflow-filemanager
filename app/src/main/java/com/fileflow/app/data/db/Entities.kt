package com.fileflow.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    /** Serialised [com.fileflow.app.domain.rules.RuleSpec]; the shape of a rule
     *  evolves faster than a schema, so it lives as one JSON column. */
    val specJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long? = null,
    val lastRunStatus: String? = null,
    val lastMatchedCount: Int = 0,
    val isTemplate: Boolean = false,
)

@Entity(
    tableName = "rule_logs",
    indices = [Index("ruleId"), Index("startedAt")],
)
data class RuleLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val ruleName: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** RUNNING | SUCCESS | PARTIAL | FAILED | CANCELLED | UNDONE */
    val status: String,
    /** MANUAL | SCHEDULED | PREVIEW */
    val trigger: String,
    val matched: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val bytesMoved: Long = 0,
    val undoneAt: Long? = null,
    val note: String? = null,
)

@Entity(tableName = "trash", indices = [Index("volumeRoot"), Index("deletedAt")])
data class TrashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalPath: String,
    val trashPath: String,
    val name: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val deletedAt: Long,
    val volumeRoot: String,
)

@Entity(
    tableName = "journal",
    indices = [Index("groupId"), Index("createdAt"), Index("kind")],
)
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String,
    /** COPY | MOVE | TRASH | DELETE | RENAME | CREATE_FOLDER */
    val kind: String,
    val sourcePath: String,
    val targetPath: String?,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val createdAt: Long,
    val undone: Boolean = false,
    val undoError: String? = null,
)

@Entity(tableName = "shortcuts")
data class ShortcutEntity(
    @PrimaryKey val path: String,
    val name: String,
    val position: Int = 0,
    val addedAt: Long,
)

@Entity(tableName = "recent_folders")
data class RecentFolderEntity(
    @PrimaryKey val path: String,
    val name: String,
    val visitedAt: Long,
    val visits: Int = 1,
)

/**
 * A row per file on the device, so a search is a query instead of a walk.
 *
 * Walking the filesystem took ten to sixty seconds on a full phone, every time.
 * The index is refreshed in the background and updated as the app changes
 * things, which turns the same search into a few milliseconds.
 */
@Entity(
    tableName = "indexed_files",
    indices = [
        Index("parentPath"),
        Index("name"),
        Index("extension"),
        Index("sizeBytes"),
        Index("modified"),
    ],
)
data class IndexedFileEntity(
    @PrimaryKey val path: String,
    val parentPath: String,
    val name: String,
    val extension: String,
    val sizeBytes: Long,
    val modified: Long,
    val isDirectory: Boolean = false,
    val indexedAt: Long,
)

/** A file the user has starred, which outlives any folder move. */
@Entity(tableName = "starred_files")
data class StarredFileEntity(
    @PrimaryKey val path: String,
    val name: String,
    val addedAt: Long,
    /** Kept separate from path so a rename does not silently lose the star. */
    val sizeBytes: Long = 0,
    val modified: Long = 0,
)

/**
 * The text of a file, for searching inside documents rather than names.
 *
 * A plain table rather than an FTS index: Room's FTS entities have to be
 * created by a migration that matches their generated schema exactly, and a
 * mismatch is a broken database on update. A body column with a bounded size
 * keeps the query a scan — a few hundred milliseconds over a few thousand
 * documents — which is a fair price for a schema that cannot go wrong.
 */
@Entity(
    tableName = "content_index",
    indices = [Index("modified"), Index("name")],
)
data class ContentIndexEntity(
    @PrimaryKey val path: String,
    val name: String,
    val extension: String,
    val modified: Long,
    val sizeBytes: Long,
    /** The readable text, capped when it was indexed. */
    val body: String,
    /** Lower-cased body, so the search does not lower-case it on every query. */
    val folded: String,
    val indexedAt: Long,
)

/**
 * The same document as a vector, for the search that is not substring matching.
 *
 * Kept in its own table because it is only ever read by one feature and it is
 * the bulky one: a document that fails to embed can still be found by its
 * words, and dropping the vectors should never cost the text index.
 */
@Entity(tableName = "content_vectors")
data class ContentVectorEntity(
    @PrimaryKey val path: String,
    val name: String,
    val dimension: Int,
    /** Floats, little-endian, normalised. */
    val vector: ByteArray,
    val indexedAt: Long,
)

/**
 * One reversible batch of changes, as summarised for the "recent changes" list.
 */
data class JournalGroupSummary(
    val groupId: String,
    val startedAt: Long,
    val entryCount: Int,
    val undoneCount: Int,
    val bytes: Long,
    val moves: Int,
    val copies: Int,
    val trashes: Int,
    val renames: Int,
) {
    val allUndone: Boolean get() = undoneCount == entryCount
    val canUndo: Boolean get() = !allUndone && (moves + copies + trashes + renames) > 0
}
