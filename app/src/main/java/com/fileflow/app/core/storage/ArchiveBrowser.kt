package com.fileflow.app.core.storage

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One entry inside an archive, as the browser sees it. */
@androidx.compose.runtime.Immutable
data class ArchiveEntry(
    /** Path inside the archive, without the archive's own name. */
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * Archives as folders.
 *
 * A zip is not a folder, but it is a tree, and every file manager worth the name
 * lets you walk into one instead of extracting it first to find out what is in
 * it. This is the reading half: list a level, open one entry, copy one entry
 * out — with the format decided by the file's name, the same way extraction
 * decides it.
 *
 * The tar index is cached because a tar has no central directory: without a
 * cache, opening a folder inside a 4 GB tar would walk the whole thing again for
 * every tap.
 */
@Singleton
class ArchiveBrowser @Inject constructor() {

    private val tarIndexes = object :
        LinkedHashMap<String, Pair<Pair<Long, Long>, List<TarFormat.Entry>>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Pair<Long, Long>, List<TarFormat.Entry>>>?,
        ) = size > MAX_CACHED_INDEXES
    }

    fun isArchive(file: File): Boolean = file.isFile && ArchiveFormat.of(file.name) != null

    /**
     * The children of [inner] inside [archive]. Directories that exist only as a
     * consequence of the files under them — which is most of them, in a tar —
     * are still reported, because a tree that hides its folders is not a tree.
     */
    suspend fun list(archive: File, inner: String): List<ArchiveEntry> = withContext(Dispatchers.IO) {
        val prefix = inner.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val seen = LinkedHashMap<String, ArchiveEntry>()

        fun add(entryPath: String, isDirectory: Boolean, size: Long, modified: Long) {
            val name = entryPath.substringBefore('/')
            if (name.isEmpty()) return
            val childPath = if (prefix.isEmpty()) name else prefix + name
            val immediate = !entryPath.contains('/')
            val existing = seen[childPath]
            if (existing == null) {
                seen[childPath] = ArchiveEntry(
                    path = childPath,
                    name = name,
                    isDirectory = isDirectory || !immediate,
                    sizeBytes = if (isDirectory || !immediate) 0L else size,
                    lastModified = modified,
                )
            } else if (existing.isDirectory && immediate && !isDirectory) {
                // A file and a folder can share a name in a zip; the file wins,
                // because it is the one that has content.
                seen[childPath] = existing.copy(isDirectory = false, sizeBytes = size)
            }
        }

        when (ArchiveFormat.of(archive.name)) {
            ArchiveFormat.ZIP -> ZipFile(archive).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val name = entry.name
                    if (!name.startsWith(prefix) || name == prefix) return@forEach
                    add(
                        entryPath = name.removePrefix(prefix).trimEnd('/'),
                        isDirectory = entry.isDirectory || name.endsWith('/'),
                        size = entry.size.coerceAtLeast(0L),
                        modified = entry.time,
                    )
                }
            }

            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ ->
                tarIndex(archive).forEach { entry ->
                    if (!entry.name.startsWith(prefix)) return@forEach
                    add(
                        entryPath = entry.name.removePrefix(prefix).trimEnd('/'),
                        isDirectory = entry.isDirectory,
                        size = entry.size,
                        modified = entry.modifiedSeconds * 1000L,
                    )
                }

            null -> Unit
        }

        seen.values.sortedWith(
            compareByDescending<ArchiveEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
    }

    /** Reads one entry. The caller closes the stream, which closes the archive. */
    suspend fun open(archive: File, entryPath: String): InputStream = withContext(Dispatchers.IO) {
        when (ArchiveFormat.of(archive.name)) {
            ArchiveFormat.ZIP -> {
                val zip = ZipFile(archive)
                val entry = zip.getEntry(entryPath) ?: zip.getEntry("$entryPath/")
                if (entry == null || entry.isDirectory) {
                    zip.close()
                    throw IllegalArgumentException("No such entry: $entryPath")
                }
                val stream = zip.getInputStream(entry)
                object : InputStream() {
                    override fun read(): Int = stream.read()
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        stream.read(buffer, offset, length)

                    override fun close() {
                        runCatching { stream.close() }
                        runCatching { zip.close() }
                    }
                }
            }

            ArchiveFormat.TAR -> {
                val match = tarIndex(archive).firstOrNull { it.name.trimEnd('/') == entryPath.trimEnd('/') }
                    ?: throw IllegalArgumentException("No such entry: $entryPath")
                // Seeks instead of scanning: a plain tar is addressable, and this
                // is the difference between opening a file and walking to it.
                val access = RandomAccessFile(archive, "r")
                access.seek(match.dataOffset)
                object : InputStream() {
                    private var remaining = match.size

                    override fun read(): Int {
                        if (remaining <= 0) return -1
                        val value = access.read()
                        if (value >= 0) remaining--
                        return value
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (remaining <= 0) return -1
                        val count = access.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                        if (count > 0) remaining -= count
                        return count
                    }

                    override fun close() {
                        runCatching { access.close() }
                    }
                }
            }

            ArchiveFormat.TAR_GZ -> {
                val match = tarIndex(archive).firstOrNull {
                    it.name.trimEnd('/') == entryPath.trimEnd('/')
                } ?: throw IllegalArgumentException("No such entry: $entryPath")
                val input = GZIPInputStream(BufferedInputStream(FileInputStream(archive), COPY_BUFFER))
                TarFormat.openEntry(input, match)
            }

            null -> throw IllegalArgumentException("Not an archive: ${archive.name}")
        }
    }

    /** Copies one entry out to [target]. Returns the bytes written. */
    suspend fun extract(
        archive: File,
        entryPath: String,
        target: File,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        var total = 0L
        open(archive, entryPath).use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                    onProgress(total, 0L)
                }
            }
        }
        total
    }

    /**
     * The tar index, cached against the archive's size and timestamp: the moment
     * either changes, the cache is stale, and a stale index would open the wrong
     * bytes.
     */
    private suspend fun tarIndex(archive: File): List<TarFormat.Entry> {
        val stamp = archive.length() to archive.lastModified()
        tarIndexes[archive.absolutePath]?.let { (cachedStamp, entries) ->
            if (cachedStamp == stamp) return entries
        }
        val entries = when (ArchiveFormat.of(archive.name)) {
            ArchiveFormat.TAR_GZ -> GZIPInputStream(
                BufferedInputStream(FileInputStream(archive), COPY_BUFFER)
            ).use { TarFormat.index(it) }

            else -> FileInputStream(archive).use { TarFormat.index(it) }
        }
        tarIndexes[archive.absolutePath] = stamp to entries
        return entries
    }

    private companion object {
        const val COPY_BUFFER = 128 * 1024
        const val MAX_CACHED_INDEXES = 6
    }
}
