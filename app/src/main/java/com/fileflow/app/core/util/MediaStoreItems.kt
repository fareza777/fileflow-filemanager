package com.fileflow.app.core.util

import android.database.Cursor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.storage.StorageAccess
import java.io.File

/**
 * Turning a MediaStore row into a [FileItem], on ROMs that are not all alike.
 *
 * `_data` is the column every file manager wants and the one Android has been
 * trying to retire since API 29. Most builds still provide it, some OEM builds
 * drop it from the projection. Reading it with `getColumnIndexOrThrow` meant
 * those devices did not crash — they returned an empty list, because both call
 * sites wrap the read in `runCatching` — so the home screen's recent files and
 * the entire calendar came up blank with nothing in the logs to explain why.
 *
 * When `_data` is missing the path is rebuilt from `relative_path` plus the
 * volume root, which is what that pair exists for. Below API 29 `_data` is
 * guaranteed and `relative_path` does not exist, so the two cases never overlap.
 */
object MediaStoreItems {

    /**
     * The columns every consumer needs. `relative_path` and `volume_name` are
     * only requested where they exist (API 29+): asking MediaStore for a column
     * it does not know is an IllegalArgumentException, not an ignored hint.
     */
    val PROJECTION: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.VOLUME_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
    } else {
        arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
    }

    /**
     * Builds the item, or returns null when the row cannot be turned into a
     * path that actually exists. A guessed path is worse than no row: tapping it
     * would open the wrong file or nothing at all.
     *
     * @param volumeRoots mount points of every volume the app can see, used to
     * resolve a relative path that belongs to a removable card.
     */
    fun from(
        cursor: Cursor,
        volumeRoots: List<String>,
        requireExists: Boolean = true,
    ): FileItem? {
        val name = cursor.stringColumn(MediaStore.Files.FileColumns.DISPLAY_NAME)
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val path = resolvePath(cursor, volumeRoots) ?: return null
        val file = File(path)
        if (requireExists && (!file.exists() || file.isDirectory)) return null

        val extension = MimeTypes.extensionOf(name)
        val mime = cursor.stringColumn(MediaStore.Files.FileColumns.MIME_TYPE)
            ?: MimeTypes.mimeFor(extension)

        return FileItem(
            path = path,
            name = name,
            isDirectory = false,
            sizeBytes = cursor.longColumn(MediaStore.Files.FileColumns.SIZE),
            // MediaStore reports seconds; the rest of the app works in millis.
            lastModified = cursor.longColumn(MediaStore.Files.FileColumns.DATE_MODIFIED) * 1000L,
            extension = extension,
            mimeType = mime,
            category = MimeTypes.categoryOf(extension),
            isHidden = name.startsWith("."),
        )
    }

    /**
     * Whether a row is a file the user thinks of as theirs.
     *
     * The media index knows about everything, including the parts of the
     * filesystem nobody browses: `.thumbnails` caches, FileFlow's own trash and
     * partial copies, and the `Android/data` / `Android/obb` trees every app is
     * walled off from. Feeding those into "recent files" or the calendar
     * produced entries from folders the user had never opened, so the noise is
     * dropped in one place, for every feed that reads MediaStore.
     */
    fun isUserFile(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        if (StorageAccess.RESTRICTED_SEGMENTS.any { normalized.contains(it) }) return false
        // Hidden segments cover the caches (`.thumbnails`), the app's own
        // working directory (`.fileflow`) and dotfiles, none of which are
        // "what arrived recently" in any sense a user means.
        return normalized.split('/').none { it.startsWith(".") }
    }

    private fun resolvePath(cursor: Cursor, volumeRoots: List<String>): String? {
        cursor.stringColumn(MediaStore.Files.FileColumns.DATA)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        // No absolute path: rebuild it. `relative_path` is the directory with a
        // trailing separator ("DCIM/Camera/"), relative to that volume's root.
        val relative = cursor.stringColumn(MediaStore.Files.FileColumns.RELATIVE_PATH)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val root = volumeRootOf(cursor, volumeRoots) ?: return null
        return File(File(root), relative).path
    }

    private fun volumeRootOf(cursor: Cursor, volumeRoots: List<String>): String? {
        val primary = Environment.getExternalStorageDirectory()?.absolutePath
        val volume = cursor.stringColumn(MediaStore.Files.FileColumns.VOLUME_NAME)
            ?: return primary
        return when {
            // The internal volume, which is what almost every row is.
            volume == MediaStore.VOLUME_EXTERNAL_PRIMARY ||
                volume == MediaStore.VOLUME_EXTERNAL ||
                volume == "external_primary" -> primary
            else -> {
                // A removable card is named "external-1A2B-3C4D" and mounted at
                // "/storage/1A2B-3C4D". Match on the suffix; when nothing
                // matches, fall back to primary and let the existence check
                // drop the row rather than inventing a path.
                val uuid = volume.substringAfter("external-", "")
                volumeRoots.firstOrNull { uuid.isNotEmpty() && it.endsWith(uuid) } ?: primary
            }
        }
    }
}
