package com.fileflow.app.domain.versions

import android.content.Context
import com.fileflow.app.core.analysis.Chunker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a version is on disk: the metadata and the ordered list of chunk hashes
 * that make it up. Serialised as JSON so a version file stays readable, and
 * parsed with kotlinx.serialization rather than org.json, which is an Android
 * class that does not exist in a JVM test.
 */
@Serializable
internal data class VersionManifest(
    val id: String,
    val path: String,
    val name: String,
    val at: Long,
    val size: Long,
    val stored: Long,
    val chunks: Int,
    val order: List<String>,
)

/** One stored version of a file. */
@androidx.compose.runtime.Immutable
data class FileVersion(
    val id: String,
    val at: Long,
    val sizeBytes: Long,
    /** Bytes this version added to the store, after chunk de-duplication. */
    val storedBytes: Long,
    val chunks: Int,
)

/** The store's size on disk, and what it saved. */
data class VersionStoreStats(
    val versions: Int,
    val files: Int,
    val storedBytes: Long,
    val logicalBytes: Long,
) {
    /** What the same versions would cost as plain copies. */
    val savedBytes: Long get() = (logicalBytes - storedBytes).coerceAtLeast(0L)
}

/**
 * A time machine that stores differences rather than copies.
 *
 * The naive version of "keep versions of this file" is to copy it every time,
 * which turns a document edited twenty times into twenty copies. This keeps one
 * copy of each *chunk* instead: the file is cut with the content-defined
 * chunker, the chunks are stored once under their own hash, and a version is a
 * list of hashes. Editing a paragraph in a 40 MB document then costs the chunks
 * the paragraph touches, not the document — which is the whole reason xdelta and
 * its relatives exist.
 *
 * The store lives in the app's private directory, so versions survive the
 * original being edited, moved or deleted, and are removed with the app.
 */
