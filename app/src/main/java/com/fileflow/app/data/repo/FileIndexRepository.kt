package com.fileflow.app.data.repo

import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.storage.StorageAccess
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.core.util.MimeTypes
import com.fileflow.app.data.db.FileIndexDao
import com.fileflow.app.data.db.IndexedFileEntity
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.FileWalker
import com.fileflow.app.domain.WalkOptions
import com.fileflow.app.domain.search.SearchFilters
import com.fileflow.app.domain.scan.StorageWalkGate
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * A persistent index of what is on the device.
 *
 * Searching used to mean walking the filesystem from scratch — ten to sixty
 * seconds on a full phone, and it had to happen again for the next search. With
 * an index the same query is a SQL scan of a few thousand rows, and the whole
 * class of "search across everything" features becomes cheap enough to build.
 */
@Singleton
class FileIndexRepository @Inject constructor(
    private val dao: FileIndexDao,
    private val walker: FileWalker,
    private val volumes: VolumeRepository,
    private val storageAccess: StorageAccess,
    private val gate: StorageWalkGate,
) {

    fun observeCount(): Flow<Int> = dao.observeCount()

    fun observeIndexedAt(): Flow<Long?> = dao.observeIndexedAt()

    /** When the index was last built, or null when it never has been. */
    suspend fun indexedAt(): Long? = withContext(Dispatchers.IO) {
        dao.observeIndexedAt().first()
    }

    suspend fun count(): Int = dao.count()

    /** An index older than this is refreshed before it is trusted. */
    suspend fun isFresh(maxAgeMillis: Long = FRESH_FOR): Boolean = withContext(Dispatchers.IO) {
        val at = dao.observeIndexedAt().first() ?: return@withContext false
        System.currentTimeMillis() - at <= maxAgeMillis && dao.hasAny()
    }

    suspend fun hasContent(): Boolean = withContext(Dispatchers.IO) { dao.hasAny() }

    /**
     * Rebuilds from scratch. Rows are replaced rather than appended so paths
     * that no longer exist disappear; the whole table is written in one
     * transaction per batch instead of one statement per file.
     */
    suspend fun rebuild(
        onProgress: (indexed: Int) -> Unit = {},
    ): Int = gate.run { rebuildLocked(onProgress) }

    /**
     * Rebuilds only if storage is idle. Used at startup, where waiting would
     * mean making the user wait: if the home screen is already walking, the
     * scheduled worker will build the index later instead of the two sweeps
     * running side by side.
     */
    suspend fun rebuildIfIdle(onProgress: (indexed: Int) -> Unit = {}): Int? =
        gate.tryRun { rebuildLocked(onProgress) }

    private suspend fun rebuildLocked(
        onProgress: (indexed: Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val batch = ArrayList<IndexedFileEntity>(BATCH_SIZE)
        var total = 0

        dao.clear()

        // Hidden directories are where the caches and thumbnails live; indexing
        // them doubled the build time to answer searches nobody makes, and the
        // live scanner never looked there either.
        walker.walkFiles(
            volumes.roots(),
            WalkOptions(recursive = true, skipHidden = true, includeDirectories = true),
        ).collect { candidate ->
            if (storageAccess.isRestrictedPath(candidate.path)) return@collect
            batch += candidate.toEntity(startedAt)
            if (batch.size >= BATCH_SIZE) {
                dao.upsertAll(batch.toList())
                total += batch.size
                batch.clear()
                onProgress(total)
                // Hand the disk back between batches: this runs while the user
                // is already using the app, and a build that monopolises IO is
                // felt as the whole phone going slow.
                delay(YIELD_MILLIS)
            }
        }

        if (batch.isNotEmpty()) {
            dao.upsertAll(batch.toList())
            total += batch.size
        }
        onProgress(total)
        total
    }

    /**
     * Searches the index. Falls back to null when the index is empty so the
     * caller can run a live scan instead of silently returning nothing.
     */
    suspend fun search(
        query: String,
        filters: SearchFilters,
        root: String? = null,
        deep: Boolean = true,
        limit: Int = SEARCH_LIMIT,
    ): List<Candidate>? = withContext(Dispatchers.IO) {
        if (!dao.hasAny()) return@withContext null

        val extensions = filters.extensions.map { MimeTypes.normalizeExtension(it) }
        val categoryExtensions = filters.categories
            .flatMap { MimeTypes.extensionsFor(it) }
            .distinct()

        // Scoping the search to a folder has to mean the same thing here as it
        // does in the live walk: nothing at all when no root is given, the
        // folder's own children when it is, and the whole subtree when deep.
        // Without this the index answered every query device-wide, so searching
        // from inside a folder returned files from across the phone.
        val scopedRoot = root?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        val hasRoot = if (scopedRoot == null) 0 else 1
        // LIKE needs the folder followed by a separator, or "/sdcard/Down" would
        // also match "/sdcard/Downloads/…". Wildcards in the path itself are
        // escaped so a folder named with a % stays literal.
        val rootPattern = scopedRoot?.let { "${escapeLike(it)}/%" } ?: ""

        val term = query.trim()
        val rows = if (term.isEmpty()) {
            dao.searchFiltersOnly(
                hasRoot = hasRoot,
                root = scopedRoot ?: "",
                deep = if (deep) 1 else 0,
                rootPattern = rootPattern,
                hasExtensions = if (extensions.isEmpty()) 0 else 1,
                extensions = extensions.ifEmpty { listOf("") },
                hasCategories = if (categoryExtensions.isEmpty()) 0 else 1,
                categoryExtensions = categoryExtensions.ifEmpty { listOf("") },
                minSize = filters.minBytes,
                maxSize = filters.maxBytes,
                modifiedAfter = filters.modifiedAfter,
                modifiedBefore = filters.modifiedBefore,
                limit = limit,
            )
        } else {
            dao.searchByName(
                pattern = "%${escapeLike(term)}%",
                hasRoot = hasRoot,
                root = scopedRoot ?: "",
                deep = if (deep) 1 else 0,
                rootPattern = rootPattern,
                hasExtensions = if (extensions.isEmpty()) 0 else 1,
                extensions = extensions.ifEmpty { listOf("") },
                hasCategories = if (categoryExtensions.isEmpty()) 0 else 1,
                categoryExtensions = categoryExtensions.ifEmpty { listOf("") },
                minSize = filters.minBytes,
                maxSize = filters.maxBytes,
                modifiedAfter = filters.modifiedAfter,
                modifiedBefore = filters.modifiedBefore,
                limit = limit,
            )
        }
        rows.map { it.toCandidate() }
    }

    suspend fun mostRecent(limit: Int = 30): List<Candidate> = withContext(Dispatchers.IO) {
        dao.mostRecent(limit).map { it.toCandidate() }
    }

    /**
     * The newest files, straight out of the index. This is the only source that
     * can answer "what changed on this device" for the formats the media index
     * never reported — a zip, a document, a code file.
     */
    suspend fun mostRecentFiles(limit: Int = 30): List<Candidate> = withContext(Dispatchers.IO) {
        dao.mostRecentFiles(limit).map { it.toCandidate() }
    }

    /** Keeps the index honest about what the app itself just changed. */
    suspend fun upsertPaths(paths: List<String>) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val rows = paths.mapNotNull { path ->
            val file = File(path)
            if (file.isFile) file.toEntity(now) else null
        }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    suspend fun removePaths(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.forEach { path ->
            dao.remove(path)
            dao.removeUnder(path)
        }
    }

    /**
     * A LIKE wildcard typed by the user is a literal character, not a pattern.
     */
    private fun escapeLike(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '%', '_', '\\' -> append('\\').append(ch)
                else -> append(ch)
            }
        }
    }

    private fun Candidate.toEntity(indexedAt: Long): IndexedFileEntity = IndexedFileEntity(
        path = path,
        parentPath = path.substringBeforeLast('/', ""),
        name = name,
        extension = extension,
        sizeBytes = sizeBytes,
        modified = lastModified,
        isDirectory = isDirectory,
        indexedAt = indexedAt,
    )

    private fun File.toEntity(indexedAt: Long): IndexedFileEntity {
        val extension = if (isDirectory) "" else MimeTypes.extensionOf(name)
        return IndexedFileEntity(
            path = absolutePath,
            parentPath = parent ?: "",
            name = name,
            extension = extension,
            sizeBytes = if (isFile) length() else 0L,
            modified = lastModified(),
            isDirectory = isDirectory,
            indexedAt = indexedAt,
        )
    }

    private fun IndexedFileEntity.toCandidate(): Candidate = Candidate(
        path = path,
        name = name,
        extension = extension,
        category = MimeTypes.categoryOf(extension),
        sizeBytes = sizeBytes,
        lastModified = modified,
        isHidden = name.startsWith("."),
        isDirectory = isDirectory,
    )

    companion object {
        private const val BATCH_SIZE = 500
        private const val SEARCH_LIMIT = 3000
        private const val YIELD_MILLIS = 40L
        const val FRESH_FOR = 6 * 60 * 60 * 1000L
    }
}
