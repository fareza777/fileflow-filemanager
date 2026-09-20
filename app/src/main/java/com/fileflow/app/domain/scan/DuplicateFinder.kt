package com.fileflow.app.domain.scan

import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.FileWalker
import com.fileflow.app.domain.WalkOptions
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class DuplicateGroup(
    val sizeBytes: Long,
    val files: List<Candidate>,
) {
    val wastedBytes: Long get() = sizeBytes * (files.size - 1).coerceAtLeast(0)
}

/**
 * Duplicate detection in three widening passes: size, then a prefix hash, then
 * a full hash. The ordering matters on a phone — a full read of every file
 * would take minutes, while size-plus-prefix eliminates almost everything
 * after reading a few hundred kilobytes per file.
 */
@Singleton
class DuplicateFinder @Inject constructor(
    private val walker: FileWalker,
    private val volumes: VolumeRepository,
    private val gate: StorageWalkGate,
) {

    suspend fun find(
        roots: List<String> = volumes.roots(),
        minSizeBytes: Long = 32 * 1024,
        onProgress: (scanned: Int) -> Unit = {},
    ): List<DuplicateGroup> = gate.run { findLocked(roots, minSizeBytes, onProgress) }

    /**
     * Only files at or above the threshold are ever held: a phone's worth of
     * tiny files is discarded at the walk, which is what keeps this bounded
     * without a cap that would hide real duplicates.
     */
    private suspend fun findLocked(
        roots: List<String>,
        minSizeBytes: Long,
        onProgress: (scanned: Int) -> Unit,
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val bySize = HashMap<Long, MutableList<Candidate>>()
        var scanned = 0

        walker.walkFiles(roots, WalkOptions(recursive = true, skipHidden = true))
            .collect { candidate ->
                scanned++
                if (candidate.sizeBytes < minSizeBytes) return@collect
                bySize.getOrPut(candidate.sizeBytes) { mutableListOf() }.add(candidate)
                if (scanned % 200 == 0) onProgress(scanned)
            }
        onProgress(scanned)

        val sizeCandidates = bySize.filterValues { it.size > 1 }.values.flatten()
        if (sizeCandidates.isEmpty()) return@withContext emptyList()

        // Hashing is pure CPU once the file is open, so it parallelises well —
        // but only a few at a time: letting eight threads fight over a phone's
        // flash storage is slower than four, not faster.
        val narrowed = mutableListOf<Candidate>()
        val prefixHashes = HashMap<String, MutableList<Candidate>>()
        forEachBounded(sizeCandidates) { candidate ->
            val prefix = hash(candidate.toFile(), PREFIX_BYTES) ?: return@forEachBounded
            synchronized(prefixHashes) {
                prefixHashes.getOrPut("${candidate.sizeBytes}:$prefix") { mutableListOf() }
                    .add(candidate)
            }
        }
        prefixHashes.filterValues { it.size > 1 }.values.forEach { narrowed += it }

        if (narrowed.isEmpty()) return@withContext emptyList()

        val confirmed = HashMap<String, MutableList<Candidate>>()
        forEachBounded(narrowed) { candidate ->
            val full = hash(candidate.toFile(), Long.MAX_VALUE) ?: return@forEachBounded
            synchronized(confirmed) {
                confirmed.getOrPut("${candidate.sizeBytes}:$full") { mutableListOf() }
                    .add(candidate)
            }
        }

        confirmed.values
            .filter { it.size > 1 }
            .map { files ->
                val sorted = files.sortedBy { it.lastModified }
                DuplicateGroup(sizeBytes = sorted.first().sizeBytes, files = sorted)
            }
            .sortedByDescending { it.wastedBytes }
    }

    /**
     * Runs [block] over [items] with a fixed number of workers, splitting the
     * work across cores without letting one job per file hammer the storage
     * controller at once.
     *
     * Each item re-checks for cancellation. Awaiting the jobs only observes
     * their own cancellation, so without this, leaving the duplicates screen
     * kept the entire device being read to the end — minutes of disk work for a
     * result nobody was waiting for.
     */
    private suspend fun <T> forEachBounded(
        items: List<T>,
        workers: Int = HASH_WORKERS,
        block: (T) -> Unit,
    ) = coroutineScope {
        val gate = Semaphore(workers)
        items.map { item ->
            async(Dispatchers.Default) {
                gate.withPermit {
                    currentCoroutineContext().ensureActive()
                    block(item)
                }
            }
        }.awaitAll()
    }

    private fun hash(file: File, limit: Long): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER)
            var remaining = limit
            while (remaining > 0) {
                val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, wanted)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private companion object {
        const val PREFIX_BYTES = 256L * 1024
        const val HASH_BUFFER = 64 * 1024
        const val HASH_WORKERS = 4
    }
}