@Singleton
class VersionStore internal constructor(
    private val root: File,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "versions"))

    private val json = Json { ignoreUnknownKeys = true }

    private val chunks: File get() = File(root, "chunks").apply { mkdirs() }
    private val manifests: File get() = File(root, "manifests").apply { mkdirs() }

    /** True when this file is worth versioning: not enormous, not a directory. */
    fun canVersion(file: File): Boolean =
        file.isFile && file.length() > 0L && file.length() <= MAX_FILE_BYTES

    /**
     * Stores the current contents as a new version.
     *
     * @return the version, or null when the file is unchanged since the last
     * one — snapshotting something that did not change would fill the store
     * with identical manifests.
     */
    suspend fun snapshot(
        file: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): FileVersion? = withContext(Dispatchers.IO) {
        if (!canVersion(file)) return@withContext null
        val parts = Chunker.split(file)
        if (parts.isEmpty()) return@withContext null

        val key = keyFor(file.absolutePath)
        val existing = versionsOf(key)
        if (existing.isNotEmpty()) {
            val latest = readManifest(existing.first().id)
            if (latest == parts.map { it.hash }) return@withContext null
        }

        var stored = 0L
        parts.forEachIndexed { index, chunk ->
            currentCoroutineContext().ensureActive()
            onProgress(index, parts.size)
            val target = File(chunks, chunk.hash)
            if (target.exists() && target.length() == chunk.length.toLong()) return@forEachIndexed
            copyChunk(file, chunk.offset, chunk.length, target)
            stored += chunk.length
        }

        val id = "${System.currentTimeMillis()}-${file.name.hashCode().toUInt().toString(16)}"
        val manifest = VersionManifest(
            id = id,
            path = file.absolutePath,
            name = file.name,
            at = System.currentTimeMillis(),
            size = file.length(),
            stored = stored,
            chunks = parts.size,
            order = parts.map { it.hash },
        )
        writeManifest(key, manifest)
        onProgress(parts.size, parts.size)

        FileVersion(
            id = id,
            at = manifest.at,
            sizeBytes = manifest.size,
            storedBytes = stored,
            chunks = parts.size,
        )
    }

    /** Every version of [file], newest first. */
    suspend fun versions(file: File): List<FileVersion> = withContext(Dispatchers.IO) {
        versionsOf(keyFor(file.absolutePath))
    }

    /**
     * Writes one version back over a destination. The bytes are read from the
     * store, so a version survives the original being deleted.
     */
    suspend fun restore(
        file: File,
        versionId: String,
        destination: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val order = readManifest(versionId) ?: error("That version is gone")
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.fileflow-part")

        RandomAccessFile(partial, "rw").use { output ->
            output.setLength(0)
            order.forEachIndexed { index, hash ->
                currentCoroutineContext().ensureActive()
                onProgress(index, order.size)
                val chunk = File(chunks, hash)
                check(chunk.exists()) { "A chunk of that version is missing" }
                chunk.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        if (!partial.renameTo(destination)) {
            partial.copyTo(destination, overwrite = true)
            partial.delete()
        }
        onProgress(order.size, order.size)
        destination
    }

    suspend fun deleteVersion(file: File, versionId: String) = withContext(Dispatchers.IO) {
        val key = keyFor(file.absolutePath)
        File(File(manifests, key), "$versionId.json").delete()
        prune()
    }

    /** How much the store holds, and how much that saved. */
    suspend fun stats(): VersionStoreStats = withContext(Dispatchers.IO) {
        val files = manifests.listFiles()?.sumOf { folder ->
            folder.listFiles()?.size ?: 0
        } ?: 0
        val stored = chunks.listFiles()?.sumOf { it.length() } ?: 0L
        var logical = 0L
        var versions = 0
        manifests.listFiles()?.forEach { folder ->
            folder.listFiles()?.forEach { manifest ->
                runCatching {
                    val parsed = json.decodeFromString(VersionManifest.serializer(), manifest.readText())
                    logical += parsed.size
                    versions++
                }
            }
        }
        VersionStoreStats(versions = versions, files = files, storedBytes = stored, logicalBytes = logical)
    }

    /** Drops the oldest versions past [keepPerFile] and any orphaned chunks. */
    suspend fun prune(keepPerFile: Int = MAX_VERSIONS_PER_FILE) = withContext(Dispatchers.IO) {
        manifests.listFiles()?.forEach { folder ->
            val sorted = folder.listFiles()?.sortedByDescending { it.name }.orEmpty()
            sorted.drop(keepPerFile).forEach { runCatching { it.delete() } }
        }
        val referenced = HashSet<String>()
        manifests.listFiles()?.forEach { folder ->
            folder.listFiles()?.forEach { manifest ->
                readOrder(manifest)?.forEach { referenced += it }
            }
        }
        chunks.listFiles()?.forEach { chunk ->
            if (chunk.name !in referenced) runCatching { chunk.delete() }
        }
    }

    // ------------------------------------------------------------- internals

    private fun versionsOf(key: String): List<FileVersion> =
        File(manifests, key).listFiles()
            ?.sortedByDescending { it.name }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val manifest = json.decodeFromString(VersionManifest.serializer(), file.readText())
                    FileVersion(
                        id = manifest.id,
                        at = manifest.at,
                        sizeBytes = manifest.size,
                        storedBytes = manifest.stored,
                        chunks = manifest.chunks,
                    )
                }.getOrNull()
            }

    private fun writeManifest(key: String, manifest: VersionManifest) {
        val folder = File(manifests, key).apply { mkdirs() }
        File(folder, "${manifest.id}.json").writeText(json.encodeToString(VersionManifest.serializer(), manifest))
    }

    private fun readManifest(versionId: String): List<String>? {
        val manifest = manifests.listFiles()
            ?.firstNotNullOfOrNull { folder ->
                folder.listFiles()?.firstOrNull { it.name == "$versionId.json" }
            } ?: return null
        return runCatching {
            json.decodeFromString(VersionManifest.serializer(), manifest.readText()).order
        }.getOrNull()
    }

    private fun readOrder(manifest: File): List<String>? = runCatching {
        json.decodeFromString(VersionManifest.serializer(), manifest.readText()).order
    }.getOrNull()

    private fun copyChunk(source: File, offset: Long, length: Int, target: File) {
        val partial = File(target.parentFile, "${target.name}.part")
        RandomAccessFile(source, "r").use { input ->
            input.seek(offset)
            partial.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    }

    /** A folder name that is stable for a path and safe on every filesystem. */
    private fun keyFor(path: String): String =
        "${path.hashCode().toUInt().toString(16)}-${File(path).name.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")}"

    private companion object {
        const val COPY_BUFFER = 128 * 1024
        const val MAX_FILE_BYTES = 512L * 1024 * 1024
        const val MAX_VERSIONS_PER_FILE = 20
    }
}
