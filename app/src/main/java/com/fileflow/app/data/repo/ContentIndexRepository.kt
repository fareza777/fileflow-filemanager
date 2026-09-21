package com.fileflow.app.data.repo

import com.fileflow.app.core.analysis.TextExtractor
import com.fileflow.app.core.analysis.TextVectors
import com.fileflow.app.core.storage.StorageAccess
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.data.db.ContentIndexDao
import com.fileflow.app.data.db.ContentIndexEntity
import com.fileflow.app.data.db.ContentVectorEntity
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.FileWalker
import com.fileflow.app.domain.WalkOptions
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** One hit, with the words around it. */
@androidx.compose.runtime.Immutable
data class ContentHit(
    val path: String,
    val name: String,
    val snippet: String,
    val score: Float,
    val fromVector: Boolean = false,
)

/**
 * The text index, and the two searches it makes possible.
 *
 * Name search is what the file index already does. This is the other question:
 * "which of my files talks about this?" — answered once by reading every
 * readable document into a table, and then twice: by the words in it, and by a
 * vector that stands in for what it is about.
 *
 * The index is bounded on purpose. Only formats that are text, only files under
 * a few megabytes, and only the first part of a large one: a search that takes
 * a minute to build is a search nobody uses twice.
 */
@Singleton
class ContentIndexRepository @Inject constructor(
    private val dao: ContentIndexDao,
    private val walker: FileWalker,
    private val volumes: VolumeRepository,
    private val storageAccess: StorageAccess,
) {

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    suspend fun vectorCount(): Int = withContext(Dispatchers.IO) { dao.vectorCount() }

    suspend fun indexedAt(): Long? = withContext(Dispatchers.IO) { dao.indexedAt() }

    /**
     * Reads every searchable document and writes it to the table.
     *
     * Rows are replaced rather than appended, so a file that was deleted stops
     * being found and a file that changed is re-read. Both tables are cleared
     * first: an index that answers from two different days is worse than one
     * that answers from none.
     */
    suspend fun rebuild(
        onProgress: (indexed: Int, currentName: String) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        dao.clear()
        dao.clearVectors()

        val entries = ArrayList<ContentIndexEntity>(BATCH)
        val vectors = ArrayList<ContentVectorEntity>(BATCH)
        var total = 0
        val now = System.currentTimeMillis()

        walker.walkFiles(
            volumes.roots(),
            WalkOptions(recursive = true, skipHidden = true),
        ).collect { candidate ->
            currentCoroutineContext().ensureActive()
            if (storageAccess.isRestrictedPath(candidate.path)) return@collect
            if (!TextExtractor.canExtract(File(candidate.path))) return@collect
            if (candidate.extension.lowercase() !in TextExtractor.EXTRACTABLE) return@collect

            val text = runCatching { TextExtractor.extract(File(candidate.path)) }.getOrNull()
                ?: return@collect
            if (text.isBlank()) return@collect

            entries += ContentIndexEntity(
                path = candidate.path,
                name = candidate.name,
                extension = candidate.extension,
                modified = candidate.lastModified,
                sizeBytes = candidate.sizeBytes,
                body = text,
                folded = text.lowercase(),
                indexedAt = now,
            )
            vectors += ContentVectorEntity(
                path = candidate.path,
                name = candidate.name,
                dimension = TextVectors.DIMENSION,
                vector = TextVectors.pack(TextVectors.embed(text)),
                indexedAt = now,
            )
            total++

            if (entries.size >= BATCH) {
                dao.upsert(entries.toList())
                dao.upsertVectors(vectors.toList())
                entries.clear()
                vectors.clear()
                onProgress(total, candidate.name)
                // The disk is the slowest part and the user is waiting; giving
                // it back between batches keeps the app responsive.
                delay(YIELD_MILLIS)
            }
        }

        if (entries.isNotEmpty()) {
            dao.upsert(entries.toList())
            dao.upsertVectors(vectors.toList())
        }
        onProgress(total, "")
        total
    }

    /**
     * Word search: every term has to be present, and the words around the hit
     * are returned so the result says why it matched.
     */
    suspend fun search(
        query: String,
        limit: Int = 200,
    ): List<ContentHit> = withContext(Dispatchers.IO) {
        val terms = tokenize(query)
        if (terms.isEmpty()) return@withContext emptyList()

        // The SQL takes four terms; more than that and the extra ones are
        // checked here, which is cheaper than a query per extra word.
        val padded = (terms + List(4) { "" }).take(4)
        val rows = dao.search(
            t1 = like(padded[0]),
            t2 = like(padded[1]),
            t3 = like(padded[2]),
            t4 = like(padded[3]),
            limit = limit,
        )
        rows.asSequence()
            .filter { row -> terms.all { row.folded.contains(it) } }
            .map { row ->
                ContentHit(
                    path = row.path,
                    name = row.name,
                    snippet = TextExtractor.snippet(row.body, terms),
                    score = 1f,
                )
            }
            .toList()
    }

    /**
     * Vector search: the documents closest in meaning to the query, which finds
     * the ones that talk about the same thing without using the same words.
     *
     * Brute force on purpose. A few thousand documents of a few hundred floats
     * is a handful of milliseconds, and an approximate index would trade that
     * for a structure that needs rebuilding on every write.
     */
    suspend fun searchSimilar(
        query: String,
        limit: Int = 60,
        minimumScore: Float = 0.12f,
    ): List<ContentHit> = withContext(Dispatchers.IO) {
        val needle = TextVectors.embed(query)
        var needleMagnitude = 0f
        needle.forEach { needleMagnitude += it * it }
        if (needleMagnitude <= 0f) return@withContext emptyList()

        val rows = dao.allVectors()
        val scored = rows.asSequence()
            .mapNotNull { row ->
                val score = TextVectors.cosine(needle, TextVectors.unpack(row.vector, row.dimension))
                if (score < minimumScore) null else Triple(row.path, row.name, score)
            }
            .sortedByDescending { triple -> triple.third }
            .take(limit)
            .toList()

        val terms = tokenize(query)
        scored.map { (path, name, score) ->
            val body = dao.entry(path)?.body.orEmpty()
            ContentHit(
                path = path,
                name = name,
                snippet = TextExtractor.snippet(body, terms),
                score = score,
                fromVector = true,
            )
        }
    }

    /** Keeps the index honest about a file the app itself changed. */
    suspend fun refreshPaths(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.forEach { path ->
            val file = File(path)
            if (!TextExtractor.canExtract(file)) {
                dao.remove(path)
                dao.removeVector(path)
                return@forEach
            }
            val text = runCatching { TextExtractor.extract(file) }.getOrNull()
            if (text.isNullOrBlank()) {
                dao.remove(path)
                dao.removeVector(path)
                return@forEach
            }
            val now = System.currentTimeMillis()
            dao.upsert(
                listOf(
                    ContentIndexEntity(
                        path = path,
                        name = file.name,
                        extension = file.name.substringAfterLast('.', "").lowercase(),
                        modified = file.lastModified(),
                        sizeBytes = file.length(),
                        body = text,
                        folded = text.lowercase(),
                        indexedAt = now,
                    )
                )
            )
            dao.upsertVectors(
                listOf(
                    ContentVectorEntity(
                        path = path,
                        name = file.name,
                        dimension = TextVectors.DIMENSION,
                        vector = TextVectors.pack(TextVectors.embed(text)),
                        indexedAt = now,
                    )
                )
            )
        }
    }

    suspend fun removePaths(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.forEach { path ->
            dao.remove(path)
            dao.removeVector(path)
        }
    }

    /** The most recently changed documents that were indexed. */
    suspend fun recent(limit: Int = 40): List<Candidate> = withContext(Dispatchers.IO) {
        dao.recent(limit).map { row ->
            Candidate(
                path = row.path,
                name = row.name,
                extension = row.extension,
                category = com.fileflow.app.core.util.MimeTypes.categoryOf(row.extension),
                sizeBytes = row.sizeBytes,
                lastModified = row.modified,
                isHidden = row.name.startsWith("."),
                isDirectory = false,
            )
        }
    }

    /** Terms as the index folds them: words of three characters or more. */
    private fun tokenize(query: String): List<String> = query.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }
        .distinct()
        .take(8)

    /** LIKE needs wildcards around the term and the % in the term escaped. */
    private fun like(term: String): String {
        if (term.isEmpty()) return ""
        val escaped = term.replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    private companion object {
        const val BATCH = 200
        const val YIELD_MILLIS = 30L
    }
}
