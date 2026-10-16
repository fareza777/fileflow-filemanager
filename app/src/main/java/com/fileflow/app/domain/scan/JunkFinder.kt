package com.fileflow.app.domain.scan

import com.fileflow.app.core.storage.StorageAccess
import com.fileflow.app.core.storage.TrashStore
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.FileWalker
import com.fileflow.app.domain.WalkOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

enum class JunkKind { EMPTY_FILES, EMPTY_FOLDERS, TEMP, LOGS, OLD_APK, THUMBNAILS }

data class JunkBucket(
    val kind: JunkKind,
    val items: List<Candidate>,
    val bytes: Long,
)

data class JunkReport(val buckets: List<JunkBucket>) {
    val totalBytes: Long get() = buckets.sumOf { it.bytes }
    val totalCount: Int get() = buckets.sumOf { it.items.size }
    val isEmpty: Boolean get() = totalCount == 0

    /** Everything the cleaner would remove if the user accepted it all. */
    fun allItems(): List<Candidate> = buckets.flatMap { it.items }
}

/**
 * Junk is defined conservatively: only things that are unambiguously
 * disposable. Nothing here guesses at "unused" — a zero-byte file is far more
 * often a failed download than something someone needs.
 */
@Singleton
class JunkFinder @Inject constructor(
    private val walker: FileWalker,
    private val volumes: VolumeRepository,
    private val storageAccess: StorageAccess,
    private val trash: TrashStore,
    private val gate: StorageWalkGate,
) {

    suspend fun scan(
        roots: List<String> = volumes.roots(),
        onProgress: (scanned: Int) -> Unit = {},
    ): JunkReport = gate.run {
        withContext(Dispatchers.IO) {
            val buckets = HashMap<JunkKind, MutableList<Candidate>>()
            val emptyFolders = mutableListOf<Candidate>()
            var scanned = 0

            walker.walkFiles(
                roots,
                WalkOptions(recursive = true, skipHidden = false, includeDirectories = false),
                // Empty folders come out of the listing the walk performs
                // anyway. Asking for them separately meant a second traversal of
                // the whole device purely to find folders with nothing in them.
                onEmptyDirectory = { directory ->
                    if (!storageAccess.isRestrictedPath(directory.path) &&
                        !trash.isTrashPath(directory.path)
                    ) {
                        emptyFolders += directory
                    }
                },
            ).collect { candidate ->
                scanned++
                if (scanned % 300 == 0) onProgress(scanned)
                // Android/data and obb stay untouchable even for a cleaner: the
                // system hides them because touching them breaks other apps.
                if (storageAccess.isRestrictedPath(candidate.path)) return@collect
                // The trash holds everything this tool just removed. It is
                // walked without the hidden-file skip (junk lives in dot-folders
                // like .thumbnails), so without this line every clean moved the
                // junk into .fileflow/trash and the next scan reported the very
                // same files again — "deleted, and back".
                if (trash.isTrashPath(candidate.path)) return@collect
                JunkRules.classify(candidate)?.let { kind ->
                    buckets.getOrPut(kind) { mutableListOf() }.add(candidate)
                }
            }
            onProgress(scanned)

            if (emptyFolders.isNotEmpty()) buckets[JunkKind.EMPTY_FOLDERS] = emptyFolders

            JunkReport(
                buckets = buckets.entries
                    .map { (kind, items) ->
                        JunkBucket(
                            kind = kind,
                            items = items.sortedByDescending { it.sizeBytes }.take(MAX_PER_BUCKET),
                            bytes = items.sumOf { it.sizeBytes },
                        )
                    }
                    .sortedByDescending { it.bytes }
            )
        }
    }

    private companion object {
        /**
         * A bucket is a list to scroll and a set to act on, not an archive. A
         * phone with 40 000 stray thumbnails should not turn into 40 000
         * objects held in memory to render the first screenful.
         */
        const val MAX_PER_BUCKET = 500
    }
}
