package com.fileflow.app.core.analysis

import kotlin.math.sqrt

/**
 * Local vector search without a model to run.
 *
 * A "semantic" search usually means an embedding model, and on a phone that
 * means shipping hundreds of megabytes and a runtime. What a model buys is a
 * fixed-length vector whose distance means "about the same thing" — and a
 * hashed bag of words gives a usable version of that for free: each term and
 * each term pair is hashed into one of a few hundred dimensions, the vector is
 * normalised, and the cosine between two of them rises when documents share
 * vocabulary. It will not know that a "car" is an "automobile". It will find the
 * document that talks about the same things in different words, which is the
 * part substring search cannot do at all.
 *
 * The dimensions are deliberately few: a query is compared against every
 * indexed document, and 192 floats per document is what keeps that a few
 * milliseconds on a phone instead of a few seconds.
 */
object TextVectors {

    const val DIMENSION = 192

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** A hashed unigram-and-bigram vector, L2 normalised so cosine is a dot. */
    fun embed(text: String): FloatArray {
        val vector = FloatArray(DIMENSION)
        val words = WORD.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toList()
        if (words.isEmpty()) return vector

        var previous: String? = null
        words.forEach { word ->
            add(vector, word, 1f)
            previous?.let { first -> add(vector, "$first $word", 0.6f) }
            previous = word
        }

        var norm = 0f
        vector.forEach { norm += it * it }
        norm = sqrt(norm)
        if (norm > 0f) {
            for (index in vector.indices) vector[index] /= norm
        }
        return vector
    }

    private fun add(vector: FloatArray, term: String, weight: Float) {
        val hash = term.hashCode()
        // Two positions per term, with opposite signs: collisions cancel instead
        // of adding up, which is what keeps the distance meaningful at this size.
        val first = (hash and 0x7FFFFFFF) % DIMENSION
        val second = ((hash shr 8) and 0x7FFFFFFF) % DIMENSION
        vector[first] += weight
        vector[second] -= weight
    }

    /** Cosine similarity of two normalised vectors, which is their dot product. */
    fun cosine(left: FloatArray, right: FloatArray): Float {
        if (left.size != right.size) return 0f
        var sum = 0f
        for (index in left.indices) sum += left[index] * right[index]
        return sum
    }

    fun pack(vector: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(vector.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun unpack(bytes: ByteArray, dimension: Int = DIMENSION): FloatArray {
        if (bytes.size < dimension * 4) return FloatArray(dimension)
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimension) { buffer.float }
    }

    /**
     * Words that appear in everything and so distinguish nothing. Deliberately
     * short and English-plus-Indonesian: a longer list would need a language
     * detector, and the cost of leaving a word in is a little noise.
     */
    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "have", "has", "was",
        "were", "are", "not", "but", "you", "your", "its", "dan", "yang",
        "untuk", "dengan", "dari", "pada", "adalah", "akan", "tidak", "ini", "itu",
    )
}
