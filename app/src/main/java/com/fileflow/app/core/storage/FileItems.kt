package com.fileflow.app.core.storage

import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.util.MimeTypes
import java.io.File
import kotlinx.serialization.Serializable

/**
 * Conflict handling is resolved before an operation starts rather than mid-way,
 * so a batch never half-finishes because a dialog appeared on file 900.
 */
@Serializable
enum class ConflictPolicy { RENAME, OVERWRITE, SKIP }

enum class OperationKind { COPY, MOVE, TRASH, DELETE, RENAME, CREATE_FOLDER, ARCHIVE, RESTORE }

data class OperationProgress(
    val current: Int,
    val total: Int,
    val currentName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
) {
    val fraction: Float
        get() = when {
            bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
            total > 0 -> (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}

data class OperationIssue(val path: String, val message: String)

/**
 * One reversible change. These are what the undo journal stores, so the fields
 * mirror exactly what is needed to walk the change back.
 */
data class AppliedChange(
    val kind: OperationKind,
    val sourcePath: String,
    val targetPath: String?,
    val sizeBytes: Long,
    val isDirectory: Boolean,
)

data class OperationReport(
    val kind: OperationKind,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val bytesMoved: Long = 0,
    val cancelled: Boolean = false,
    val changes: List<AppliedChange> = emptyList(),
    val issues: List<OperationIssue> = emptyList(),
    /**
     * The journal group these changes were written to. Callers need it to offer
     * an undo for exactly this operation — without it an "undo" button has to
     * guess the id, and guessing is how an undo silently does nothing.
     */
    val groupId: String = "",
) {
    val total: Int get() = succeeded + failed + skipped
    val isClean: Boolean get() = failed == 0 && !cancelled
}

object FileItems {

    fun from(file: File, childCount: Int = 0): FileItem {
        val name = file.name
        val isDirectory = file.isDirectory
        val extension = if (isDirectory) "" else MimeTypes.extensionOf(name)
        return FileItem(
            path = file.absolutePath,
            name = name,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else file.length(),
            lastModified = file.lastModified(),
            extension = extension,
            mimeType = if (isDirectory) FOLDER_MIME else MimeTypes.mimeFor(extension),
            category = if (isDirectory) FileCategory.OTHER else MimeTypes.categoryOf(extension),
            isHidden = name.startsWith("."),
            childCount = childCount,
            isReadable = file.canRead(),
        )
    }

    const val FOLDER_MIME = "resource/folder"

    /**
     * Directories are listed in one pass. Child counts are computed here, on
     * the calling background thread, because the alternative — asking each row
     * how many children it has — puts a directory read on the main thread for
     * every visible row on every frame.
     */
    fun listDirectory(directory: File, includeHidden: Boolean): List<FileItem> {
        val children = directory.listFiles() ?: return emptyList()
        return children
            .asSequence()
            .filter { includeHidden || !it.name.startsWith(".") }
            .map { child ->
                val childCount = if (child.isDirectory) {
                    child.listFiles()?.count { includeHidden || !it.name.startsWith(".") } ?: 0
                } else {
                    0
                }
                from(child, childCount)
            }
            .sortedWith(COMPARATOR)
            .toList()
    }

    private val COMPARATOR = compareByDescending<FileItem> { it.isDirectory }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
}
