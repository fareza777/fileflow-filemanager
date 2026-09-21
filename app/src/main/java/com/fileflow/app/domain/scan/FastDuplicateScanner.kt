package com.fileflow.app.domain.scan

import com.fileflow.app.core.analysis.FastHash
import com.fileflow.app.core.storage.StorageAccess
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.FileWalker
import com.fileflow.app.domain.WalkOptions
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Files that a sampled fingerprint says might be copies of each other. */
@androidx.compose.runtime.Immutable
data class FastDuplicateGroup(
    val fingerprint: String,
    val files: List<Candidate>,
) {
    val wastedBytes: Long get() = files.drop(1).sumOf { it.sizeBytes }
    val suspected: Boolean get() = files.size > 1
}

/**
 * The cheap half of duplicate finding.
 *
 * Hashing every byte of every file is correct and slow: on a phone with a
 * hundred GB of media it is minutes of reading, most of it spent on files that
 * could not possibly be copies because they are not even the same size. This
 * pass samples instead — size plus the head, the middle and the tail — which is
 * imohash's trick, and it answers "is this worth looking at closely" in
 * microseconds per file.
 *
 * What it returns is *candidates*, and the UI says so: two files that share a
 * fingerprint of their first and last 16 KB are almost certainly identical, and
 * the full hash confirms it before anything is deleted.
 */
@Singleton
class FastDuplicateScanner @Inject constructor(
    private val walker: FileWalker,
    private val volumes: VolumeRepository,
    private val storageAccess: StorageAccess,
) {

    suspend fun scan(
        roots: List<String> = volumes.roots(),
        minSizeBytes: Long = DEFAULT_MIN_SIZE,
        onProgress: (scanned: Int, candidates: Int) -> Unit = { _, _ -> },
    ): List<FastDuplicateGroup> = withContext(Dispatchers.IO) {
        val buckets = HashMap<String, MutableList<Candidate>>()
        var scanned = 0
        var considered = 0

        try {
            walker.walkFiles(
                roots,
                WalkOptions(recursive = true, skipHidden = true),
            ).collect { candidate ->
                currentCoroutineContext().ensureActive()
                scanned++
                if (scanned % 200 == 0) onProgress(scanned, considered)
                if (storageAccess.isRestrictedPath(candidate.path)) return@collect
                // Zero-byte files are all "identical" and are the junk
                // cleaner's business, not this one's.
                if (candidate.sizeBytes < minSizeBytes) return@collect
                considered++
                val file = File(candidate.path)
                val hash = runCatching { FastHash.fastHash(file) }.getOrNull() ?: return@collect
                buckets.getOrPut(hash) { mutableListOf() } += candidate
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        }

        onProgress(scanned, considered)
        buckets
            .filterValues { it.size > 1 }
            .map { (hash, files) -> FastDuplicateGroup(hash, files) }
            .sortedByDescending { it.wastedBytes }
    }

    /** Confirms a group by reading every byte, and narrows it to true copies. */
    suspend fun confirm(group: FastDuplicateGroup): List<Candidate> = withContext(Dispatchers.IO) {
        val buckets = HashMap<String, MutableList<Candidate>>()
        group.files.forEach { candidate ->
            currentCoroutineContext().ensureActive()
            val file = File(candidate.path)
            val hash = runCatching { FastHash.fullHash(file) }.getOrNull() ?: return@forEach
            buckets.getOrPut(hash) { mutableListOf() } += candidate
        }
        buckets.values.filter { it.size > 1 }.flatten()
    }

    private companion object {
        /** Below this the sample is most of the file anyway. */
        const val DEFAULT_MIN_SIZE = 128L * 1024
    }
}
