package com.fileflow.app.domain.recovery

import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Reed-Solomon erasure coding, the Backblaze construction.
 *
 * The idea is insurance rather than backup: a file is cut into `dataShards` and
 * `parityShards` more are computed from it, and *any* `dataShards` of the total
 * rebuild the original. Lose a USB stick, a card, or a folder to a bad sector
 * and the file still comes back — which is the one failure mode that copying a
 * file somewhere else does not protect against.
 *
 * The arithmetic is the standard Vandermonde matrix over GF(256) with the AES
 * polynomial: rows of `i^j` are turned into a systematic matrix so the data
 * shards are stored as they are, and recovery inverts the submatrix of the
 * survivors. Backblaze's JavaReedSolomon does the same thing with the same
 * maths; this is an independent implementation of it.
 */
class ReedSolomon(val dataShards: Int, val parityShards: Int) {

    init {
        require(dataShards > 0) { "Need at least one data shard" }
        require(parityShards > 0) { "Need at least one parity shard" }
        require(dataShards + parityShards <= 256) { "At most 256 shards in total" }
    }

    val totalShards: Int get() = dataShards + parityShards

    private val matrix: Array<ByteArray> = buildMatrix()

    /**
     * A Vandermonde matrix over the total shard count, made systematic by
     * multiplying by the inverse of its top square block. The top `dataShards`
     * rows then form the identity, which is what makes the data shards
     * storable verbatim.
     */
    private fun buildMatrix(): Array<ByteArray> {
        val rows = Array(totalShards) { row ->
            ByteArray(dataShards) { column -> GF.pow(row * column) }
        }
        val top = Array(dataShards) { rows[it].copyOf() }
        val inverse = GF.invert(top)
        return Array(totalShards) { row -> GF.multiply(inverse, rows[row]) }
    }

    /** Fills the parity shards from the data shards. Sizes must all match. */
    fun encode(shards: Array<ByteArray>) {
        require(shards.size == totalShards)
        val size = shards[0].size
        for (parity in 0 until parityShards) {
            val row = matrix[dataShards + parity]
            val target = shards[dataShards + parity]
            target.fill(0)
            for (data in 0 until dataShards) {
                val factor = row[data].toInt() and 0xFF
                if (factor == 0) continue
                val source = shards[data]
                for (index in 0 until size) {
                    target[index] = (target[index].toInt() xor GF.mul(factor, source[index].toInt() and 0xFF)).toByte()
                }
            }
        }
    }

    /**
     * Rebuilds every missing shard in place. [present] says which shards the
     * caller actually has; at least `dataShards` of them must be true.
     */
    fun decode(shards: Array<ByteArray>, present: BooleanArray) {
        require(shards.size == totalShards && present.size == totalShards)
        val available = present.count { it }
        if (available == totalShards) return
        check(available >= dataShards) {
            "Need at least $dataShards shards to rebuild; only $available available"
        }

        // The first `dataShards` survivors, in shard order.
        val usedIndices = present.indices.filter { present[it] }.take(dataShards)
        val subMatrix = Array(dataShards) { row -> matrix[usedIndices[row]].copyOf() }
        val inverse = GF.invert(subMatrix)
        val size = shards[usedIndices.first()].size
        val decoded = Array(dataShards) { ByteArray(size) }

        for (column in 0 until dataShards) {
            decoded[column].fill(0)
            for (row in 0 until dataShards) {
                val factor = inverse[column][row].toInt() and 0xFF
                if (factor == 0) continue
                val source = shards[usedIndices[row]]
                val target = decoded[column]
                for (index in 0 until size) {
                    target[index] = (target[index].toInt() xor GF.mul(factor, source[index].toInt() and 0xFF)).toByte()
                }
            }
        }

        for (index in 0 until dataShards) {
            if (!present[index]) {
                System.arraycopy(decoded[index], 0, shards[index], 0, size)
                present[index] = true
            }
        }
        // Parity shards are cheaper to recompute than to invert.
        encode(shards)
        for (index in present.indices) present[index] = true
    }
}

/** Galois field arithmetic over GF(256), polynomial 0x11d. */
internal object GF {

    private val table = IntArray(512)
    private val log = IntArray(256)

    init {
        var value = 1
        for (index in 0 until 255) {
            table[index] = value
            log[value] = index
            value = value shl 1
            if (value and 0x100 != 0) value = value xor 0x11D
        }
        for (index in 255 until 512) table[index] = table[index - 255]
    }

