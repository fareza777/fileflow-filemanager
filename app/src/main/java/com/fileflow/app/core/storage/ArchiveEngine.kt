package com.fileflow.app.core.storage

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import android.os.StatFs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ArchiveResult(
    val entries: Int,
    val bytes: Long,
    val destination: String?,
    /** Entries that had to be given a different name to avoid a collision. */
    val renamed: Int = 0,
)

/** What an archive is written as. */
enum class ArchiveFormat(val extension: String, val label: String) {
    ZIP("zip", "ZIP"),
    TAR("tar", "TAR"),
    TAR_GZ("tar.gz", "TAR.GZ"),
    ;

    companion object {
        /** Matches on the whole name, so "notes.tar.gz" is not read as a tar. */
        fun of(fileName: String): ArchiveFormat? {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> TAR_GZ
                lower.endsWith(".tar") -> TAR
                lower.endsWith(".zip") -> ZIP
                else -> null
            }
        }
    }
}

/** How hard the compressor tries, from "just store it" to "smallest". */
enum class CompressionLevel(val deflate: Int, val label: String) {
    STORE(0, "Store"),
    FAST(1, "Fast"),
    NORMAL(6, "Normal"),
    MAXIMUM(9, "Maximum"),
}

/** One tick of an archive job, in entries and in bytes. */
data class ArchiveProgress(
    val done: Int,
    val total: Int,
    val name: String,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
)

/**
 * Zip support, using the JDK's own implementation — no dependency, no native
 * library, and it works on the same storage the rest of the app does.
 *
 * The one thing worth being careful about is extraction: a crafted archive can
 * contain entry names like `../../databases/foo`, and writing those out is how
 * a zip turns into an escape from the destination folder. Every entry is
 * resolved and checked before anything is written.
 */
@Singleton
class ArchiveEngine @Inject constructor() {

