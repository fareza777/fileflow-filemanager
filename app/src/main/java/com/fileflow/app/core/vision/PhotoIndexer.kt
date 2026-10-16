package com.fileflow.app.core.vision

import android.content.Context
import android.provider.MediaStore
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.core.util.MediaStoreItems
import com.fileflow.app.data.db.PhotoFingerprint
import com.fileflow.app.data.db.PhotoIndexDao
import com.fileflow.app.data.db.PhotoIndexEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class PhotoMatch { TEXT, NAME, THEME, RECENT }

/** A photo that matched, and why. */
@androidx.compose.runtime.Immutable
data class PhotoHit(
    val path: String,
    val name: String,
    val modified: Long,
    /** The words around the match, when the match was text. */
    val snippet: String?,
    /** The Indonesian theme names the photo matched, when it matched a theme. */
    val themes: List<String>,
    val labels: List<String>,
    val match: PhotoMatch = PhotoMatch.RECENT,
    val relevance: Int = 0,
)

/**
 * Reads photos once so they can be searched by what is in them.
 *
 * The pass is deliberately incremental and deliberate about it: every photo
 * already indexed is compared by modification time and size, and one that has
 * not changed is skipped without being opened. That is what makes the second
 * run seconds instead of an hour, and it is the difference between a feature
 * someone uses and a feature someone runs once.
 *
 * Text and labels come from two different models, and they answer two different
 * questions, so a photo is only asked about what is worth asking: a document
 * gets read, a landscape gets labelled, and both happen when neither is obvious
 * from the file name.
 */
