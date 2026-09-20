package com.fileflow.app.domain.scan

import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.core.util.MimeTypes
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.FileWalker
import com.fileflow.app.domain.WalkOptions
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

data class CategoryUsage(
    val category: FileCategory,
    val bytes: Long,
    val fileCount: Int,
)

/**
 * One row in the analyzer's drill-down: a child of whichever folder is being
 * looked at, carrying its **recursive** size.
 *
 * The recursive number is the whole point. The old version showed a folder's
 * total bytes but listed the folder's own children, so opening "Android/media"
 * (7.5 GB of WhatsApp media three levels down) showed a listing that added up
 * to almost nothing — it looked empty and the number looked like a lie.
 */
@androidx.compose.runtime.Immutable
data class UsageEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val bytes: Long,
    val fileCount: Int,
    /** False when the folder appeared after the scan and has no measured size. */
    val measured: Boolean = true,
    /** Bytes of the filter category only, when a category filter is active. */
    val filtered: Boolean = false,
) {
    val isFile: Boolean get() = !isDirectory
}

data class Breadcrumb(val name: String, val path: String)

data class AnalysisResult(
    val root: String = "",
    val totalBytes: Long = 0,
    val totalFiles: Int = 0,
    val categories: List<CategoryUsage> = emptyList(),
    /** Recursive size of every directory the walk touched. */
    val dirBytes: Map<String, Long> = emptyMap(),
    val dirFiles: Map<String, Int> = emptyMap(),
    /** Per top-level folder, bytes broken down by category. */
    val dirCategoryBytes: Map<String, LongArray> = emptyMap(),
    val largestFiles: List<Candidate> = emptyList(),
    val volumeTotal: Long = 0,
    val volumeFree: Long = 0,
) {
    val isEmpty: Boolean get() = totalFiles == 0

    fun categoryBytes(path: String, category: FileCategory): Long =
        dirCategoryBytes[path]?.let { row ->
            if (category.ordinal < row.size) row[category.ordinal] else 0L
        } ?: 0L

    /** Recursive size of a directory, or the file's own size. */
    fun bytesOf(path: String, isDirectory: Boolean, ownSize: Long): Long =
        if (isDirectory) dirBytes[path] ?: 0L else ownSize
}

/**
 * One pass over the storage feeds the totals, the category breakdown, the
 * per-folder breakdown and the largest files.
 *
 * Sizes are rolled up to **every** ancestor as the walk streams past, which
 * costs one map write per level per file and buys a drill-down that can answer
 * any folder in constant time. The alternative — re-walking a subtree when the
 * user opens it — would re-read the slowest thing on the device on every tap.
 */