    fun mul(left: Int, right: Int): Int {
        if (left == 0 || right == 0) return 0
        return table[log[left] + log[right]]
    }

    fun div(left: Int, right: Int): Int {
        require(right != 0) { "Division by zero in GF(256)" }
        if (left == 0) return 0
        return table[(log[left] - log[right] + 255) % 255]
    }

    /** `value ^ power` in the field; used for the Vandermonde rows. */
    fun pow(power: Int): Byte = table[((power % 255) + 255) % 255].toByte()

    fun multiply(matrix: Array<ByteArray>, vector: ByteArray): ByteArray {
        val result = ByteArray(matrix.size)
        for (row in matrix.indices) {
            var sum = 0
            for (column in vector.indices) {
                sum = sum xor mul(matrix[row][column].toInt() and 0xFF, vector[column].toInt() and 0xFF)
            }
            result[row] = sum.toByte()
        }
        return result
    }

    fun multiply(left: Array<ByteArray>, right: Array<ByteArray>): Array<ByteArray> {
        val size = left.size
        val result = Array(size) { ByteArray(size) }
        for (row in 0 until size) {
            for (column in 0 until size) {
                var sum = 0
                for (index in 0 until size) {
                    sum = sum xor mul(
                        left[row][index].toInt() and 0xFF,
                        right[index][column].toInt() and 0xFF,
                    )
                }
                result[row][column] = sum.toByte()
            }
        }
        return result
    }

    /** Gauss-Jordan inversion; throws when the matrix is singular. */
    fun invert(matrix: Array<ByteArray>): Array<ByteArray> {
        val size = matrix.size
        val work = Array(size) { row ->
            ByteArray(size * 2).also { line ->
                System.arraycopy(matrix[row], 0, line, 0, size)
                line[size + row] = 1
            }
        }

        for (column in 0 until size) {
            var pivot = column
            while (pivot < size && work[pivot][column].toInt() == 0) pivot++
            check(pivot < size) { "Matrix is singular" }
            if (pivot != column) {
                val swap = work[pivot]
                work[pivot] = work[column]
                work[column] = swap
            }
            val factor = div(1, work[column][column].toInt() and 0xFF)
            for (index in 0 until size * 2) {
                work[column][index] = mul(work[column][index].toInt() and 0xFF, factor).toByte()
            }
            for (row in 0 until size) {
                if (row == column) continue
                val scale = work[row][column].toInt() and 0xFF
                if (scale == 0) continue
                for (index in 0 until size * 2) {
                    work[row][index] = (
                        (work[row][index].toInt() and 0xFF) xor
                            mul(scale, work[column][index].toInt() and 0xFF)
                        ).toByte()
                }
            }
        }

        return Array(size) { row -> work[row].copyOfRange(size, size * 2) }
    }
}

/** A finished shard set on disk. */
data class ShieldResult(
    val shards: List<String>,
    val dataShards: Int,
    val parityShards: Int,
    val originalSize: Long,
)

/**
 * The on-disk half: writing shards, and putting a file back together from
 * whichever of them are still there.
 *
 * Each shard is a self-describing file — magic, geometry, index, and the length
 * of the original — so a restore can be given a folder of shards and no other
 * information, and can tell the user how many are missing before it starts
 * rather than failing in the middle.
 */
@Singleton
class RecoveryShield @Inject constructor() {

    suspend fun create(
        source: File,
        destination: File,
        dataShards: Int,
        parityShards: Int,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): ShieldResult = withContext(Dispatchers.IO) {
        require(dataShards >= 1 && parityShards >= 1) { "Shard counts must be at least 1" }
        val rs = ReedSolomon(dataShards, parityShards)
        destination.mkdirs()

        val size = source.length()
        val shardSize = ((size + dataShards - 1) / dataShards).toInt().coerceAtLeast(1)
        val shards = Array(rs.totalShards) { ByteArray(shardSize) }

        RandomAccessFile(source, "r").use { access ->
            for (index in 0 until dataShards) {
                currentCoroutineContext().ensureActive()
                access.seek(index.toLong() * shardSize)
                var filled = 0
                while (filled < shardSize) {
                    val read = access.read(shards[index], filled, shardSize - filled)
                    if (read <= 0) break
                    filled += read
                }
                onProgress(index, dataShards, source.name)
            }
        }

        rs.encode(shards)

        val base = source.name.removeSuffix(".ffshard")
        val names = mutableListOf<String>()
        shards.forEachIndexed { index, bytes ->
            val shardFile = File(destination, "$base.${index}.ffshard")
            writeShard(shardFile, index, rs, size, bytes)
            names += shardFile.absolutePath
            onProgress(index, shards.size, shardFile.name)
        }

        ShieldResult(names, dataShards, parityShards, size)
    }

