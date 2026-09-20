package com.fileflow.app.domain.rename

import com.fileflow.app.core.storage.StorageAccess
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.data.prefs.RenameConvention
import com.fileflow.app.domain.FileWalker
import com.fileflow.app.domain.WalkOptions
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext

data class RenameProposal(
    val path: String,
    val folder: String,
    val currentName: String,
    val proposedName: String,
    val reasons: Set<RenameReason>,
    val confidence: Confidence,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * Finds files whose names are working against the person using them, and
 * proposes names that are. This is deliberately not automatic: every proposal
 * is shown with its reason, and nothing is renamed until the user says so.
 */
@Singleton
class SmartRenameEngine @Inject constructor(
    private val walker: FileWalker,
    private val volumes: VolumeRepository,
    private val storageAccess: StorageAccess,
) {

    suspend fun scan(
        roots: List<String> = volumes.roots(),
        convention: RenameConvention = RenameConvention.KEEP,
        datePrefix: Boolean = false,
        minConfidence: Confidence = Confidence.LOW,
        onProgress: (scanned: Int, found: Int) -> Unit = { _, _ -> },
    ): List<RenameProposal> = withContext(Dispatchers.IO) {
        val raw = mutableListOf<RenameProposal>()
        var scanned = 0

        walker.walkFiles(roots, WalkOptions(recursive = true, skipHidden = true))
            // A suggestion list is something to review and act on, not an
            // archive. Without a ceiling, a phone full of camera files built one
            // object per improvable name before the first row was even shown.
            .takeWhile { raw.size < MAX_PROPOSALS }
            .collect { candidate ->
                scanned++
                if (scanned % 100 == 0) onProgress(scanned, raw.size)
                if (storageAccess.isRestrictedPath(candidate.path)) return@collect
                if (candidate.extension in SKIP_EXTENSIONS) return@collect

                val header = readHeader(candidate.toFile())
                val suggestion = NameHeuristics.suggest(
                    candidate = candidate,
                    convention = convention,
                    datePrefix = datePrefix,
                    header = header,
                ) ?: return@collect

                val proposed = NameHeuristics.assemble(suggestion)
                if (proposed == candidate.name) return@collect
                if (proposed.length > MAX_NAME_LENGTH) return@collect
                if (suggestion.confidence.ordinal > minConfidence.ordinal) return@collect

                raw += RenameProposal(
                    path = candidate.path,
                    folder = candidate.path.substringBeforeLast('/'),
                    currentName = candidate.name,
                    proposedName = proposed,
                    reasons = suggestion.reasons,
                    confidence = suggestion.confidence,
                    sizeBytes = candidate.sizeBytes,
                    lastModified = candidate.lastModified,
                )
            }
        onProgress(scanned, raw.size)

        resolveCollisions(raw).sortedWith(
            compareByDescending<RenameProposal> { it.confidence.ordinal }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.currentName }
        )
    }

    /**
     * Two files can be improved into the same name — "IMG_20230101_120000"
     * and "20230101_120000" both become the same stamp. Within one folder the
     * later one gets a counter, and anything already sitting on disk wins.
     */
    private suspend fun resolveCollisions(
        proposals: List<RenameProposal>,
    ): List<RenameProposal> {
        val result = mutableListOf<RenameProposal>()
        proposals.groupBy { it.folder }.forEach { (folder, items) ->
            val claimed = HashSet<String>()
            items.forEach { proposal ->
                currentCoroutineContext().ensureActive()
                val wanted = proposal.proposedName
                val occupied = File(folder, wanted).exists()
                val selfOccupied = File(folder, wanted).absolutePath == proposal.path
                val free = claimed.add(wanted.lowercase())

                if ((occupied && !selfOccupied) || !free) {
                    val alternative = uniquify(folder, wanted, claimed)
                    result += proposal.copy(
                        proposedName = alternative,
                        reasons = proposal.reasons + RenameReason.UNIQUENESS,
                    )
                } else {
                    result += proposal
                }
            }
        }
        return result
    }

    private fun uniquify(folder: String, name: String, claimed: MutableSet<String>): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (index < 500) {
            val candidate = "$base ($index)$extension"
            val taken = File(folder, candidate).exists() || !claimed.add(candidate.lowercase())
            if (!taken) return candidate
            index++
        }
        return "$base-${System.currentTimeMillis()}$extension"
    }

    /**
     * One small read per file buys the extension repair, which is the single
     * most valuable fix the tool offers — a PNG pretending to be TXT is
     * invisible to every app on the device.
     */
    private fun readHeader(file: File): ByteArray? = runCatching {
        if (!file.isFile || file.length() == 0L) return null
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HEADER_BYTES)
            val read = input.read(buffer)
            if (read <= 0) null else buffer.copyOf(read)
        }
    }.getOrNull()

    private companion object {
        const val HEADER_BYTES = 512
        const val MAX_NAME_LENGTH = 180

        /** Nobody reviews a list longer than this; the walk stops once it is full. */
        const val MAX_PROPOSALS = 500

        /** Formats where a rename would break the file's own tooling. */
        val SKIP_EXTENSIONS = setOf("so", "dex", "odex", "vdex", "art", "oat", "nomedia")
    }
}
