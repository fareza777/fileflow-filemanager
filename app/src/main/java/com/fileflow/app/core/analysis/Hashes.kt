package com.fileflow.app.core.analysis

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Fast, sampled fingerprints.
 *
 * Two different questions, two different answers:
 *
 * - [fastHash] answers "could these two files be the same?" without reading
 *   them. It samples the head, the middle and the tail plus the size, which is
 *   imohash's idea: a 300 MB file is distinguished from its neighbours in
 *   microseconds, and the only thing it can get wrong is a difference hidden
 *   between the samples — which is exactly what the full hash is for.
 * - [fullHash] reads everything, so it can only be run over the handful of
 *   files the fast pass left standing.
 */
object FastHash {

    /** Size plus three sampled windows, hashed together. */
    fun fastHash(file: File, sampleBytes: Int = DEFAULT_SAMPLE): String {
        val length = file.length()
        if (length <= 0L) return emptyHash()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(length.toString().toByteArray())

        val sample = minOf(sampleBytes.toLong(), length / 3 + 1).toInt().coerceAtLeast(1)
        RandomAccessFile(file, "r").use { access ->
            val buffer = ByteArray(sample)
            // Head.
            access.seek(0)
            access.readFully(buffer)
            digest.update(buffer)
            // Middle.
            access.seek(length / 2)
            val middle = access.read(buffer)
            if (middle > 0) digest.update(buffer, 0, middle)
            // Tail.
            access.seek((length - sample).coerceAtLeast(0L))
            val tail = access.read(buffer)
            if (tail > 0) digest.update(buffer, 0, tail)
        }
        return digest.digest().toHex()
    }

    /** Every byte, for the files that survived the fast pass. */
    fun fullHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun emptyHash(): String = "0".repeat(64)

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        this@toHex.forEach { byte -> append("%02x".format(byte)) }
    }

    /** Enough of a sample to catch a header rewrite, small enough to be free. */
    const val DEFAULT_SAMPLE = 16 * 1024
}

/**
 * A fuzzy hash, in the spirit of TLSH.
 *
 * Local similarity hashing answers the question a cryptographic hash cannot:
 * "is this the same document with a paragraph changed" rather than "is this
 * byte-identical". A sliding window over the bytes feeds a table of 128 buckets
 * of 5-bit counts, the counts are quantised against the file's own quartiles,
 * and the digest carries the file's length so two files of very different sizes
 * can never be called similar.
 *
 * It is deliberately *not* byte-compatible with the reference TLSH
 * implementation — the bucket table is this project's own, and matching their
 * table would mean shipping their generator and their licence. The property
 * that matters is the same: similar files score a small distance, unrelated
 * files score a large one.
 */
object FuzzyHash {

    private const val BUCKETS = 128
    private const val WINDOW = 5

    private val TABLE = IntArray(256) { index ->
        var value = index * 0x9E3779B1.toInt()
        value = value xor (value ushr 13)
        value *= 0x85EBCA6B.toInt()
        value = value xor (value ushr 16)
        value
    }

    /** @return a 40-byte digest: 4 bytes of length, 36 bytes of buckets. */
    fun digest(file: File, header: ByteArray? = null): ByteArray {
        val length = file.length()
        val counts = IntArray(BUCKETS)
        var windowValue = 0
        var seen = 0L

        fun consume(byte: Int) {
            windowValue = ((windowValue shl 1) or TABLE[byte and 0xFF]) and 0x7FFFFFFF
            if (seen >= WINDOW) {
                // The low bits pick the bucket; a spread of them so the counts
                // do not all land in the first few.
                val bucket = ((windowValue ushr 3) xor (windowValue ushr 11)) and (BUCKETS - 1)
                counts[bucket]++
            }
            seen++
        }

        val head = header ?: ByteArray(0)
        head.forEach { consume(it.toInt()) }
        RandomAccessFile(file, "r").use { access ->
            access.seek(head.size.toLong())
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = access.read(buffer)
                if (read <= 0) break
                for (index in 0 until read) consume(buffer[index].toInt())
            }
        }

