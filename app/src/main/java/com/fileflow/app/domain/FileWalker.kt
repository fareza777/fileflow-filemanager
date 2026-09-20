package com.fileflow.app.domain

import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.util.MimeTypes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.yield

/**
 * A file reduced to what every scanner, rule and heuristic actually needs.
 * Keeping the walkers away from [File] means the rule engine can be tested
 * without a filesystem.
 */
@androidx.compose.runtime.Immutable
data class Candidate(
    val path: String,
    val name: String,
    val extension: String,
    val category: FileCategory,
    val sizeBytes: Long,
    val lastModified: Long,
    val isHidden: Boolean,
    val isDirectory: Boolean,
) {
    fun toFile(): File = File(path)
}

object Candidates {
    fun of(file: File): Candidate {
        val name = file.name
        val isDirectory = file.isDirectory
        val extension = if (isDirectory) "" else MimeTypes.extensionOf(name)
        return Candidate(
            path = file.absolutePath,
            name = name,
            extension = extension,
            category = if (isDirectory) FileCategory.OTHER else MimeTypes.categoryOf(extension),
            sizeBytes = if (isDirectory) 0L else file.length(),
            lastModified = file.lastModified(),
            isHidden = name.startsWith("."),
            isDirectory = isDirectory,
        )
    }

    fun of(file: File, size: Long, extension: String): Candidate = Candidate(
        path = file.absolutePath,
        name = file.name,
        extension = extension,
        category = MimeTypes.categoryOf(extension),
        sizeBytes = size,
        lastModified = file.lastModified(),
        isHidden = file.name.startsWith("."),
        isDirectory = false,
    )
}

/**
 * Scanners work in [Candidate]; lists work in [FileItem]. One converter keeps
 * the boundary in a single place.
 */
fun Candidate.toFileItem(childCount: Int = 0): FileItem = FileItem(
    path = path,
    name = name,
    isDirectory = isDirectory,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    extension = extension,
    mimeType = if (isDirectory) "resource/folder" else MimeTypes.mimeFor(extension),
    category = category,
    isHidden = isHidden,
    childCount = childCount,
)

data class WalkOptions(
    val recursive: Boolean = true,
    val skipHidden: Boolean = true,
    val includeDirectories: Boolean = false,
    /** The trash is part of the app's own bookkeeping, never a scan target. */
    val skipTrash: Boolean = true,
    val maxDepth: Int = 40,
    /**
     * Refuses a directory and everything inside it.
     *
     * Android/data and Android/obb are unreadable to every app on Android 11+,
     * but they sit under a perfectly ordinary-looking parent, so they are not
     * caught by the hidden-name rule. Dropping their files one by one after
     * listing them meant paying for the entire subtree — these two folders can
     * hold tens of thousands of files — to throw every result away. Pruning
     * here means they are never listed at all.
     */
    val pruneDirectory: ((File) -> Boolean)? = null,
)

/**
 * Depth-first traversal that yields as it goes, so a preview can start showing
 * matches while the rest of the device is still being read.
 */
@javax.inject.Singleton
class FileWalker @javax.inject.Inject constructor(
    private val storageAccess: com.fileflow.app.core.storage.StorageAccess,
) {

    /**
     * [onEmptyDirectory] is reported from the listing the traversal has to do
     * anyway. Finding empty folders used to mean a whole second pass over the
     * device, when the answer was already in hand the moment a directory came
     * back with nothing in it.
     */
    fun walkFiles(
        roots: List<String>,
        options: WalkOptions = WalkOptions(),
        onEmptyDirectory: ((Candidate) -> Unit)? = null,
    ): Flow<Candidate> =
        flow {
            val visited = HashSet<String>()
            var sinceYield = 0
            for (root in roots) {
                val rootFile = File(root)
                if (!rootFile.isDirectory) continue
                if (shouldPrune(rootFile, options)) continue
                val stack = ArrayDeque<Pair<File, Int>>()
                stack.addLast(rootFile to 0)
                while (stack.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    if (++sinceYield >= YIELD_EVERY) {
                        sinceYield = 0
                        // A directory walk is a long, unbroken run of syscalls.
                        // Yielding hands the IO dispatcher back regularly so the
                        // frame loop and the rest of the app keep getting a turn
                        // instead of waiting out the whole traversal.
                        yield()
                    }
                    val (directory, depth) = stack.removeLast()
                    val canonical = runCatching { directory.canonicalPath }.getOrNull()
                    if (canonical != null && !visited.add(canonical)) continue
                    if (isSkippedDirectory(directory, options)) continue
                    if (shouldPrune(directory, options)) continue

                    val children = directory.listFiles() ?: continue
                    if (onEmptyDirectory != null && children.isEmpty()) {
                        onEmptyDirectory(Candidates.of(directory))
                    }
                    for (child in children) {
                        val hidden = child.name.startsWith(".")
                        if (options.skipHidden && hidden) continue
                        if (child.isDirectory) {
                            // The trash stays out of every scan even when the
                            // user has chosen to show hidden folders.
                            if (options.skipTrash && child.name == TRASH_ROOT_MARKER) continue
                            if (shouldPrune(child, options)) continue
                            if (options.includeDirectories) emit(Candidates.of(child))
                            if (options.recursive && depth < options.maxDepth) {
                                stack.addLast(child to depth + 1)
                            }
                        } else if (child.isFile) {
                            emit(Candidates.of(child))
                        }
                    }
                }
            }
        }.flowOn(Dispatchers.IO)

    fun walkDirectories(roots: List<String>): Flow<Candidate> = flow {
        val options = WalkOptions()
        for (root in roots) {
            val rootFile = File(root)
            if (!rootFile.isDirectory) continue
            val stack = ArrayDeque<File>()
            stack.addLast(rootFile)
            while (stack.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val directory = stack.removeLast()
                if (isSkippedDirectory(directory, options)) continue
                if (shouldPrune(directory, options)) continue
                val children = directory.listFiles() ?: continue
                for (child in children) {
                    if (!child.isDirectory) continue
                    if (child.name.startsWith(".")) continue
                    if (shouldPrune(child, options)) continue
                    emit(Candidates.of(child))
                    stack.addLast(child)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Android/data and Android/obb are unreadable on Android 11+, but they sit
     * under a normal-looking parent so the hidden-name rule does not catch
     * them. Left alone, every scan of the device would list their entire
     * subtree and then discard it file by file. Deciding here means the
     * traversal never opens them — and because the check lives in the walker
     * rather than in each caller, no scanner can forget to do it.
     */
    private fun shouldPrune(directory: File, options: WalkOptions): Boolean {
        options.pruneDirectory?.let { return it(directory) }
        return storageAccess.isRestrictedPath(directory.absolutePath)
    }

    private fun isSkippedDirectory(directory: File, options: WalkOptions): Boolean {
        val name = directory.name
        if (name == TRASH_ROOT_MARKER) return true
        if (options.skipHidden && name.startsWith(".")) return true
        return false
    }

    companion object {
        const val TRASH_ROOT_MARKER = ".fileflow"
        private const val YIELD_EVERY = 120
    }
}
