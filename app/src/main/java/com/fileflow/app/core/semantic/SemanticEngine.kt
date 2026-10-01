package com.fileflow.app.core.semantic

import android.content.Context
import com.fileflow.app.data.db.PhotoVectorDao
import com.fileflow.app.data.db.PhotoVectorEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** One photo that matched a phrase, with how closely. */
@androidx.compose.runtime.Immutable
data class SemanticHit(
    val path: String,
    val name: String,
    val modified: Long,
    /** Cosine similarity, 0 to 1. Higher is closer in meaning. */
    val score: Float,
)

/** Why the tier cannot run, in words the screen can show. */
sealed interface SemanticStatus {
    data object NotDownloaded : SemanticStatus
    data class Downloading(val asset: String, val done: Long, val total: Long) : SemanticStatus
    data object Loading : SemanticStatus
    data class Ready(val photos: Int, val bytes: Long) : SemanticStatus
    data class Failed(val reason: String) : SemanticStatus
}

/**
 * Photo search by meaning, using the model the user chose to download.
 *
 * Everything the cheap tier cannot do lives here. A photo of people clearing a
 * ditch has no text in it and its labels say "Person, Crowd, Field" — which
 * cannot know that it is the kerja bakti. MobileCLIP puts the *phrase* in the
 * same space as the picture, so "kerja bakti" reaches it.
 *
 * The two towers are loaded once and kept: they are hundreds of megabytes and
 * the load is the expensive part. Their roles are decided by their input
 * tensors rather than their file names, because the repository's names (`first`
 * and `last`) do not say which is which and swapping them yields vectors that
 * are the right length and meaningless.
 */