        // Quartiles over the non-zero buckets: a file of plain text and a file
        // of noise with the same byte counts still hash differently.
        val nonZero = counts.filter { it > 0 }.sorted()
        val q1 = nonZero.getOrElse(nonZero.size / 4) { 0 }
        val q2 = nonZero.getOrElse(nonZero.size / 2) { 0 }
        val q3 = nonZero.getOrElse(nonZero.size * 3 / 4) { 0 }

        val digest = ByteArray(4 + (BUCKETS * 5 + 7) / 8)
        digest[0] = (length ushr 24).toByte()
        digest[1] = (length ushr 16).toByte()
        digest[2] = (length ushr 8).toByte()
        digest[3] = length.toByte()

        var bit = 0
        counts.forEach { count ->
            val code = when {
                count == 0 -> 0
                count <= q1 -> 1
                count <= q2 -> 2
                count <= q3 -> 3
                else -> 4
            }
            // Five bits per bucket, packed low-to-high.
            repeat(5) { position ->
                val value = (code shr position) and 1
                if (value == 1) {
                    val byteIndex = 4 + bit / 8
                    digest[byteIndex] = (digest[byteIndex].toInt() or (1 shl (bit % 8))).toByte()
                }
                bit++
            }
        }
        return digest
    }

    /**
     * A distance between 0 and 128: the sum of the per-bucket differences, plus
     * a penalty for a length difference, which is what stops a two-line file
     * from matching a two-hour video.
     */
    fun distance(left: ByteArray, right: ByteArray): Int {
        if (left.size != right.size || left.size < 8) return BUCKETS
        val leftLength = lengthOf(left)
        val rightLength = lengthOf(right)
        val lengthPenalty = lengthPenalty(leftLength, rightLength)

        var score = 0
        var bit = 0
        repeat(BUCKETS) {
            val a = bits(left, bit)
            val b = bits(right, bit)
            score += kotlin.math.abs(a - b)
            bit += 5
        }
        return score + lengthPenalty
    }

    private fun bits(digest: ByteArray, startBit: Int): Int {
        var value = 0
        repeat(5) { position ->
            val bit = startBit + position
            val byteIndex = 4 + bit / 8
            if (byteIndex < digest.size) {
                val set = (digest[byteIndex].toInt() ushr (bit % 8)) and 1
                value = value or (set shl position)
            }
        }
        return value
    }

    private fun lengthOf(digest: ByteArray): Long =
        ((digest[0].toLong() and 0xFF) shl 24) or
            ((digest[1].toLong() and 0xFF) shl 16) or
            ((digest[2].toLong() and 0xFF) shl 8) or
            (digest[3].toLong() and 0xFF)

    private fun lengthPenalty(left: Long, right: Long): Int {
        if (left == 0L || right == 0L) return BUCKETS
        val ratio = if (left > right) left.toDouble() / right else right.toDouble() / left
        return when {
            ratio <= 1.1 -> 0
            ratio <= 1.5 -> 10
            ratio <= 2.0 -> 30
            ratio <= 4.0 -> 60
            else -> BUCKETS
        }
    }

    /** Where "similar" starts; below it the two files share most of their shape. */
    const val SIMILAR_THRESHOLD = 32
}

/**
 * Content-defined chunking, the FastCDC idea.
 *
 * Fixed-size chunks break the moment a byte is inserted: every later chunk
 * changes, and a sync transfers the whole file. A rolling hash that cuts where
 * the content says so — after a minimum, before a maximum, and on average in
 * between — keeps every chunk after the edit identical, so a sync moves one
 * chunk instead of a file.
 */
object Chunker {

    private val GEAR = IntArray(256) { index -> nudge(index) }

    private fun nudge(seed: Int): Int {
        var value = seed * 0x9E3779B1.toInt() + 0x7F4A7C15
        value = value xor (value ushr 15)
        value *= 0x2545F491
        return value xor (value ushr 13)
    }

    data class Chunk(val offset: Long, val length: Int, val hash: String)