    /**
     * Rebuilds the original from whatever shards live in [folder] and match
     * [baseName]. Missing shards are rebuilt from the survivors.
     */
    suspend fun restore(
        folder: File,
        baseName: String,
        destination: File,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val candidates = folder.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$baseName.") && it.name.endsWith(".ffshard") }
            .orEmpty()
        check(candidates.isNotEmpty()) { "No shards for $baseName in ${folder.name}" }

        // The geometry is written into every shard, so any one of them is
        // enough to know how the set was built.
        val header = readHeader(candidates.first())
        val rs = ReedSolomon(header.dataShards, header.parityShards)
        val shards = Array(rs.totalShards) { ByteArray(header.shardSize) }
        val present = BooleanArray(rs.totalShards)

        candidates.forEach { file ->
            val index = file.name.removePrefix("$baseName.").removeSuffix(".ffshard").toIntOrNull()
                ?: return@forEach
            if (index in 0 until rs.totalShards) {
                readPayload(file, header.shardSize)?.let { payload ->
                    System.arraycopy(payload, 0, shards[index], 0, minOf(payload.size, header.shardSize))
                    present[index] = true
                }
            }
        }

        check(present.count { it } >= rs.dataShards) {
            "Only ${present.count { it }} shards found; ${rs.dataShards} are needed"
        }

        rs.decode(shards, present)
        onProgress(rs.dataShards, rs.totalShards, "rebuilt")

        destination.parentFile?.mkdirs()
        RandomAccessFile(destination, "rw").use { output ->
            output.setLength(0)
            var written = 0L
            for (index in 0 until rs.dataShards) {
                currentCoroutineContext().ensureActive()
                if (written >= header.originalSize) break
                val remaining = header.originalSize - written
                val length = minOf(shards[index].size.toLong(), remaining).toInt()
                output.write(shards[index], 0, length)
                written += length
                onProgress(index + 1, rs.totalShards, destination.name)
            }
        }
        destination
    }

    /** What the shard files in a folder claim, without reading their payloads. */
    fun inspect(folder: File): List<ShardHeader> = folder.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".ffshard") }
        .orEmpty()
        .mapNotNull { file -> runCatching { file.name to readHeader(file) }.getOrNull() }
        .map { (name, header) -> header.copy(name = name) }

    private fun writeShard(file: File, index: Int, rs: ReedSolomon, originalSize: Long, payload: ByteArray) {
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0)
            output.writeBytes(MAGIC)
            output.writeInt(rs.dataShards)
            output.writeInt(rs.parityShards)
            output.writeInt(index)
            output.writeLong(originalSize)
            output.writeInt(payload.size)
            output.write(payload)
        }
    }

    private fun readHeader(file: File): ShardHeader = RandomAccessFile(file, "r").use { input ->
        val magic = ByteArray(MAGIC.length)
        input.readFully(magic)
        check(String(magic) == MAGIC) { "${file.name} is not a recovery shard" }
        val data = input.readInt()
        val parity = input.readInt()
        val index = input.readInt()
        val original = input.readLong()
        val size = input.readInt()
        ShardHeader(
            name = file.name,
            dataShards = data,
            parityShards = parity,
            index = index,
            originalSize = original,
            shardSize = size,
            headerBytes = MAGIC.length + 4 * 4 + 8,
        )
    }

    private fun readPayload(file: File, expectedSize: Int): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            input.seek((MAGIC.length + 4 * 4 + 8).toLong())
            val buffer = ByteArray(expectedSize)
            input.readFully(buffer)
            buffer
        }
    }.getOrNull()

    private companion object {
        const val MAGIC = "FFSHARD1"
    }
}

data class ShardHeader(
    val name: String,
    val dataShards: Int,
    val parityShards: Int,
    val index: Int,
    val originalSize: Long,
    val shardSize: Int,
    val headerBytes: Int,
)
