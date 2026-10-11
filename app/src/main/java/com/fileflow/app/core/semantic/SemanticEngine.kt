package com.fileflow.app.core.semantic

import android.content.Context
import android.util.Log
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
 * The model is loaded once and kept: it is hundreds of megabytes and the load
 * is the expensive part. It carries *both* towers in one file, so one
 * interpreter serves pictures and phrases; which input and which output is
 * which is read from the model rather than from its file name.
 */
@Singleton
class SemanticEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: SemanticModels,
    private val vectors: PhotoVectorDao,
) {

    private val lock = Mutex()
    private var model: ClipEncoder? = null
    private var tokenizer: ClipTokenizer? = null

    /**
     * Set once the accelerator has refused to *run* the model.
     *
     * Opening the model and running it are different failures. A delegate can
     * accept a 400 MB graph when the interpreter is built and then fail on the
     * first picture, which no construction-time fallback ever sees — the model
     * opens, the pass runs, and every photo comes back empty. So the first run
     * failure with the accelerator in place closes it and reopens on the CPU,
     * and this flag stops the second attempt from trying the accelerator again.
     */
    private var cpuOnly = false

    /**
     * Why the last load failed, in the model's own words.
     *
     * "The model did not load" is not an answer: it says nothing about whether a
     * file is missing, the interpreter refused it, its inputs are not what this
     * code expects, or the vocabulary would not parse. Whichever it is, the
     * message names it — including the tensor shapes, because that is what a
     * wrong assumption about the model looks like.
     */
    var loadFailure: String? = null
        private set

    /** Identifies the model in stored vectors, so another model cannot reuse them. */
    val modelId: String = "mobileclip2-s2"

    fun installed(): Boolean = models.ready()

    fun sizeOnDisk(): Long = models.sizeOnDisk()

    suspend fun count(): Int = withContext(Dispatchers.IO) { vectors.count() }

    /**
     * How many indexed photos still have no vector: what is left to read by
     * meaning. The screen uses this to decide whether a pass is worth starting,
     * so a finished library does not spin up a service to do nothing.
     */
    suspend fun pending(): Int = withContext(Dispatchers.IO) {
        val known = vectors.embeddedPaths().toHashSet()
        vectors.indexedPhotos().count { it.path !in known && File(it.path).isFile }
    }

    fun observeCount() = vectors.observeCount()

    suspend fun ready(): Boolean = withContext(Dispatchers.IO) { load() != null }

    /**
     * Opens both towers and the tokenizer, once.
     *
     * @return null when anything is missing or does not look like the tower it
     * claims to be, so a half-downloaded model is refused instead of producing
     * vectors nobody can trust.
     */
    private suspend fun load(): Pair<ClipEncoder, ClipTokenizer>? = lock.withLock {
        model?.let { encoder ->
            tokenizer?.let { return@withLock Pair(encoder, it) }
        }

        if (!models.ready()) {
            val missing = SemanticAsset.entries.filterNot { models.present(it) }
            loadFailure = "Missing from the download: " +
                missing.joinToString(", ") { "${it.label} (${it.fileName})" }
            return@withLock null
        }

        withContext(Dispatchers.IO) {
            // One file, both towers. The old code opened "first" as the image
            // tower and "last" as the text tower; they are the same dual-tower
            // model twice over, so both classified as IMAGE and the load was
            // refused with "the model's inputs are not what this app expects".
            val opened = ClipEncoder.open(models.file(SemanticAsset.MODEL), cpuOnly = cpuOnly)
            if (opened == null) {
                val megabytes = models.file(SemanticAsset.MODEL).length() / (1024 * 1024)
                loadFailure = "The model file could not be opened as a TFLite model " +
                    "($megabytes MB on disk). Deleting it and downloading it again is the fix."
                return@withContext null
            }

            val parsed = runCatching {
                ClipTokenizer(
                    vocab = parseVocab(models.file(SemanticAsset.VOCAB)),
                    mergeLines = models.file(SemanticAsset.MERGES).readLines(),
                )
            }.getOrElse { error ->
                runCatching { opened.close() }
                loadFailure = "The vocabulary could not be read: ${error.message}"
                return@withContext null
            }

            model = opened
            tokenizer = parsed
            loadFailure = null
            Pair(opened, parsed)
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
        val (encoder, _) = load() ?: return@withContext 0

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

            val vector = encoder.embedImage(File(photo.path))
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
            } else if (encoder.runFailure != null) {
                // A photo that cannot be read is one file to skip. A run that
                // threw is the model itself, and continuing would turn a broken
                // model into a progress bar that reaches the end and stores
                // nothing — which is exactly what happened: every photo failed,
                // the count stayed at zero, and no screen said why.
                val reason = encoder.runFailure
                if (reopenOnCpu(encoder)) return@withContext embed(limit, onProgress)
                throw IllegalStateException(
                    "The model could not run on this phone: $reason",
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
            val prompts = ClipPrompts.promptsFor(needle)
            if (prompts.isEmpty()) return@withContext emptyList()

            // Two attempts, the accelerator first. Searching and indexing are
            // the same model and the same failure, so they need the same
            // second chance — when only indexing had one, the library indexed
            // fine and every search silently came back empty.
            repeat(2) {
                val (encoder, tokens) = load() ?: return@withContext emptyList()

                // Named for what it holds, not for the table it resembles: a
                // local called `vectors` shadows the DAO of the same name and
                // silently turns `vectors.all()` into the stdlib `all { }`,
                // which fails to compile at a line that reads correctly.
                val embeddings = prompts.mapNotNull { prompt ->
                    encoder.embedText(tokens.encode(prompt))
                }
                // A text tower that answers every prompt with nothing looks
                // exactly like a phrase the model has never met: an empty
                // result, no error. Saying so is the difference between a
                // wrong query and a broken tower.
                if (embeddings.isEmpty()) {
                    Log.w(TAG, "text tower returned no vector for ${prompts.size} prompt(s): " +
                        "${encoder.runFailure ?: "no exception raised"}")
                    if (reopenOnCpu(encoder)) return@repeat
                    return@withContext emptyList()
                }

                val queryVector = averageNormalised(embeddings) ?: return@withContext emptyList()
                val all = vectors.all()
                val scored = scoreAll(queryVector, all)
                // Nothing comparable and nothing clearing the bar are two
                // different faults that the old line reported identically as
                // "aboveFloor=0". Saying which one it is is the whole point of
                // measuring.
                if (scored.isEmpty()) {
                    val seen = all
                        .distinctBy { "${it.model}/${it.dimension}" }
                        .joinToString(", ") { "${it.model}/${it.dimension}" }
                    Log.w(TAG, "search: no stored vector is comparable — wanted " +
                        "model=$modelId dim=${queryVector.size}; stored=${all.size} " +
                        "as [$seen]")
                    return@withContext emptyList()
                }
                val hits = scored.filter { it.score >= minimum }.take(limit)
                // The floor under a result is a guess, and a guess nobody
                // measures eventually becomes a ceiling nobody can see past.
                // rawTop is the best score *before* the floor, so a bar set
                // above every real score shows up as a number instead of
                // looking like a library with nothing to offer.
                val best = scored.first()
                Log.i(TAG, "search '$needle': prompts=${prompts.size} embedded=${embeddings.size} " +
                    "dim=${queryVector.size} stored=${all.size} rows=${scored.size} " +
                    "rawTop=${best.score} aboveFloor=${hits.size} " +
                    "top=${hits.firstOrNull()?.score ?: Float.NaN} floor=$minimum " +
                    "best='${best.name}'")
                return@withContext hits
            }
            emptyList()
        }

    /**
     * Every stored vector scored against [queryVector], closest first.
     *
     * The floor and the limit are applied by the caller on purpose: applied
     * here, the best score that *was* available is discarded along with the
     * rejects, and "zero results" can no longer be told apart from "every
     * result sat below a floor set too high".
     */
    private fun scoreAll(
        queryVector: FloatArray,
        all: List<PhotoVectorEntity>,
    ): List<SemanticHit> = all
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
        .sortedByDescending { it.score }
        .toList()

    /** True when the query has a hand-written prompt rather than a raw passthrough. */
    fun isCurated(query: String): Boolean = ClipPrompts.isCurated(query)

    suspend fun clear() = withContext(Dispatchers.IO) { vectors.clear() }

    fun close() {
        runCatching { model?.close() }
        model = null
        tokenizer = null
    }

    /**
     * Drops the open model so the next call opens it again.
     *
     * The tokenizer is kept: the accelerator is the suspect here, and the
     * vocabulary is a megabyte of text that was parsed without complaint.
     */
    private fun reopen() {
        runCatching { model?.close() }
        model = null
    }

    /**
     * Gives the model one more try on the CPU, if that is what has not been
     * tried yet.
     *
     * Opening a model and running it are different failures. A delegate can
     * accept a 400 MB graph when the interpreter is built and then refuse the
     * first input — `ANEURALNETWORKS_BAD_DATA` — and no construction-time
     * fallback ever sees that, because construction succeeded. On this phone
     * that is exactly what happens: the model opens, the accelerator takes it,
     * and every run fails.
     *
     * Both paths need this, not just one. Indexing photos and searching them
     * are the same model and the same refusal; when only indexing knew how to
     * retry, the library indexed cleanly and every search came back empty with
     * nothing said anywhere.
     *
     * @return true when the caller should try again. False when the CPU has
     * already been tried — in which case the failure is the model's, not the
     * accelerator's, and another attempt would only repeat it.
     */
    private fun reopenOnCpu(encoder: ClipEncoder): Boolean {
        if (!encoder.usesDelegate || cpuOnly) return false
        Log.w(TAG, "the accelerator refused to run the model; reopening on the CPU")
        cpuOnly = true
        reopen()
        return true
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

        /** Where a search explains itself, in enough detail to be argued with. */
        private const val TAG = "SemanticEngine"

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

        /**
         * CLIP's vocabulary file: a JSON object of token to id.
         *
         * On the companion, and internal, because this is the step that fails
         * silently: a file that parses to zero entries does not throw, it just
         * sends every word to `<unk>`, and a tokenizer that answers every
         * question with the same string hands the text tower a model input it
         * cannot tell apart from any other. The engine needs the map; the test
         * needs to know the map is not empty.
         */
        internal fun parseVocab(file: File): Map<String, Int> {
            val vocab = HashMap<String, Int>(64_000)
            ENTRIES.findAll(file.readText()).forEach { match ->
                vocab[match.groupValues[1]] = match.groupValues[2].toIntOrNull() ?: return@forEach
            }
            return vocab
        }

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
