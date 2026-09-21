package com.fileflow.app.core.delta

import com.fileflow.app.core.analysis.Chunker
import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The list of pieces a file is made of, small enough to send over the wire.
 *
 * A file is described by the content-defined chunks the chunker already finds:
 * their offsets, their lengths, and a hash of each. Sending this costs a few
 * kilobytes where sending the file costs gigabytes, and because the boundaries
 * are decided by the content, a chunk that survives an edit keeps its hash —
 * which is the entire trick.
 */
@Serializable
data class DeltaManifest(
    val size: Long,
    val chunks: List<DeltaChunk>,
) {
    val totalBytes: Long get() = chunks.sumOf { it.length.toLong() }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): DeltaManifest? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

@Serializable
data class DeltaChunk(
    val offset: Long,
    val length: Int,
    val hash: String,
)

/** One reusable piece: same bytes, already on this side. */
data class Reuse(
    val offset: Long,
    val length: Int,
    val localOffset: Long,
)

/**
 * What a transfer would actually move.
 *
 * The plan is computed from the two manifests alone, so the user can be told
 * "1.2 MB of 800 MB" before a single byte crosses the network — and a plan that
 * turns out to be wrong is a bug in the arithmetic, not something discovered
 * halfway through a transfer.
 */
data class DeltaPlan(
    val remoteSize: Long,
    val reuse: List<Reuse>,
    val fetch: List<DeltaChunk>,
) {
    val reusableBytes: Long get() = reuse.sumOf { it.length.toLong() }
    val fetchBytes: Long get() = fetch.sumOf { it.length.toLong() }

    /** The headline number: how much of the file does not have to move. */
    val savedPercent: Int
        get() = if (remoteSize <= 0L) 0 else ((reusableBytes * 100) / remoteSize).toInt()

    val nothingToDo: Boolean get() = fetch.isEmpty()
}

/** Where a missing chunk comes from. A network, a socket, a test. */
interface ChunkSource {
    suspend fun chunk(offset: Long, length: Int): ByteArray?
}

/**
 * Transfers the difference between two copies of a file.
 *
 * This is the part of a sync that decides, for each piece of the remote file,
 * whether this side already has those exact bytes somewhere — its own copy may
 * be an older revision with a paragraph inserted near the top, and the pieces
 * after that paragraph are still byte-for-byte identical. Reused pieces are
 * copied from the local file; only the rest is fetched.
 *
 * Reuse is safe because it is decided by hash: bytes are only taken from the
 * local file when the hash of what is there matches the hash of what is wanted,
 * so a plan that says "reuse" can never quietly produce a corrupted file.
 */
object DeltaTransfer {

    suspend fun manifest(file: File): DeltaManifest =
        DeltaManifest(file.length(), Chunker.split(file).map { it.toDelta() })

    /**
     * Compares the local file's pieces with the remote manifest and says which
     * pieces can be taken from here and which have to be fetched.
     */
    suspend fun plan(localFile: File, remote: DeltaManifest): DeltaPlan {
        val available = HashMap<String, Pair<Long, Int>>()
        if (localFile.isFile && localFile.length() > 0L) {
            for (chunk in Chunker.split(localFile)) {
                // First writer wins. Choosing the last would be no better and no
                // worse, but "first" is the answer that does not depend on
                // iteration order.
                available.putIfAbsent(chunk.hash, chunk.offset to chunk.length)
            }
        }

        val reuse = mutableListOf<Reuse>()
        val fetch = mutableListOf<DeltaChunk>()
        for (chunk in remote.chunks) {
            val candidate = available[chunk.hash]
            if (candidate != null) {
                reuse += Reuse(chunk.offset, chunk.length, candidate.first)
            } else {
                fetch += chunk
            }
        }
        return DeltaPlan(remote.size, reuse, fetch)
    }

    /**
     * Writes the remote revision to [output], taking reused pieces from
     * [localFile] and the rest from [source]. The output is only moved into
     * place once every byte is accounted for, so an interrupted transfer leaves
     * the previous file untouched — and leaves nothing else behind either.
     */
    suspend fun apply(
        localFile: File,
        plan: DeltaPlan,
        source: ChunkSource,
        output: File,
    ): Boolean {
        val temporary = File(output.parentFile, "${output.name}.part")
        var finished = false
        try {
            if (!writeAll(localFile, plan, source, temporary)) return false
            if (output.exists()) output.delete()
            finished = temporary.renameTo(output)
            return finished
        } catch (_: Exception) {
            return false
        } finally {
            // A half-written file is not a file; the whole point of building it
            // beside the real one is that it can be thrown away.
            if (!finished) temporary.delete()
        }
    }

    private suspend fun writeAll(
        localFile: File,
        plan: DeltaPlan,
        source: ChunkSource,
        temporary: File,
    ): Boolean {
        RandomAccessFile(temporary, "rw").use { writer ->
            writer.setLength(plan.remoteSize)

            // Reused pieces: a seek into the local file and a copy.
            if (plan.reuse.isNotEmpty()) {
                RandomAccessFile(localFile, "r").use { reader ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    for (piece in plan.reuse) {
                        reader.seek(piece.localOffset)
                        writer.seek(piece.offset)
                        var written = 0
                        while (written < piece.length) {
                            val read = reader.read(buffer, 0, minOf(buffer.size, piece.length - written))
                            if (read <= 0) return false
                            writer.write(buffer, 0, read)
                            written += read
                        }
                    }
                }
            }

            for (chunk in plan.fetch) {
                val bytes = source.chunk(chunk.offset, chunk.length) ?: return false
                if (bytes.size != chunk.length) return false
                writer.seek(chunk.offset)
                writer.write(bytes)
            }
        }
        return true
    }

    private fun Chunker.Chunk.toDelta() = DeltaChunk(offset, length, hash)

    private const val BUFFER_SIZE = 256 * 1024
}
