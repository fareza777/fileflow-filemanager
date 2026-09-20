package com.fileflow.app.domain.scan

import com.fileflow.app.core.storage.StorageAccess
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.core.util.Paths
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.FileWalker
import com.fileflow.app.domain.WalkOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

data class QuickInsights(
    val scannedFiles: Int = 0,
    val totalBytes: Long = 0,
    /** A sample, largest first, capped for memory. Not a count — see [largeCount]. */
    val largeFiles: List<Candidate> = emptyList(),
    /**
     * The true number of large files, uncapped. The tile on the home screen
     * used to print `largeFiles.size`, which is the sample ceiling: a device
     * with 350 large files said "200" and the neighbouring junk tile, counted
     * honestly, made the two inconsistent in the same row.
     */
    val largeCount: Int = 0,
    val largeTotalBytes: Long = 0,
    val junkBytes: Long = 0,
    val junkCount: Int = 0,
    val duplicatesKnown: Boolean = false,
) {
    val largeBytes: Long get() = largeFiles.sumOf { it.sizeBytes }
}

/**
 * The dashboard needs three numbers, and walking the filesystem is the most
 * expensive thing this app does — so it happens once and produces all of them.
 */
@Singleton
class InsightsScanner @Inject constructor(
    private val walker: FileWalker,
    private val volumes: VolumeRepository,
    private val storageAccess: StorageAccess,
    private val gate: StorageWalkGate,
) {

    /**
     * The folders that hold almost everything a person would recognise as
     * "my files". Scanning these first puts a real number on screen in a
     * fraction of the time a full sweep takes.
     */
    fun hotRoots(): List<String> {
        val root = volumes.primaryRoot()
        return listOf(
            "Download",
            "Documents",
            "Pictures",
            "DCIM",
            "Movies",
            "Music",
            "Android/media/com.whatsapp/WhatsApp/Media",
            "Telegram",
            "Bluetooth",
        ).map { java.io.File(root, it).absolutePath }
            .filter { java.io.File(it).isDirectory }
    }

    /**
     * Hot folders first, then the whole volume. The order is the whole trick:
     * one walk yields an early, useful number for the folders people actually
     * use, and a complete one when it finishes — rather than two sweeps, the
     * second of which re-read every directory the first already read.
     *
     * The walker de-duplicates directories across roots, so listing a hot
     * folder before the volume that contains it does not read it twice.
     */
    fun walkRoots(): List<String> {
        val primary = volumes.primaryRoot()
        val others = volumes.roots().filter { it != primary }
        return (hotRoots() + primary + others).distinct()
    }

    suspend fun scan(
        roots: List<String> = volumes.roots(),
        largeThreshold: Long = 100L * 1024 * 1024,
        hotRoots: List<String> = emptyList(),
        onProgress: (scanned: Int) -> Unit = {},
        onHotResult: (QuickInsights) -> Unit = {},
    ): QuickInsights = gate.run {
        withContext(Dispatchers.IO) {
            val large = mutableListOf<Candidate>()
            var largeCount = 0
            var largeTotalBytes = 0L
            var junkBytes = 0L
            var junkCount = 0
            var totalBytes = 0L
            var scanned = 0
            var hotOpen = hotRoots.isNotEmpty()
            var hotFiles = 0
            var hotBytes = 0L
            var hotLargeCount = 0
            var hotLargeBytes = 0L

            walker.walkFiles(roots, WalkOptions(recursive = true, skipHidden = true))
                .collect { candidate ->
                    scanned++
                    totalBytes += candidate.sizeBytes
                    if (scanned % 250 == 0) onProgress(scanned)

                    val hot = hotOpen && hotRoots.any { Paths.isInside(candidate.path, it) }
                    if (hotOpen && !hot) {
                        // The hot folders are behind us; hand the caller a usable
                        // number now and keep going for the complete one.
                        hotOpen = false
                        if (hotFiles > 0) {
                            onHotResult(
                                QuickInsights(
                                    scannedFiles = hotFiles,
                                    totalBytes = hotBytes,
                                    largeFiles = large
                                        .filter { l -> hotRoots.any { Paths.isInside(l.path, it) } }
                                        .sortedByDescending { it.sizeBytes },
                                    largeCount = hotLargeCount,
                                    largeTotalBytes = hotLargeBytes,
                                    junkBytes = junkBytes,
                                    junkCount = junkCount,
                                )
                            )
                        }
                    }
                    if (hot) {
                        hotFiles++
                        hotBytes += candidate.sizeBytes
                    }

                    if (storageAccess.isRestrictedPath(candidate.path)) return@collect

                    if (candidate.sizeBytes >= largeThreshold) {
                        // Counted in full, but only the largest MAX_LARGE_FILES
                        // are actually kept: a device full of video could put
                        // tens of thousands of paths in memory for a list the
                        // dashboard renders ten of. The smallest retained entry
                        // is evicted, so the sample stays the true top-K.
                        largeCount++
                        largeTotalBytes += candidate.sizeBytes
                        if (hot) {
                            hotLargeCount++
                            hotLargeBytes += candidate.sizeBytes
                        }
                        if (large.size < MAX_LARGE_FILES ||
                            candidate.sizeBytes > (large.minByOrNull { it.sizeBytes }?.sizeBytes ?: 0L)
                        ) {
                            large += candidate
                            if (large.size > MAX_LARGE_FILES) {
                                large.minByOrNull { it.sizeBytes }?.let { large.remove(it) }
                            }
                        }
                    }

                    when (JunkRules.classify(candidate)) {
                        null -> Unit
                        JunkKind.EMPTY_FILES -> junkCount++
                        else -> {
                            junkCount++
                            junkBytes += candidate.sizeBytes
                        }
                    }
                }
            onProgress(scanned)

            QuickInsights(
                scannedFiles = scanned,
                totalBytes = totalBytes,
                largeFiles = large.sortedByDescending { it.sizeBytes },
                largeCount = largeCount,
                largeTotalBytes = largeTotalBytes,
                junkBytes = junkBytes,
                junkCount = junkCount,
            )
        }
    }

    private companion object {
        /**
         * The dashboard shows a handful; keeping every large file on a device
         * full of video is how a "quick" scan turns into tens of megabytes of
         * strings held for a list nobody scrolls to the end of.
         */
        const val MAX_LARGE_FILES = 200
    }
}