@Singleton
class SemanticEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: SemanticModels,
    private val vectors: PhotoVectorDao,
) {

    private val lock = Mutex()
    private var imageTower: ClipEncoder? = null
    private var textTower: ClipEncoder? = null
    private var tokenizer: ClipTokenizer? = null

    /** Identifies the model in stored vectors, so another model cannot reuse them. */
    val modelId: String = "mobileclip2-s2"

    fun installed(): Boolean = models.ready()

    fun sizeOnDisk(): Long = models.sizeOnDisk()

    suspend fun count(): Int = withContext(Dispatchers.IO) { vectors.count() }

    fun observeCount() = vectors.observeCount()

    suspend fun ready(): Boolean = withContext(Dispatchers.IO) { load() != null }

    /**
     * Opens both towers and the tokenizer, once.
     *
     * @return null when anything is missing or does not look like the tower it
     * claims to be, so a half-downloaded model is refused instead of producing
     * vectors nobody can trust.
     */
    private suspend fun load(): Triple<ClipEncoder, ClipEncoder, ClipTokenizer>? = lock.withLock {
        imageTower?.let { image ->
            textTower?.let { text ->
                tokenizer?.let { return@withLock Triple(image, text, it) }
            }
        }
        if (!models.ready()) return@withLock null

        withContext(Dispatchers.IO) {
            val a = ClipEncoder.open(models.file(SemanticAsset.IMAGE_TOWER)) ?: return@withContext null
            val b = ClipEncoder.open(models.file(SemanticAsset.TEXT_TOWER))
                ?: run {
                    a.close()
                    return@withContext null
                }

            val image = when {
                a.info.role == TowerRole.IMAGE -> a
                b.info.role == TowerRole.IMAGE -> b
                else -> null
            }
            val text = when {
                a.info.role == TowerRole.TEXT -> a
                b.info.role == TowerRole.TEXT -> b
                else -> null
            }
            // Both files describing the same tower, or neither describing the
            // one it must be, is a model that cannot search — say so rather than
            // produce noise.
            if (image == null || text == null || image === text) {
                runCatching { a.close() }
                runCatching { b.close() }
                return@withContext null
            }

            val parsed = runCatching {
                ClipTokenizer(
                    vocab = parseVocab(models.file(SemanticAsset.VOCAB)),
                    mergeLines = models.file(SemanticAsset.MERGES).readLines(),
                )
            }.getOrNull() ?: run {
                runCatching { a.close() }
                runCatching { b.close() }
                return@withContext null
            }

            imageTower = image
            textTower = text
            tokenizer = parsed
            Triple(image, text, parsed)
        }
    }

    /**
     * Reads photos that have no vector yet.
     *
     * Only photos already in the text index are considered: that pass has
     * already found the pictures and recorded their fingerprints, so this one
     * can be about the model rather than about storage.
     */
    suspend fun embed(
        limit: Int = 500,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val (image, _, _) = load() ?: return@withContext 0

        val known = vectors.embeddedPaths().toHashSet()
        val photos = vectors.indexedPhotos()
            .filter { it.path !in known && File(it.path).isFile }
            .sortedByDescending { it.modified }
            .take(limit)
        if (photos.isEmpty()) return@withContext 0

        var done = 0
        val batch = ArrayList<PhotoVectorEntity>(BATCH)
        photos.forEach { photo ->
            currentCoroutineContext().ensureActive()
            onProgress(done, photos.size, photo.path.substringAfterLast('/'))

            val vector = image.embedImage(File(photo.path))
            if (vector != null) {
                batch += PhotoVectorEntity(
                    path = photo.path,
                    name = photo.path.substringAfterLast('/'),
                    modified = photo.modified,
                    dimension = vector.size,
                    vector = pack(vector),
                    model = modelId,
                    indexedAt = System.currentTimeMillis(),
                )
            }
            done++

            if (batch.size >= BATCH) {
                vectors.upsert(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) vectors.upsert(batch.toList())
        onProgress(done, photos.size, "")
        done
    }

    /**
     * Photos whose *meaning* is closest to [query].
     *
     * The query is not embedded as typed. It is turned into several English
     * prompts, each is embedded, and the vectors are averaged — because the
     * model was trained on English captions and sentence-shaped text, and a
     * bare Indonesian phrase is neither. Averaging several phrasings of the same
     * question lands the query vector nearer the middle of the region those
     * photos occupy, which is the standard prompt-ensembling result and costs a
     * few milliseconds of text-tower time per search.
     */
    suspend fun search(query: String, limit: Int = 60, minimum: Float = MINIMUM_SCORE): List<SemanticHit> =
        withContext(Dispatchers.IO) {
            val needle = query.trim()
            if (needle.isEmpty()) return@withContext emptyList()

            val (_, text, tokens) = load() ?: return@withContext emptyList()
            val prompts = ClipPrompts.promptsFor(needle)
            if (prompts.isEmpty()) return@withContext emptyList()

            val vectors = prompts.mapNotNull { prompt ->
                text.embedText(tokens.encode(prompt))
            }
            val queryVector = averageNormalised(vectors) ?: return@withContext emptyList()

            vectorsIndex(queryVector, limit, minimum)
        }

    private suspend fun vectorsIndex(
        queryVector: FloatArray,
        limit: Int,
        minimum: Float,
    ): List<SemanticHit> = vectors.all()
        .asSequence()
        .filter { it.model == modelId && it.dimension == queryVector.size }
        .map { row ->
            SemanticHit(
                path = row.path,
                name = row.name,
                modified = row.modified,
                score = dot(queryVector, unpack(row.vector, row.dimension)),
            )
        }
        .filter { it.score >= minimum }
        .sortedByDescending { it.score }
        .take(limit)
        .toList()

    /** True when the query has a hand-written prompt rather than a raw passthrough. */
    fun isCurated(query: String): Boolean = ClipPrompts.isCurated(query)

    suspend fun clear() = withContext(Dispatchers.IO) { vectors.clear() }

    fun close() {
        runCatching { imageTower?.close() }
        runCatching { textTower?.close() }
        imageTower = null
        textTower = null
        tokenizer = null
    }

    // ------------------------------------------------------------- internals

    /** Both vectors are unit length, so cosine similarity is their dot product. */
    private fun dot(left: FloatArray, right: FloatArray): Float {
        if (left.size != right.size) return 0f
        var sum = 0f
        for (index in left.indices) sum += left[index] * right[index]
        return sum
    }

    private fun pack(vector: FloatArray): ByteArray =
        ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { vector.forEach { putFloat(it) } }
            .array()

    private fun unpack(bytes: ByteArray, dimension: Int): FloatArray {
        if (bytes.size < dimension * 4) return FloatArray(dimension)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimension) { buffer.float }
    }

    /** CLIP's vocabulary file: a JSON object of token to id. */
    private fun parseVocab(file: File): Map<String, Int> {
        val text = file.readText()
        val vocab = HashMap<String, Int>(64_000)
        ENTRIES.findAll(text).forEach { match ->
            vocab[match.groupValues[1]] = match.groupValues[2].toIntOrNull() ?: return@forEach
        }
        return vocab
    }

    /**
     * The mean of several unit vectors, re-normalised.
     *
     * In the companion object so it can be tested without an engine, a device or
     * a model — and because it has one edge that matters: prompts whose vectors
     * cancel out average to nothing, and dividing by that length would put a NaN
     * into every comparison. Every photo would then score NaN, and NaN
     * comparisons are false, so the search would return an empty list rather
     * than an error. Returning null instead makes that case the caller's.
     */
    internal companion object {
        const val BATCH = 8

        /**
         * The floor under a result, and an honest guess rather than a
         * measurement: CLIP similarities for a good match sit in a fairly narrow
         * band that depends on the model and the library, and this number wants
         * tuning against real photos on a real phone. It is deliberately low —
         * a wrong-but-labelled result the user can dismiss is better than a
         * correct one hidden by an arbitrary line.
         */
        const val MINIMUM_SCORE = 0.15f

        /**
         * Matches `"token": 1234` in CLIP's `vocab.json`, including the escaped
         * characters some tokens contain.
         */
        private val ENTRIES = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*(\\d+)")

        fun averageNormalised(vectors: List<FloatArray>): FloatArray? {
            if (vectors.isEmpty()) return null
            val size = vectors.first().size
            if (size == 0 || vectors.any { it.size != size }) return null

            val mean = FloatArray(size)
            vectors.forEach { vector ->
                for (index in 0 until size) mean[index] += vector[index]
            }
            var sum = 0f
            mean.forEach { sum += it * it }
            val length = sqrt(sum)
            if (length <= 1e-6f) return null
            for (index in mean.indices) mean[index] /= length
            return mean
        }
    }
}
