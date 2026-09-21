package com.fileflow.app.core.storage

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One file inside a seekable archive. */
@androidx.compose.runtime.Immutable
data class SeekableEntry(
    val name: String,
    val sizeBytes: Long,
    val modified: Long,
    val isDirectory: Boolean,
)

/**
 * A compressed archive whose members can be read without decompressing the ones
 * before them.
 *
 * Every single-stream format has the same problem: to reach the last file you
 * decompress everything ahead of it, so a multi-gigabyte backup cannot be
 * browsed on a phone at all. The fix is an index — the same idea as zstd's
 * seekable format, bgzip and indexed gzip — and it only needs the deflater the
 * JDK already ships:
 *
 * ```
 *   "FFZ1" | index offset (8 bytes) | members, each its own deflate stream
 *   at the index offset: entry count, then per entry:
 *     name, original size, modified, is directory, data offset, data length
 * ```
 *
 * The index is written last and its position is in the header, so opening an
 * archive is one seek and reading one member is another — no matter how large
 * the archive is. Each member is compressed independently, which costs a little
 * ratio compared to one continuous stream and buys the ability to seek, and is
 * exactly the trade every seekable format makes.
 *
 * One limit, stated rather than hidden: the *inside* of a member cannot be
 * seeked, because it is one deflate stream. Browsing, opening and extracting a
 * file from a huge archive is instant; jumping to the middle of a member is not
 * something this format offers.
 */
object SeekableArchive {

    private const val MAGIC = "FFZ1"
    private const val COPY_BUFFER = 128 * 1024

    /** The in-progress file, named so the sweeper can find an abandoned one. */
    private const val PARTIAL = ".fileflow-part"

    /** The extension the archive gets, and that the browser recognises. */
    const val EXTENSION = "ffz"

    suspend fun write(
        sources: List<File>,
        destination: File,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): ArchiveResult = withContext(Dispatchers.IO) {
        val files = sources.filter { it.exists() }
        require(files.isNotEmpty()) { "Nothing to compress" }

        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + PARTIAL)
        val entries = mutableListOf<EntryRecord>()
        var count = 0
        var bytes = 0L
        var indexPosition = 0L

