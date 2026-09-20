package com.fileflow.app.core.model

/**
 * Coarse buckets used by the analyzer, the rule builder and the UI accents.
 * Deliberately small: a file manager that invents twenty categories makes
 * every list harder to scan.
 */
enum class FileCategory(val key: String) {
    IMAGES("images"),
    VIDEOS("videos"),
    AUDIO("audio"),
    DOCUMENTS("documents"),
    ARCHIVES("archives"),
    APPS("apps"),
    CODE("code"),
    OTHER("other");

    companion object {
        fun fromKey(key: String?): FileCategory =
            entries.firstOrNull { it.key == key } ?: OTHER
    }
}

enum class SortField { NAME, SIZE, MODIFIED, TYPE }

enum class SortDirection { ASCENDING, DESCENDING }

data class SortOrder(
    val field: SortField = SortField.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
) {
    fun toggled(next: SortField): SortOrder = if (next == field) {
        copy(direction = if (direction == SortDirection.ASCENDING) {
            SortDirection.DESCENDING
        } else {
            SortDirection.ASCENDING
        })
    } else {
        SortOrder(
            field = next,
            direction = if (next == SortField.NAME) {
                SortDirection.ASCENDING
            } else {
                SortDirection.DESCENDING
            },
        )
    }
}

/**
 * Every instance is an immutable snapshot, which lets the Compose compiler skip
 * rows whose item has not changed instead of recomposing an entire list when
 * one selection is toggled.
 */
@androidx.compose.runtime.Immutable
data class FileItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val extension: String,
    val mimeType: String,
    val category: FileCategory,
    val isHidden: Boolean,
    val childCount: Int = 0,
    val isReadable: Boolean = true,
) {
    val isFile: Boolean get() = !isDirectory
}

/** A storage root: internal shared storage or a mounted SD card. */
data class VolumeInfo(
    val id: String,
    val label: String,
    val rootPath: String,
    val isRemovable: Boolean,
    val isPrimary: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
}

/** One entry in an undo journal, mirrored from the database for the UI. */
data class JournalEntry(
    val id: Long,
    val groupId: String,
    val kind: String,
    val sourcePath: String,
    val targetPath: String?,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val createdAt: Long,
    val undone: Boolean,
    val undoError: String?,
)