    suspend fun split(
        file: File,
        minSize: Int = MIN,
        averageSize: Int = AVERAGE,
        maxSize: Int = MAX,
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        if (!file.isFile || file.length() == 0L) return chunks

        val digest = MessageDigest.getInstance("SHA-256")
        val mask = maskFor(averageSize)
        val buffer = ByteArray(256 * 1024)
        var position = 0L
        var chunkStart = 0L
        var fingerprint = 0

        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                for (index in 0 until read) {
                    val byte = buffer[index].toInt() and 0xFF
                    digest.update(byte.toByte())
                    fingerprint = ((fingerprint shl 1) + GEAR[byte])
                    position++
                    val length = position - chunkStart
                    // Cut after the minimum when the rolling hash says so, and
                    // always at the maximum: a long run of identical bytes has
                    // no natural boundary and must not become one chunk.
                    if (length >= maxSize || (length >= minSize && (fingerprint and mask) == 0)) {
                        chunks += Chunk(chunkStart, length.toInt(), digest.digest().toHex())
                        chunkStart = position
                        digest.reset()
                    }
                }
            }
        }
        if (position > chunkStart) {
            chunks += Chunk(chunkStart, (position - chunkStart).toInt(), digest.digest().toHex())
        }
        return chunks
    }

    /**
     * The cut mask, over the *high* bits of the rolling hash.
     *
     * It matters which end of the register is tested. The hash is built as
     * `(hash shl 1) + GEAR[byte]`, so every byte pushes the previous ones one
     * place up: the low bits are decided almost entirely by the most recent
     * byte, and testing them asks "was the last byte one of these few values"
     * instead of "is this a boundary". The result is not an average-sized
     * chunk — it is a chunk several times too large, which is the difference
     * between a sync moving ten kilobytes and moving a third of the file.
     *
     * The high bits are the mixed ones, so that is where the test goes.
     */
    private fun maskFor(averageSize: Int): Int {
        var bits = 0
        var size = 1
        while (size < averageSize && bits < MASK_BITS) {
            size = size shl 1
            bits++
        }
        // Kept below the sign bit, so the arithmetic stays a shift and not a
        // surprise.
        return ((1 shl bits) - 1) shl (MASK_BITS - bits)
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        this@toHex.forEach { byte -> append("%02x".format(byte)) }
    }

    const val MIN = 2 * 1024
    const val AVERAGE = 8 * 1024
    const val MAX = 64 * 1024

    /** Bits of the 32-bit hash the cut mask draws from, sign bit excluded. */
    private const val MASK_BITS = 30
}

/**
 * A Merkle fingerprint of a folder.
 *
 * Each file contributes its content hash; each folder contributes the hash of
 * its sorted children. Comparing two folders then costs one hash per folder
 * instead of a walk, and a differing subtree is found by descending instead of
 * by re-reading everything — which is what makes a sync on a phone affordable.
 */
object MerkleFingerprint {

    data class Node(
        val name: String,
        val hash: String,
        val sizeBytes: Long,
        val children: List<Node> = emptyList(),
    ) {
        val isDirectory: Boolean get() = children.isNotEmpty() || sizeBytes == 0L
    }

    suspend fun of(directory: File): Node? {
        if (!directory.isDirectory) return null
        return walk(directory, directory.name)
    }

    private fun walk(file: File, name: String): Node {
        if (file.isFile) {
            return Node(name = name, hash = FastHash.fastHash(file), sizeBytes = file.length())
        }
        val children = runCatching { file.listFiles()?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { !it.name.startsWith('.') }
            .sortedBy { it.name.lowercase() }

        val nodes = children.map { child -> walk(child, child.name) }
        val digest = MessageDigest.getInstance("SHA-256")
        nodes.forEach { node ->
            digest.update(node.name.toByteArray())
            digest.update(node.hash.toByteArray())
            digest.update(node.sizeBytes.toString().toByteArray())
        }
        return Node(
            name = name,
            hash = digest.digest().toHex(),
            sizeBytes = nodes.sumOf { it.sizeBytes },
            children = nodes,
        )
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        this@toHex.forEach { byte -> append("%02x".format(byte)) }
    }
}