@Singleton
class PhotoIndexer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PhotoIndexDao,
    private val text: PhotoText,
    private val themes: PhotoThemes,
    private val volumes: VolumeRepository,
) {

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    fun observeCount() = dao.observeCount()

    suspend fun indexedAt(): Long? = withContext(Dispatchers.IO) { dao.indexedAt() }

    suspend fun clear() = withContext(Dispatchers.IO) { dao.clear() }

    /**
     * Photos the phone knows about, newest first.
     *
     * Every one of them. The list used to stop at the newest two thousand, which
     * meant "search your photos" quietly answered for a slice of the library
     * and nothing else — the gap that reads as "only 210 showed up".
     */
    private suspend fun photos(): List<PhotoCandidate> = withContext(Dispatchers.IO) {
        val roots = volumes.volumes().map { it.rootPath }
        val found = ArrayList<PhotoCandidate>(512)
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                MediaStoreItems.PROJECTION,
                "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'image/%'",
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val item = MediaStoreItems.from(cursor, roots) ?: continue
                    if (!MediaStoreItems.isUserFile(item.path)) continue
                    if (!File(item.path).isFile) continue
                    found += PhotoCandidate(item.path, item.name, item.lastModified, item.sizeBytes)
                }
            }
        }
        found
    }

    /**
     * How many photos [index] would consider — what the whole library weighs
     * in at, so the screen can say "210 of 3,000" instead of leaving the user
     * to wonder whether 210 is all there is.
     */
    suspend fun knownCount(): Int = withContext(Dispatchers.IO) { photos().size }

    /**
     * Reads every photo that is new or changed — the entire library, oldest
     * leftovers included. Newest first, so an interrupted run still covers the
     * most recent photos before the tail, and re-running it continues where the
     * fingerprints say it stopped.
     */
    suspend fun index(
        includeLabels: Boolean = true,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val photos = photos()
        if (photos.isEmpty()) return@withContext 0

        val known = dao.fingerprints().associateBy { it.path }
        val pending = photos.filter { photo ->
            val previous = known[photo.path]
            previous == null || previous.modified != photo.modified || previous.sizeBytes != photo.sizeBytes
        }
        if (pending.isEmpty()) return@withContext 0

        var done = 0
        val batch = ArrayList<PhotoIndexEntity>(BATCH)
        pending.forEach { photo ->
            currentCoroutineContext().ensureActive()
            onProgress(done, pending.size, photo.name)
            val file = File(photo.path)

            val recognised = text.read(file).orEmpty()
            val labels = if (includeLabels) themes.labels(file) else emptyList()

            batch += PhotoIndexEntity(
                path = photo.path,
                name = photo.name,
                modified = photo.modified,
                sizeBytes = photo.sizeBytes,
                text = recognised.take(MAX_TEXT),
                folded = recognised.take(MAX_TEXT).lowercase(),
                labels = labels.joinToString(","),
                indexedAt = System.currentTimeMillis(),
            )
            done++

            if (batch.size >= BATCH) {
                dao.upsert(batch.toList())
                batch.clear()
                // Both models run on the device; giving the disk and the CPU
                // back between batches is what keeps the app usable while this
                // runs behind a progress bar.
                delay(YIELD_MILLIS)
            }
        }
        if (batch.isNotEmpty()) dao.upsert(batch.toList())
        onProgress(done, pending.size, "")
        done
    }

    // ------------------------------------------------------------- searching

    /**
     * Photos whose contents match [query].
     *
     * Text first and labels second, because the two are not equally reliable:
     * a photo with "transfer berhasil" printed in it is a match beyond argument,
     * while a label match is "photos that look like this". Both are returned,
     * labelled for what they are, and the text matches come first.
     */
    suspend fun search(query: String, limit: Int = 300): List<PhotoHit> = withContext(Dispatchers.IO) {
        val needle = query.trim()
        if (needle.isEmpty()) return@withContext emptyList()

        val hits = LinkedHashMap<String, PhotoHit>()

        val terms = needle.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .distinct()
            .take(3)
        if (terms.isNotEmpty()) {
            val padded = (terms + List(3) { "" }).take(3)
            dao.searchText(
                t1 = like(padded[0]),
                t2 = like(padded[1]),
                t3 = like(padded[2]),
                limit = limit,
            ).forEach { row ->
                hits[row.path] = PhotoHit(
                    path = row.path,
                    name = row.name,
                    modified = row.modified,
                    snippet = snippet(row.text, terms),
                    themes = ThemeVocabulary.themesFor(labelsOf(row)),
                    labels = ThemeVocabulary.display(labelsOf(row).take(4)),
                    match = if (terms.all { it in row.folded }) PhotoMatch.TEXT else PhotoMatch.NAME,
                )
            }
        }

        val themeLabels = ThemeVocabulary.labelsFor(needle)
        if (themeLabels.isNotEmpty()) {
            val padded = (themeLabels.map { ",$it," } + List(12) { "" }).take(12)
            dao.searchLabels(
                l1 = like(padded[0]), l2 = like(padded[1]), l3 = like(padded[2]),
                l4 = like(padded[3]), l5 = like(padded[4]), l6 = like(padded[5]),
                l7 = like(padded[6]), l8 = like(padded[7]), l9 = like(padded[8]),
                l10 = like(padded[9]), l11 = like(padded[10]), l12 = like(padded[11]),
                // Apply the limit after verifying evidence, not before: a newer
                // crowd of selfies must not hide older community-work photos.
                limit = dao.count(),
            ).filter { ThemeVocabulary.matchesTheme(needle, labelsOf(it)) }
                .sortedByDescending { ThemeVocabulary.matchStrength(needle, labelsOf(it)) }
                .take(limit).forEach { row ->
                hits.getOrPut(row.path) {
                    val labels = labelsOf(row)
                    PhotoHit(
                        path = row.path,
                        name = row.name,
                        modified = row.modified,
                        snippet = null,
                        themes = ThemeVocabulary.themesFor(labels),
                        labels = ThemeVocabulary.display(labels.take(4)),
                        match = PhotoMatch.THEME,
                        relevance = ThemeVocabulary.matchStrength(needle, labels),
                    )
                }
            }
        }

        hits.values.sortedWith(
            compareBy<PhotoHit> { it.match == PhotoMatch.THEME }
                .thenByDescending { it.relevance }
                .thenByDescending { it.modified },
        ).take(limit)
    }

    /** Everything indexed, newest first — what the screen shows before a query. */
    suspend fun recent(limit: Int = 200): List<PhotoHit> = withContext(Dispatchers.IO) {
        dao.recent(limit).map { row ->
            val labels = labelsOf(row)
            PhotoHit(
                path = row.path,
                name = row.name,
                modified = row.modified,
                snippet = row.text.take(SNIPPET_LENGTH).takeIf { it.isNotBlank() },
                themes = ThemeVocabulary.themesFor(labels),
                labels = ThemeVocabulary.display(labels.take(3)),
            )
        }
    }

    private fun labelsOf(row: PhotoIndexEntity): List<String> =
        if (row.labels.isBlank()) emptyList() else row.labels.split(',')

    private fun like(term: String): String {
        if (term.isEmpty()) return ""
        return "%" + term.replace("%", "\\%").replace("_", "\\_") + "%"
    }

    /** The line of text around the first hit, which is why the photo matched. */
    private fun snippet(text: String, terms: List<String>): String? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        val hit = terms.mapNotNull { term -> lower.indexOf(term).takeIf { it >= 0 } }.minOrNull()
            ?: return text.take(SNIPPET_LENGTH)
        val start = (hit - 40).coerceAtLeast(0)
        val end = (hit + 60).coerceAtMost(text.length)
        return buildString {
            if (start > 0) append("… ")
            append(text.substring(start, end).replace('\n', ' ').trim())
            if (end < text.length) append(" …")
        }
    }

    private data class PhotoCandidate(
        val path: String,
        val name: String,
        val modified: Long,
        val sizeBytes: Long,
    )

    private companion object {
        const val BATCH = 12
        const val YIELD_MILLIS = 40L
        const val MAX_TEXT = 8 * 1024
        const val SNIPPET_LENGTH = 120
    }
}
