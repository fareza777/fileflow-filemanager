package com.fileflow.app.core.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * POSIX tar, written and read without a dependency.
 *
 * Zip is what phones exchange; tar is what everything else exchanges, and a
 * `.tar.gz` is the only sensible way to move a folder of code or a Linux
 * backup. The format is old and small enough to implement honestly: a 512-byte
 * header per entry, the bytes, and padding to the next block. The one part
 * worth care is the same as in the zip reader — an entry names its own output
 * path, so every name is resolved against the destination before anything is
 * written.
 */
internal object TarFormat {

    private const val BLOCK = 512
    private const val NAME_LENGTH = 100
    private const val PREFIX_LENGTH = 155
    private const val COPY_BUFFER = 128 * 1024

    /** Directories, files and symlinks; everything else is skipped on read. */
    private const val TYPE_FILE = '0'
    private const val TYPE_DIRECTORY = '5'
    private const val TYPE_FILE_LEGACY = '\u0000'

    suspend fun write(
        sources: List<File>,
        output: OutputStream,
        onProgress: (name: String, bytesDone: Long, bytesTotal: Long) -> Unit = { _, _, _ -> },
    ): Pair<Int, Long> {
        var entries = 0
        var written = 0L
        val total = sources.sumOf { sizeOf(it) }

        suspend fun writeEntry(file: File, name: String) {
            currentCoroutineContext().ensureActive()
            if (file.isDirectory) {
                output.write(header(name, 0L, file.lastModified(), directory = true))
                entries++
                file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                    writeEntry(child, "$name/${child.name}")
                }
                return
            }
            if (!file.isFile) return
            val size = file.length()
            onProgress(name, written, total)
            output.write(header(name, size, file.lastModified(), directory = false))
            var copied = 0L
            FileInputStream(file).use { input ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    written += read
                }
            }
            output.write(padding(copied))
            entries++
            onProgress(name, written, total)
        }

        sources.filter { it.exists() }.forEach { source ->
            writeEntry(source, source.name)
        }
        // Two empty blocks end an archive; without them a reader that trusts the
        // format keeps looking for an entry that is not there.
        output.write(ByteArray(BLOCK * 2))
        output.flush()
        return entries to written
    }

    /**
     * Streams an archive out, entry by entry. The entry count is not known
     * before the last block has been read — that is what a tar is — so progress
     * is reported in bytes consumed, which for a compressed archive is at least
     * proportional to the work left.
     */
    suspend fun read(
        input: InputStream,
        destinationDirectory: File,
        archiveBytesTotal: Long,
        onProgress: (name: String, bytesDone: Long, bytesTotal: Long) -> Unit = { _, _, _ -> },
    ): Triple<Int, Long, Int> {
        destinationDirectory.mkdirs()
        val root = destinationDirectory.canonicalFile
        val claimed = HashSet<String>()
        var entries = 0
        var bytes = 0L
        var renamed = 0
        var consumed = 0L

        while (true) {
            currentCoroutineContext().ensureActive()
            val header = readFully(input, BLOCK) ?: break
            consumed += BLOCK
            if (header.all { it == 0.toByte() }) break

            val name = entryName(header) ?: continue
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val payload = if (type == TYPE_DIRECTORY) 0L else size

            val resolved = ArchivePaths.resolveSafely(root, name)
            if (resolved != null) {
                if (type == TYPE_DIRECTORY) {
                    resolved.mkdirs()
                    entries++
                } else if (type == TYPE_FILE || type == TYPE_FILE_LEGACY) {
                    val target = ArchivePaths.claim(resolved, claimed)
                    if (target != resolved) renamed++
                    target.parentFile?.mkdirs()
                    onProgress(name, consumed, archiveBytesTotal)
                    FileOutputStream(target).use { output ->
                        bytes += copy(input, output, payload)
                    }
                    consumed += payload
                } else {
                    skip(input, payload)
                    consumed += payload
                }
            } else {
                skip(input, payload)
                consumed += payload
            }

            // Entry data is padded to a block boundary.
            val padding = paddingSize(payload)
            if (padding > 0) {
                skip(input, padding.toLong())
                consumed += padding
            }
            onProgress(name, consumed, archiveBytesTotal)
        }

        return Triple(entries, bytes, renamed)
    }

    // ------------------------------------------------------------- writing

    private fun header(
        name: String,
        size: Long,
        modifiedMillis: Long,
        directory: Boolean,
    ): ByteArray {
        val block = ByteArray(BLOCK)
        val (prefix, shortName) = splitName(name, directory)
        putString(block, 0, NAME_LENGTH, shortName)
        putOctal(block, 100, 8, 0b110100100) // 0644
        putOctal(block, 108, 8, 0)
        putOctal(block, 116, 8, 0)
        putOctal(block, 124, 12, size)
        putOctal(block, 136, 12, modifiedMillis / 1000)
        block[156] = if (directory) TYPE_DIRECTORY.code.toByte() else TYPE_FILE.code.toByte()
        putString(block, 257, 6, "ustar")
        block[263] = '0'.code.toByte()
        block[264] = '0'.code.toByte()
        putString(block, 265, 32, "fileflow")
        putString(block, 297, 32, "fileflow")
        putString(block, 345, PREFIX_LENGTH, prefix)

        // The checksum is computed with its own field read as spaces, which is
        // the one part of this format everyone has to special-case.
        for (index in 148 until 156) block[index] = ' '.code.toByte()
        var sum = 0
        block.forEach { sum += it.toInt() and 0xFF }
        putString(block, 148, 8, sum.toString(8).padStart(6, '0') + "\u0000 ")
        return block
    }

    /**
     * ustar keeps long names by splitting them across a prefix field and the
     * name field at a slash. When even that cannot hold the path, the file name
     * is kept and the folders are dropped — a name that is wrong beats an entry
     * that cannot be read back.
     */
    private fun splitName(name: String, directory: Boolean): Pair<String, String> {
        val trimmed = name.removePrefix("/")
        if (trimmed.length <= NAME_LENGTH) return "" to trimmed
        var cut = trimmed.length - NAME_LENGTH - 1
        while (cut > 0) {
            if (trimmed[cut] == '/' && cut <= PREFIX_LENGTH) {
                return trimmed.substring(0, cut) to trimmed.substring(cut + 1)
            }
            cut--
        }
        val fallback = trimmed.substringAfterLast('/')
        return "" to fallback.take(NAME_LENGTH)
    }

    /** Source bytes, used only to give the progress bar a denominator. */
    private fun sizeOf(file: File): Long {
        if (file.isFile) return file.length()
        if (!file.isDirectory) return 0L
        return runCatching {
            file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
        }.getOrDefault(0L)
    }

    private fun putString(block: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val count = minOf(bytes.size, length)
        System.arraycopy(bytes, 0, block, offset, count)
    }

    private fun putOctal(block: ByteArray, offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0')
        putString(block, offset, length - 1, text.takeLast(length - 1))
        block[offset + length - 1] = 0
    }

    private fun padding(bytes: Long): ByteArray = ByteArray(paddingSize(bytes))

    private fun paddingSize(bytes: Long): Int {
        val remainder = (bytes % BLOCK).toInt()
        return if (remainder == 0) 0 else BLOCK - remainder
    }

    // ------------------------------------------------------------- reading

    private fun entryName(header: ByteArray): String? {
        val name = string(header, 0, NAME_LENGTH)
        val prefix = string(header, 345, PREFIX_LENGTH)
        val full = if (prefix.isNotEmpty()) "$prefix/$name" else name
        return full.ifBlank { null }
    }

    private fun string(block: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && block[end] != 0.toByte()) end++
        return String(block, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun octal(block: ByteArray, offset: Int, length: Int): Long {
        val text = string(block, offset, length).trim().takeWhile { it in '0'..'7' }
        if (text.isEmpty()) return 0
        return text.toLongOrNull(8) ?: 0
    }

    private fun readFully(input: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(buffer, read, length - read)
            if (count <= 0) return if (read == 0) null else buffer.copyOf(read)
            read += count
        }
        return buffer
    }

    private suspend fun copy(input: InputStream, output: OutputStream, length: Long): Long {
        val buffer = ByteArray(COPY_BUFFER)
        var remaining = length
        var total = 0L
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
            total += read
        }
        return total
    }

    private fun skip(input: InputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(COPY_BUFFER)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            remaining -= read
        }
    }
}