    /**
     * Writes [sources] into one archive. The format and the effort are the
     * caller's choice because they are genuinely different answers: a zip is
     * what a phone can open, a tar.gz is what a server expects, and "store"
     * is what you pick when the payload is already compressed.
     */
    suspend fun compress(
        sources: List<String>,
        destination: File,
        format: ArchiveFormat = ArchiveFormat.ZIP,
        level: CompressionLevel = CompressionLevel.NORMAL,
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Result<ArchiveResult> = withContext(Dispatchers.IO) {
        runCatching {
            val files = sources.map(::File).filter { it.exists() }
            if (files.isEmpty()) error("Nothing to compress")

            destination.parentFile?.mkdirs()
            val partial = File(destination.parentFile, destination.name + PARTIAL_SUFFIX)
            var count = 0
            var bytes = 0L

            try {
                FileOutputStream(partial).use { fileOutput ->
                    when (format) {
                        ArchiveFormat.ZIP -> {
                            ZipOutputStream(BufferedOutputStream(fileOutput, COPY_BUFFER)).use { zip ->
                                val written = writeZip(zip, files, level, onProgress)
                                count = written.first
                                bytes = written.second
                            }
                        }

                        ArchiveFormat.TAR -> {
                            val written = TarFormat.write(
                                sources = files,
                                output = fileOutput,
                                onProgress = { name, done, total ->
                                    onProgress(ArchiveProgress(0, 0, name, done, total))
                                },
                            )
                            count = written.first
                            bytes = written.second
                        }

                        ArchiveFormat.TAR_GZ -> {
                            // GZIPOutputStream has no level of its own; the
                            // deflater it builds is the only way in.
                            val gzip = object : GZIPOutputStream(fileOutput, COPY_BUFFER) {
                                init {
                                    def.setLevel(level.deflate)
                                }
                            }
                            gzip.use { stream ->
                                val written = TarFormat.write(
                                    sources = files,
                                    output = stream,
                                    onProgress = { name, done, total ->
                                        onProgress(ArchiveProgress(0, 0, name, done, total))
                                    },
                                )
                                count = written.first
                                bytes = written.second
                            }
                        }
                    }
                }
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                partial.delete()
                destination.delete()
                throw cancelled
            } catch (error: Throwable) {
                partial.delete()
                throw error
            }

            ArchiveResult(entries = count, bytes = bytes, destination = destination.absolutePath)
        }
    }

    private suspend fun writeZip(
        zip: ZipOutputStream,
        files: List<File>,
        level: CompressionLevel,
        onProgress: (ArchiveProgress) -> Unit,
    ): Pair<Int, Long> {
        zip.setLevel(level.deflate)
        var count = 0
        var bytes = 0L
        val total = files.sumOf { sourceSize(it) }
        files.forEachIndexed { index, file ->
            currentCoroutineContext().ensureActive()
            onProgress(ArchiveProgress(index, files.size, file.name, bytes, total))
            val written = addToZip(zip, file, file.name)
            count += written.first
            bytes += written.second
            onProgress(ArchiveProgress(index, files.size, file.name, bytes, total))
        }
        return count to bytes
    }

    /** Source bytes, used only to give the progress bar a denominator. */
    private fun sourceSize(file: File): Long {
        if (file.isFile) return file.length()
        if (!file.isDirectory) return 0L
        return runCatching { file.listFiles()?.sumOf { sourceSize(it) } ?: 0L }.getOrDefault(0L)
    }

    private suspend fun addToZip(
        zip: ZipOutputStream,
        file: File,
        entryName: String,
    ): Pair<Int, Long> {
        currentCoroutineContext().ensureActive()
        if (file.isDirectory) {
            zip.putNextEntry(ZipEntry("$entryName/"))
            zip.closeEntry()
            var count = 1
            var bytes = 0L
            file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                val written = addToZip(zip, child, "$entryName/${child.name}")
                count += written.first
                bytes += written.second
            }
            return count to bytes
        }

        zip.putNextEntry(ZipEntry(entryName))
        var size = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                zip.write(buffer, 0, read)
                size += read
            }
        }
        zip.closeEntry()
        return 1 to size
    }

    /**
     * Unpacks any archive this app writes, chosen by the file's own name rather
     * than by a parameter: the extension is what a user has to go on, so it is
     * what the reader trusts too.
     */
    suspend fun extract(
        archive: File,
        destinationDirectory: File,
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Result<ArchiveResult> = withContext(Dispatchers.IO) {
        val format = ArchiveFormat.of(archive.name)
            ?: return@withContext Result.failure(IllegalArgumentException("Not an archive"))
        runCatching {
            when (format) {
                ArchiveFormat.ZIP -> extractZip(archive, destinationDirectory, onProgress)
                ArchiveFormat.TAR -> {
                    FileInputStream(archive).use { input ->
                        val (entries, bytes, renamed) = TarFormat.read(
                            input = BufferedInputStream(input, COPY_BUFFER),
                            destinationDirectory = destinationDirectory,
                            archiveBytesTotal = archive.length(),
                            onProgress = { name, done, total ->
                                onProgress(ArchiveProgress(0, 0, name, done, total))
                            },
                        )
                        ArchiveResult(entries, bytes, destinationDirectory.absolutePath, renamed)
                    }
                }
                ArchiveFormat.TAR_GZ -> {
                    FileInputStream(archive).use { input ->
                        GZIPInputStream(BufferedInputStream(input, COPY_BUFFER)).use { gzip ->
                            val (entries, bytes, renamed) = TarFormat.read(
                                input = gzip,
                                destinationDirectory = destinationDirectory,
                                archiveBytesTotal = archive.length(),
                                onProgress = { name, done, total ->
                                    onProgress(ArchiveProgress(0, 0, name, done, total))
                                },
                            )
                            ArchiveResult(entries, bytes, destinationDirectory.absolutePath, renamed)
                        }
                    }
                }
            }
        }
    }

    private suspend fun extractZip(
        zipFile: File,
        destinationDirectory: File,
        onProgress: (ArchiveProgress) -> Unit,
    ): ArchiveResult {
        destinationDirectory.mkdirs()
        val root = destinationDirectory.canonicalFile
        var count = 0
        var bytes = 0L
        var renamed = 0
        val claimed = HashSet<String>()

        // ZipFile reads the central directory, so sizes and the entry count are
        // known before a single byte is decompressed. That is what makes the bomb
        // guard below possible: with ZipInputStream the only way to learn the
        // output size was to produce it.
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList()
            val total = entries.size
            guardAgainstBomb(zipFile, entries, destinationDirectory)

            entries.forEachIndexed { index, entry ->
                currentCoroutineContext().ensureActive()
                val resolved = ArchivePaths.resolveSafely(root, entry.name)
                if (resolved == null) return@forEachIndexed

                onProgress(ArchiveProgress(index, total, entry.name, bytes, 0))
                if (entry.isDirectory) {
                    resolved.mkdirs()
                } else {
                    val target = ArchivePaths.claim(resolved, claimed)
                    if (target != resolved) renamed++
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(COPY_BUFFER)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                bytes += read
                            }
                        }
                    }
                }
                count++
            }
        }

        return ArchiveResult(
            entries = count,
            bytes = bytes,
            destination = destinationDirectory.absolutePath,
            renamed = renamed,
        )
    }

    /**
     * A small archive can declare a gigantic output: a 1 MB zip with a 10,000:1
     * ratio fills the disk just as reliably as a big one, and it used to be
     * unpacked until the write failed somewhere in the middle of the volume.
     *
     * Both numbers come from the central directory, so nothing is decompressed
     * to measure it. The declared sizes are checked, not trusted — the write
     * loop still runs against real bytes, and a lying entry is caught by the
     * free-space check failing during extraction.
     */
    private fun guardAgainstBomb(
        zipFile: File,
        entries: List<ZipEntry>,
        destination: File,
    ) {
        if (entries.size > MAX_ENTRIES) {
            error("Archive has too many entries (${entries.size})")
        }
        val declared = entries.filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0L) }
        val free = runCatching { StatFs(destination.absolutePath).availableBytes }
            .getOrDefault(0L)
        // Zero free space means the measurement failed, not that the volume is
        // full. Treating it as "no room" turned every extraction into a refusal
        // on any device where StatFs cannot answer for a path — including, it
        // turned out, on a plain JVM. Only a real number can veto.
        if (free > 0 && declared > free - (free / 10)) {
            error(
                "Archive needs about ${declared / (1024 * 1024)} MB but only " +
                    "${free / (1024 * 1024)} MB is free"
            )
        }
        // Compression ratio only means something once there is real payload to
        // compare against; tiny archives compress wildly without being hostile.
        val compressed = zipFile.length()
        if (compressed >= RATIO_FLOOR_BYTES && declared > compressed * MAX_RATIO) {
            error("Archive looks like a decompression bomb")
        }
    }

    private companion object {
        const val COPY_BUFFER = 128 * 1024
        const val PARTIAL_SUFFIX = ".fileflow-part"

        /** Extraction limits. See [guardAgainstBomb]. */
        const val MAX_ENTRIES = 50_000
        const val MAX_RATIO = 200L
        const val RATIO_FLOOR_BYTES = 2L * 1024 * 1024
    }
}
