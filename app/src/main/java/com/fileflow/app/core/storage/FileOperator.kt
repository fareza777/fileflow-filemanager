package com.fileflow.app.core.storage

import com.fileflow.app.core.util.Paths
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import android.content.Context

/**
 * The only place in the app that touches the filesystem for writes. Everything
 * upstream — the browser, the rules engine, the cleaners — goes through these
 * calls, which is what makes a single undo journal possible: each successful
 * mutation is reported back as an [AppliedChange] the moment it lands.
 */
@Singleton
class FileOperator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val volumes: VolumeRepository,
    private val trashStore: TrashStore,
) {

    // ---------------------------------------------------------------- create

    fun createFolder(parent: File, rawName: String): Result<File> {
        if (!Paths.isValidFileName(rawName)) {
            return Result.failure(IOException("Invalid folder name"))
        }
        val target = File(parent, rawName.trim())
        if (target.exists()) {
            return Result.failure(IOException("A folder with that name already exists"))
        }
        return if (target.mkdirs()) Result.success(target)
        else Result.failure(IOException("Could not create the folder"))
    }

    // ---------------------------------------------------------------- rename

    suspend fun rename(file: File, rawNewName: String): Result<AppliedChange> =
        withContext(Dispatchers.IO) {
            val newName = rawNewName.trim()
            if (!Paths.isValidFileName(newName)) {
                return@withContext Result.failure(IOException("Invalid file name"))
            }
            if (newName == file.name) {
                return@withContext Result.failure(IOException("The name is unchanged"))
            }
            val parent = file.parentFile
                ?: return@withContext Result.failure(IOException("No parent folder"))
            val target = File(parent, newName)
            if (target.exists()) {
                return@withContext Result.failure(
                    IOException("An item named \"$newName\" already exists here")
                )
            }
            if (!file.renameTo(target)) {
                return@withContext Result.failure(IOException("Rename was rejected by the system"))
            }
            Result.success(
                AppliedChange(
                    kind = OperationKind.RENAME,
                    sourcePath = file.absolutePath,
                    targetPath = target.absolutePath,
                    sizeBytes = if (file.isFile) target.length() else 0L,
                    isDirectory = target.isDirectory,
                )
            )
        }

    // ---------------------------------------------------------- copy / move

    suspend fun copy(
        sources: List<File>,
        destination: File,
        policy: ConflictPolicy,
        targetName: String? = null,
        onProgress: (OperationProgress) -> Unit = {},
        onApplied: suspend (AppliedChange) -> Unit = {},
    ): OperationReport = transfer(
        kind = OperationKind.COPY,
        sources = sources,
        destination = destination,
        policy = policy,
        moveAfterwards = false,
        targetName = targetName,
        onProgress = onProgress,
        onApplied = onApplied,
    )

    suspend fun move(
        sources: List<File>,
        destination: File,
        policy: ConflictPolicy,
        targetName: String? = null,
        onProgress: (OperationProgress) -> Unit = {},
        onApplied: suspend (AppliedChange) -> Unit = {},
    ): OperationReport = transfer(
        kind = OperationKind.MOVE,
        sources = sources,
        destination = destination,
        policy = policy,
        moveAfterwards = true,
        targetName = targetName,
        onProgress = onProgress,
        onApplied = onApplied,
    )

    private suspend fun transfer(
        kind: OperationKind,
        sources: List<File>,
        destination: File,
        policy: ConflictPolicy,
        moveAfterwards: Boolean,
        targetName: String?,
        onProgress: (OperationProgress) -> Unit,
        onApplied: suspend (AppliedChange) -> Unit,
    ): OperationReport = withContext(Dispatchers.IO) {
        val issues = mutableListOf<OperationIssue>()
        val changes = mutableListOf<AppliedChange>()

        if (!destination.exists() && !destination.mkdirs()) {
            return@withContext OperationReport(
                kind = kind,
                failed = sources.size,
                issues = listOf(
                    OperationIssue(destination.absolutePath, "Destination is not writable")
                ),
            )
        }

        val totalBytes = sources.sumOf { sizeOf(it) }
        var bytesDone = 0L
        var succeeded = 0
        var failed = 0
        var skipped = 0

        // The destination must have room for the whole batch, otherwise the
        // operation dies halfway and leaves the user with a half-moved folder.
        if (kind == OperationKind.COPY || !volumes.isSameVolume(
                sources.firstOrNull()?.absolutePath ?: destination.absolutePath,
                destination.absolutePath,
            )
        ) {
            val free = volumes.freeSpaceAt(destination.absolutePath)
            if (free != null && free < totalBytes) {
                return@withContext OperationReport(
                    kind = kind,
                    failed = sources.size,
                    issues = listOf(
                        OperationIssue(
                            destination.absolutePath,
                            "Not enough space: ${totalBytes - free} more bytes needed",
                        )
                    ),
                )
            }
        }

        try {
            sources.forEachIndexed { index, source ->
                coroutineContext.ensureActive()
                onProgress(
                    OperationProgress(
                        current = index,
                        total = sources.size,
                        currentName = source.name,
                        bytesDone = bytesDone,
                        bytesTotal = totalBytes,
                    )
                )

                if (!source.exists()) {
                    failed++
                    issues += OperationIssue(source.absolutePath, "Source no longer exists")
                    return@forEachIndexed
                }
                if (!moveAfterwards && Paths.isInside(destination.absolutePath, source.absolutePath)) {
                    failed++
                    issues += OperationIssue(source.absolutePath, "Cannot copy a folder into itself")
                    return@forEachIndexed
                }
                if (moveAfterwards && Paths.isInside(destination.absolutePath, source.absolutePath)) {
                    failed++
                    issues += OperationIssue(source.absolutePath, "Cannot move a folder into itself")
                    return@forEachIndexed
                }

                // A rule may rename while it moves, in which case the wanted
                // name beats the source's own.
                val wantedName = targetName
                    ?.takeIf { sources.size == 1 && it.isNotBlank() }
                    ?: source.name
                var target = File(destination, wantedName)
                if (target.exists()) {
                    when (policy) {
                        ConflictPolicy.SKIP -> {
                            skipped++
                            return@forEachIndexed
                        }
                        ConflictPolicy.OVERWRITE -> {
                            // Replacing something is a deletion, so it goes
                            // through the trash and the journal like any other.
                            // An "undo" that cannot bring back what it replaced
                            // is not an undo.
                            val slot = trashStore.allocate(target)
                            val displaced = if (parkInTrash(target, slot)) {
                                AppliedChange(
                                    kind = OperationKind.TRASH,
                                    sourcePath = target.absolutePath,
                                    targetPath = slot.absolutePath,
                                    sizeBytes = sizeOf(slot),
                                    isDirectory = slot.isDirectory,
                                )
                            } else {
                                null
                            }
                            if (displaced != null) {
                                changes += displaced
                                onApplied(displaced)
                            } else if (!deleteRecursively(target)) {
                                failed++
                                issues += OperationIssue(
                                    target.absolutePath,
                                    "Could not replace existing item",
                                )
                                return@forEachIndexed
                            }
                        }
                        ConflictPolicy.RENAME -> target = uniqueTarget(destination, wantedName)
                    }
                }

                val before = bytesDone
                val ok = performTransfer(source, target, moveAfterwards) { delta ->
                    bytesDone += delta
                    onProgress(
                        OperationProgress(
                            current = index,
                            total = sources.size,
                            currentName = source.name,
                            bytesDone = bytesDone,
                            bytesTotal = totalBytes,
                        )
                    )
                }
                if (!ok) {
                    bytesDone = before + sizeOf(target)
                    failed++
                    issues += OperationIssue(source.absolutePath, "The system refused this file")
                    deleteRecursively(target)
                    return@forEachIndexed
                }

                if (target.isFile) {
                    bytesDone = before + target.length()
                }

                succeeded++
                val applied = AppliedChange(
                    kind = kind,
                    sourcePath = source.absolutePath,
                    targetPath = target.absolutePath,
                    sizeBytes = if (target.isFile) target.length() else 0L,
                    isDirectory = target.isDirectory,
                )
                changes += applied
                onApplied(applied)
            }
        } catch (cancelled: CancellationException) {
            onProgress(
                OperationProgress(
                    current = succeeded,
                    total = sources.size,
                    currentName = "",
                    bytesDone = bytesDone,
                    bytesTotal = totalBytes,
                )
            )
            return@withContext OperationReport(
                kind = kind,
                succeeded = succeeded,
                failed = failed,
                skipped = skipped,
                bytesMoved = bytesDone,
                cancelled = true,
                changes = changes,
                issues = issues,
            )
        }

        OperationReport(
            kind = kind,
            succeeded = succeeded,
            failed = failed,
            skipped = skipped,
            bytesMoved = bytesDone,
            changes = changes,
            issues = issues,
        )
    }

    /**
     * A rename is instant when both ends share a mount point; anything else
     * has to become a real copy followed by a delete.
     *
     * If the copy is interrupted — cancelled by the user, or the process killed
     * — the half-written destination is removed rather than left behind looking
     * like a real file.
     */
    private suspend fun performTransfer(
        source: File,
        target: File,
        moveAfterwards: Boolean,
        onBytes: (Long) -> Unit,
    ): Boolean {
        if (moveAfterwards && volumes.isSameVolume(source.absolutePath, target.absolutePath)) {
            target.parentFile?.mkdirs()
            if (source.renameTo(target)) return true
        }
        val copied = try {
            copyRecursively(source, target, onBytes)
        } catch (cancelled: CancellationException) {
            deleteRecursively(target)
            throw cancelled
        } catch (io: IOException) {
            deleteRecursively(target)
            false
        }
        if (copied && moveAfterwards) deleteRecursively(source)
        return copied
    }

    private suspend fun copyRecursively(
        source: File,
        target: File,
        onBytes: (Long) -> Unit,
    ): Boolean {
        coroutineContext.ensureActive()
        return when {
            source.isDirectory -> {
                if (!target.exists() && !target.mkdirs()) return false
                val children = source.listFiles() ?: emptyArray()
                children.all { copyRecursively(it, File(target, it.name), onBytes) }
            }
            source.isFile -> copyFile(source, target, onBytes)
            else -> false
        }
    }

    /**
     * Files are written to a sibling named `*.fileflow-part` and only renamed
     * into place once they are complete. A destination path therefore either
     * does not exist or is a whole file — never a truncated one — no matter how
     * the copy ends.
     */
    private suspend fun copyFile(
        source: File,
        target: File,
        onBytes: (Long) -> Unit,
    ): Boolean {
        val parent = target.parentFile ?: return false
        parent.mkdirs()
        val partial = File(parent, target.name + PARTIAL_SUFFIX)
        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(STREAM_BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        onBytes(read.toLong())
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            partial.setLastModified(source.lastModified())
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            true
        } catch (cancelled: CancellationException) {
            partial.delete()
            throw cancelled
        } catch (io: IOException) {
            partial.delete()
            false
        }
    }

    // ---------------------------------------------------------------- delete

    suspend fun moveToTrash(
        files: List<File>,
        onProgress: (OperationProgress) -> Unit = {},
        onApplied: suspend (AppliedChange) -> Unit = {},
    ): OperationReport = remove(
        files = files,
        permanent = false,
        onProgress = onProgress,
        onApplied = onApplied,
    )

    suspend fun deletePermanently(
        files: List<File>,
        onProgress: (OperationProgress) -> Unit = {},
        onApplied: suspend (AppliedChange) -> Unit = {},
    ): OperationReport = remove(
        files = files,
        permanent = true,
        onProgress = onProgress,
        onApplied = onApplied,
    )

    private suspend fun remove(
        files: List<File>,
        permanent: Boolean,
        onProgress: (OperationProgress) -> Unit,
        onApplied: suspend (AppliedChange) -> Unit,
    ): OperationReport = withContext(Dispatchers.IO) {
        val kind = if (permanent) OperationKind.DELETE else OperationKind.TRASH
        val issues = mutableListOf<OperationIssue>()
        val changes = mutableListOf<AppliedChange>()
        var succeeded = 0
        var failed = 0
        var bytes = 0L

        try {
            files.forEachIndexed { index, file ->
                coroutineContext.ensureActive()
                onProgress(
                    OperationProgress(
                        current = index,
                        total = files.size,
                        currentName = file.name,
                        bytesDone = bytes,
                        bytesTotal = 0L,
                    )
                )
                if (!file.exists()) {
                    failed++
                    issues += OperationIssue(file.absolutePath, "Already gone")
                    return@forEachIndexed
                }
                val size = sizeOf(file)
                val target = if (permanent) null else trashStore.allocate(file)
                val ok = when {
                    permanent -> deleteRecursively(file)
                    target != null -> parkInTrash(file, target)
                    else -> false
                }

                if (!ok) {
                    failed++
                    issues += OperationIssue(file.absolutePath, "The system refused this file")
                    return@forEachIndexed
                }

                bytes += size
                succeeded++
                val applied = AppliedChange(
                    kind = kind,
                    sourcePath = file.absolutePath,
                    targetPath = target?.absolutePath,
                    sizeBytes = size,
                    isDirectory = file.isDirectory,
                )
                changes += applied
                onApplied(applied)
            }
        } catch (cancelled: CancellationException) {
            return@withContext OperationReport(
                kind = kind,
                succeeded = succeeded,
                failed = failed,
                bytesMoved = bytes,
                cancelled = true,
                changes = changes,
                issues = issues,
            )
        }

        OperationReport(
            kind = kind,
            succeeded = succeeded,
            failed = failed,
            bytesMoved = bytes,
            changes = changes,
            issues = issues,
        )
    }

    /**
     * Moves an item into the trash. A rename is instant when both ends share a
     * volume; anything else is a real copy followed by a delete. Used for both
     * an explicit delete and for parking whatever an overwrite displaces.
     */
    private suspend fun parkInTrash(file: File, target: File): Boolean {
        target.parentFile?.mkdirs()
        if (volumes.isSameVolume(file.absolutePath, target.absolutePath) && file.renameTo(target)) {
            return true
        }
        return copyRecursively(file, target, {}).also { copied ->
            if (copied) deleteRecursively(file)
        }
    }

    /**
     * Restores a trashed item. Kept here rather than in the trash repository
     * because it is the same class of mutation as everything else, and the
     * undo journal has to record it identically.
     */
    suspend fun restoreFromTrash(trashPath: String, originalPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val source = File(trashPath)
            if (!source.exists()) {
                return@withContext Result.failure(IOException("The file is no longer in trash"))
            }
            val original = File(originalPath)
            original.parentFile?.mkdirs()
            val target = if (original.exists()) {
                uniqueTarget(original.parentFile ?: return@withContext Result.failure(
                    IOException("No parent folder")
                ), original.name)
            } else {
                original
            }
            val ok = if (volumes.isSameVolume(source.absolutePath, target.absolutePath) &&
                source.renameTo(target)
            ) {
                true
            } else {
                copyRecursively(source, target, {}).also { copied ->
                    if (copied) deleteRecursively(source)
                }
            }
            if (ok) Result.success(Unit)
            else Result.failure(IOException("Could not restore the file"))
        }

    /**
     * Puts a file back exactly where a journal entry says it came from. Undo
     * and restore both need "the same path, or as close as possible", which is
     * narrower than a general move.
     */
    suspend fun relocate(
        sourcePath: String,
        targetPath: String,
        policy: ConflictPolicy = ConflictPolicy.RENAME,
    ): Result<String> = withContext(Dispatchers.IO) {
        val source = File(sourcePath)
        if (!source.exists()) {
            return@withContext Result.failure(IOException("The file is no longer there"))
        }
        val target = File(targetPath)
        target.parentFile?.mkdirs()
        var finalTarget = target
        if (target.exists() && target.absolutePath != source.absolutePath) {
            when (policy) {
                ConflictPolicy.SKIP ->
                    return@withContext Result.failure(IOException("Something already occupies that name"))
                ConflictPolicy.OVERWRITE -> {
                    val slot = trashStore.allocate(target)
                    if (!parkInTrash(target, slot) && !deleteRecursively(target)) {
                        return@withContext Result.failure(
                            IOException("Could not replace the existing item")
                        )
                    }
                }
                ConflictPolicy.RENAME -> {
                    val parent = target.parentFile
                        ?: return@withContext Result.failure(IOException("No parent folder"))
                    finalTarget = uniqueTarget(parent, target.name)
                }
            }
        }
        val ok = performTransfer(source, finalTarget, moveAfterwards = true) {}
        if (ok) Result.success(finalTarget.absolutePath)
        else Result.failure(IOException("Could not move the file back"))
    }

    // --------------------------------------------------------------- helpers

    suspend fun deleteRecursively(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext true
        if (file.isDirectory) {
            val children = file.listFiles() ?: emptyArray()
            for (child in children) {
                if (!deleteRecursively(child)) return@withContext false
            }
        }
        file.delete()
    }

    fun sizeOf(file: File): Long {
        if (file.isFile) return file.length()
        if (!file.isDirectory) return 0L
        var total = 0L
        val children = file.listFiles() ?: return 0L
        for (child in children) {
            total += if (child.isDirectory) sizeOf(child) else child.length()
            if (total < 0) return Long.MAX_VALUE
        }
        return total
    }

    fun conflicts(sources: List<File>, destination: File): List<String> =
        sources.filter { File(destination, it.name).exists() }.map { it.name }

    fun uniqueTarget(directory: File, name: String): File =
        File(directory, CollisionResolver.unique(directory, name))

    /**
     * Removes `*.fileflow-part` leftovers. A partial file can only survive a
     * hard process kill, so this runs at startup and is cheap: it walks the
     * directories the app is about to show anyway.
     */
    suspend fun sweepPartials(roots: List<String>): Int = withContext(Dispatchers.IO) {
        var removed = 0
        val stack = ArrayDeque<File>()
        roots.map(::File).filter { it.isDirectory }.forEach { stack.addLast(it) }
        val visited = HashSet<String>()

        while (stack.isNotEmpty()) {
            val directory = stack.removeLast()
            val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: continue
            if (!visited.add(canonical)) continue
            val children = directory.listFiles() ?: continue
            for (child in children) {
                when {
                    child.isDirectory -> stack.addLast(child)
                    child.name.endsWith(PARTIAL_SUFFIX) -> if (child.delete()) removed++
                }
            }
        }
        removed
    }

    companion object {
        private const val STREAM_BUFFER = 128 * 1024
        const val PARTIAL_SUFFIX = ".fileflow-part"
    }
}