@Singleton
class StorageAnalyzer @Inject constructor(
    private val walker: FileWalker,
    private val volumes: VolumeRepository,
) {

    companion object {
        private const val PROGRESS_EVERY = 250
        private const val LARGEST_KEPT = 200

        /**
         * How long a finished analysis stays usable.
         *
         * Walking the whole device is the most expensive thing the app does, and
         * re-opening the analyzer used to pay for it again every single time.
         * Within this window the previous result is reused, so the second visit
         * is instant; the refresh button always forces a fresh walk.
         */
        private const val CACHE_MILLIS = 3 * 60 * 1000L
    }

    private var cached: AnalysisResult? = null
    private var cachedAt = 0L

    /** True when [analyze] would answer from the previous walk. */
    fun hasFreshResult(): Boolean =
        cached != null && System.currentTimeMillis() - cachedAt < CACHE_MILLIS

    fun invalidate() {
        cached = null
        cachedAt = 0L
    }

    suspend fun analyze(
        roots: List<String> = volumes.roots(),
        force: Boolean = false,
        onProgress: (filesSeen: Int) -> Unit = {},
        onPartial: (AnalysisResult) -> Unit = {},
        isActive: () -> Boolean = { true },
    ): AnalysisResult {
        val previous = cached
        if (!force && previous != null && System.currentTimeMillis() - cachedAt < CACHE_MILLIS) {
            onProgress(previous.totalFiles)
            return previous
        }
        return walk(roots, onProgress, onPartial, isActive).also { result ->
            cached = result
            cachedAt = System.currentTimeMillis()
        }
    }

    private suspend fun walk(
        roots: List<String>,
        onProgress: (filesSeen: Int) -> Unit,
        onPartial: (AnalysisResult) -> Unit,
        isActive: () -> Boolean,
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val rootPrefixes = roots.map { it.trimEnd('/') }
        val primary = rootPrefixes.firstOrNull() ?: ""
        val volume = volumes.volumes().firstOrNull { it.isPrimary }

        val dirBytes = HashMap<String, Long>(4096)
        val dirFiles = HashMap<String, Int>(4096)
        val categoryBytes = HashMap<FileCategory, Long>()
        val categoryCount = HashMap<FileCategory, Int>()
        val dirCategoryBytes = HashMap<String, LongArray>()
        val largest = ArrayList<Candidate>(LARGEST_KEPT + 1)
        var largestFloor = 0L
        var totalBytes = 0L
        var totalFiles = 0

        walker.walkFiles(roots, WalkOptions(recursive = true, skipHidden = true))
            .collect { candidate ->
                val size = candidate.sizeBytes
                totalBytes += size
                totalFiles++

                categoryBytes[candidate.category] =
                    (categoryBytes[candidate.category] ?: 0L) + size
                categoryCount[candidate.category] =
                    (categoryCount[candidate.category] ?: 0) + 1

                rollUp(candidate, size, rootPrefixes, dirBytes, dirFiles, dirCategoryBytes)

                if (size > largestFloor) {
                    largest += candidate
                    if (largest.size > LARGEST_KEPT) {
                        largest.sortByDescending { it.sizeBytes }
                        largest.subList(LARGEST_KEPT, largest.size).clear()
                        largestFloor = largest.last().sizeBytes
                    }
                }

                if (totalFiles % PROGRESS_EVERY == 0 && isActive()) {
                    onProgress(totalFiles)
                    // A live breakdown beats a spinner: the bars fill in as the
                    // walk runs instead of the screen sitting empty for a minute.
                    onPartial(
                        snapshot(
                            root = primary,
                            totalBytes = totalBytes,
                            totalFiles = totalFiles,
                            categoryBytes = categoryBytes,
                            categoryCount = categoryCount,
                            volume = volume,
                        )
                    )
                }
            }

        onProgress(totalFiles)

        snapshot(
            root = primary,
            totalBytes = totalBytes,
            totalFiles = totalFiles,
            categoryBytes = categoryBytes,
            categoryCount = categoryCount,
            volume = volume,
        ).copy(
            dirBytes = dirBytes,
            dirFiles = dirFiles,
            dirCategoryBytes = dirCategoryBytes,
            largestFiles = largest.sortedByDescending { it.sizeBytes },
        )
    }

    /**
     * Children of [path], each with its recursive size.
     *
     * The listing is a single directory read, so it always reflects the disk
     * right now; the expensive part — how big each child really is — comes from
     * the map the walk already built.
     */
    suspend fun entriesFor(
        path: String,
        result: AnalysisResult,
        category: FileCategory? = null,
    ): List<UsageEntry> = withContext(Dispatchers.IO) {
        val directory = File(path)
        val children = directory.listFiles() ?: return@withContext emptyList()

        children.asSequence()
            .filter { !it.name.startsWith(".") }
            .filter { it.name != FileWalker.TRASH_ROOT_MARKER }
            .mapNotNull { child ->
                val isDir = child.isDirectory
                val ownSize = if (isDir) 0L else child.length()
                val name = child.name
                val childCategory = if (isDir) {
                    null
                } else {
                    MimeTypes.categoryOf(MimeTypes.extensionOf(name))
                }

                if (category == null) {
                    UsageEntry(
                        path = child.absolutePath,
                        name = name,
                        isDirectory = isDir,
                        bytes = result.bytesOf(child.absolutePath, isDir, ownSize),
                        fileCount = if (isDir) result.dirFiles[child.absolutePath] ?: 0 else 1,
                        measured = !isDir || result.dirBytes.containsKey(child.absolutePath),
                    )
                } else {
                    // With a filter on, a folder is judged and shown by how much
                    // of *that* category it holds. Keeping folders that contain
                    // none of it — at their full size — is what made the
                    // filtered list look like it was ignoring the filter.
                    val categoryBytes = if (isDir) {
                        result.categoryBytes(child.absolutePath, category)
                    } else {
                        if (childCategory == category) ownSize else 0L
                    }
                    if (categoryBytes <= 0L) return@mapNotNull null
                    UsageEntry(
                        path = child.absolutePath,
                        name = name,
                        isDirectory = isDir,
                        bytes = categoryBytes,
                        // A per-category count would need a second map the size of
                        // the tree; the byte figure is the number that matters
                        // here, and the row says which category it is.
                        fileCount = 0,
                        measured = true,
                        filtered = true,
                    )
                }
            }
            .sortedWith(
                compareByDescending<UsageEntry> { it.bytes }
                    .thenByDescending { it.isDirectory },
            )
            .take(200)
            .toList()
    }

    suspend fun breadcrumbFor(path: String, root: String): List<Breadcrumb> =
        withContext(Dispatchers.IO) {
            if (path.isBlank() || path == root) return@withContext emptyList()
            val relative = path.removePrefix(root).trimStart('/')
            if (relative.isBlank()) return@withContext emptyList()
            val crumbs = mutableListOf<Breadcrumb>()
            var accumulated = root
            relative.split('/').forEach { part ->
                accumulated = "$accumulated/$part"
                crumbs += Breadcrumb(part, accumulated)
            }
            crumbs
        }

    /**
     * Every ancestor of a file gets its size, which is what makes drill-down
     * O(1) — for the folder tree and for the per-category breakdown alike, so
     * the category filter stays honest however deep you go.
     */
    private fun rollUp(
        candidate: Candidate,
        size: Long,
        roots: List<String>,
        dirBytes: HashMap<String, Long>,
        dirFiles: HashMap<String, Int>,
        dirCategoryBytes: HashMap<String, LongArray>,
    ) {
        val path = candidate.path
        val root = roots.firstOrNull { path.startsWith("$it/") } ?: return
        val ordinal = candidate.category.ordinal

        var end = path.lastIndexOf('/')
        while (end > root.length) {
            val directory = path.substring(0, end)
            dirBytes[directory] = (dirBytes[directory] ?: 0L) + size
            dirFiles[directory] = (dirFiles[directory] ?: 0) + 1
            dirCategoryBytes.getOrPut(directory) { LongArray(FileCategory.entries.size) }[ordinal] += size
            end = path.lastIndexOf('/', end - 1)
        }
        dirBytes[root] = (dirBytes[root] ?: 0L) + size
        dirFiles[root] = (dirFiles[root] ?: 0) + 1
        dirCategoryBytes.getOrPut(root) { LongArray(FileCategory.entries.size) }[ordinal] += size
    }

    private fun snapshot(
        root: String,
        totalBytes: Long,
        totalFiles: Int,
        categoryBytes: Map<FileCategory, Long>,
        categoryCount: Map<FileCategory, Int>,
        volume: com.fileflow.app.core.model.VolumeInfo?,
    ): AnalysisResult = AnalysisResult(
        root = root,
        totalBytes = totalBytes,
        totalFiles = totalFiles,
        categories = categoryBytes.entries
            .map { CategoryUsage(it.key, it.value, categoryCount[it.key] ?: 0) }
            .sortedByDescending { it.bytes },
        volumeTotal = volume?.totalBytes ?: 0L,
        volumeFree = volume?.freeBytes ?: 0L,
    )
}