        try {
            DataOutputStream(FileOutputStream(partial).buffered(COPY_BUFFER)).use { output ->
                output.writeBytes(MAGIC)
                // Patched with the real index position once it is known.
                output.writeLong(0L)

                for (source in files) {
                    currentCoroutineContext().ensureActive()
                    onProgress(count, files.size, source.name)
                    val written = writeTree(output, source, source.name, entries)
                    count += written.first
                    bytes += written.second
                }

                indexPosition = output.size().toLong()
                output.writeInt(entries.size)
                entries.forEach { entry ->
                    output.writeUTF(entry.name)
                    output.writeLong(entry.sizeBytes)
                    output.writeLong(entry.modified)
                    output.writeBoolean(entry.isDirectory)
                    output.writeLong(entry.dataOffset)
                    output.writeInt(entry.dataLength)
                }
                output.flush()
            }

            // The header can only be filled in now: the index it points at is
            // written after the members, which is what makes one pass enough.
            RandomAccessFile(partial, "rw").use { access ->
                access.seek(MAGIC.length.toLong())
                access.writeLong(indexPosition)
            }

            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            ArchiveResult(entries = count, bytes = bytes, destination = destination.absolutePath)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            partial.delete()
            destination.delete()
            throw cancelled
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    private suspend fun writeTree(
        output: DataOutputStream,
        file: File,
        name: String,
        entries: MutableList<EntryRecord>,
    ): Pair<Int, Long> {
        currentCoroutineContext().ensureActive()
        if (file.isDirectory) {
            entries += EntryRecord(
                name = "$name/",
                sizeBytes = 0L,
                modified = file.lastModified(),
                isDirectory = true,
                dataOffset = output.size().toLong(),
                dataLength = 0,
            )
            var count = 1
            var bytes = 0L
            file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                val written = writeTree(output, child, "$name/${child.name}", entries)
                count += written.first
                bytes += written.second
            }
            return count to bytes
        }
        if (!file.isFile) return 0 to 0L

        val dataOffset = output.size().toLong()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        var written = 0L
        FileInputStream(file).use { input ->
            // The compressor is finished but the stream underneath it is not
            // closed: closing a DeflaterOutputStream closes what it wraps, and
            // the next member would then be written to a dead stream.
            val compressed = DeflaterOutputStream(ShieldedOutputStream(output), deflater, COPY_BUFFER)
            try {
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    compressed.write(buffer, 0, read)
                    written += read
                }
                compressed.finish()
            } finally {
                deflater.end()
            }
        }
        entries += EntryRecord(
            name = name,
            sizeBytes = written,
            modified = file.lastModified(),
            isDirectory = false,
            dataOffset = dataOffset,
            dataLength = (output.size() - dataOffset).toInt(),
        )
        return 1 to written
    }

    private data class EntryRecord(
        val name: String,
        val sizeBytes: Long,
        val modified: Long,
        val isDirectory: Boolean,
        val dataOffset: Long,
        val dataLength: Int,
    )

    /** The members, read from the index alone — nothing is decompressed. */
    suspend fun list(archive: File): List<SeekableEntry> = withContext(Dispatchers.IO) {
        readIndex(archive).map { record ->
            SeekableEntry(
                name = record.name.trimEnd('/'),
                sizeBytes = record.sizeBytes,
                modified = record.modified,
                isDirectory = record.isDirectory,
            )
        }
    }

    /** Opens one member: two seeks and one inflate, whatever the archive's size. */
    fun open(archive: File, entryName: String): InputStream {
        val record = readIndex(archive).firstOrNull {
            !it.isDirectory && it.name.trimEnd('/') == entryName.trimEnd('/')
        } ?: throw IllegalArgumentException("No such entry: $entryName")

        val access = RandomAccessFile(archive, "r")
        access.seek(record.dataOffset)
        val limited = LimitedInputStream(access, record.dataLength.toLong())
        val inflater = Inflater(true)
        return object : InflaterInputStream(limited, inflater, COPY_BUFFER) {
            override fun close() {
                runCatching { super.close() }
                runCatching { inflater.end() }
                runCatching { access.close() }
            }
        }
    }

    /** The whole index, parsed from the tail of the file. */
    private fun readIndex(archive: File): List<EntryRecord> {
        DataInputStream(BufferedInputStream(FileInputStream(archive), COPY_BUFFER)).use { input ->
            val magic = ByteArray(MAGIC.length)
            input.readFully(magic)
            require(String(magic) == MAGIC) { "${archive.name} is not a seekable archive" }
            val indexOffset = input.readLong()
            require(indexOffset > 0L) { "${archive.name} has no index" }

            // Skipped in bounded steps: `skipBytes` takes an Int, and the
            // distance to the index of a multi-gigabyte archive does not fit
            // in one.
            var toSkip = indexOffset - MAGIC.length - 8
            while (toSkip > 0L) {
                val skipped = input.skipBytes(minOf(toSkip, 1_000_000L).toInt())
                if (skipped <= 0) break
                toSkip -= skipped
            }
            val count = input.readInt()
            val records = ArrayList<EntryRecord>(count)
            repeat(count) {
                records += EntryRecord(
                    name = input.readUTF(),
                    sizeBytes = input.readLong(),
                    modified = input.readLong(),
                    isDirectory = input.readBoolean(),
                    dataOffset = input.readLong(),
                    dataLength = input.readInt(),
                )
            }
            return records
        }
    }

    fun matches(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val magic = ByteArray(MAGIC.length)
            input.read(magic) == MAGIC.length && String(magic) == MAGIC
        }
    }.getOrDefault(false)

    /**
     * Ignores `close`, so a stream built on top of the archive's output can be
     * finished without ending the archive itself.
     */
    private class ShieldedOutputStream(
        private val wrapped: java.io.OutputStream,
    ) : java.io.OutputStream() {
        override fun write(byte: Int) = wrapped.write(byte)
        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            wrapped.write(buffer, offset, length)

        override fun flush() = wrapped.flush()
        override fun close() = flush()
    }

    /** Reads at most [limit] bytes, so an inflater cannot run past its member. */
    private class LimitedInputStream(
        private val access: RandomAccessFile,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = access.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val wanted = minOf(length.toLong(), remaining).toInt()
            val read = access.read(buffer, offset, wanted)
            if (read > 0) remaining -= read
            return read
        }

        override fun available(): Int = remaining.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}
