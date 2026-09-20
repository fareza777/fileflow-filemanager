package com.fileflow.app.core.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Trash lives on the same volume as the file it removes, so deleting stays an
 * instant rename instead of a multi-gigabyte copy. The folder is dot-prefixed
 * so media scanners and gallery apps ignore it, and every scan in the app
 * skips it by name.
 */
@Singleton
class TrashStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val volumes: VolumeRepository,
) {

    fun trashRootFor(volumeRoot: String): File = File(volumeRoot, TRASH_DIR)

    fun trashDirectoryFor(volumeRoot: String, timestamp: Long = System.currentTimeMillis()): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
        return File(trashRootFor(volumeRoot), stamp)
    }

    /** Reserves a free name inside the dated trash folder for this volume. */
    fun allocate(source: File): File {
        val volumeRoot = volumes.volumeRootOf(source.absolutePath)
        val directory = trashDirectoryFor(volumeRoot)
        var candidate = File(directory, source.name)
        var index = 2
        while (candidate.exists() && index < 10_000) {
            candidate = File(directory, "${source.nameWithoutExtension}~$index.${source.extension}")
            index++
        }
        return candidate
    }

    fun isTrashPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.contains("/$TRASH_DIR/") || normalized.endsWith("/$TRASH_DIR")
    }

    /** Purges anything older than the retention window across every volume. */
    suspend fun purgeOlderThan(cutoff: Long): Int = withContext(Dispatchers.IO) {
        var removed = 0
        volumes.roots().forEach { root ->
            val trashRoot = trashRootFor(root)
            val children = trashRoot.listFiles() ?: return@forEach
            children.forEach { child ->
                val isEmpty = child.listFiles()?.isEmpty() ?: true
                if (isEmpty || child.lastModified() < cutoff) {
                    if (deleteTree(child)) removed++
                }
            }
            if (trashRoot.listFiles()?.isEmpty() == true) trashRoot.delete()
        }
        removed
    }

    private fun deleteTree(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteTree(it) }
        }
        return file.delete()
    }

    companion object {
        const val TRASH_DIR = ".fileflow/trash"
        private const val TRASH_ROOT_DIR = ".fileflow"
    }
}
