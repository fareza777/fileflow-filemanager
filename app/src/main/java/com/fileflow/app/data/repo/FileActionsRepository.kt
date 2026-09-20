package com.fileflow.app.data.repo

import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.storage.AppliedChange
import com.fileflow.app.core.storage.ArchiveEngine
import com.fileflow.app.core.storage.ArchiveFormat
import com.fileflow.app.core.storage.ArchiveProgress
import com.fileflow.app.core.storage.ArchiveResult
import com.fileflow.app.core.storage.CompressionLevel
import com.fileflow.app.core.storage.CollisionResolver
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.FileOperator
import com.fileflow.app.core.storage.MediaScanner
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationProgress
import com.fileflow.app.core.storage.OperationReport
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.data.db.JournalDao
import com.fileflow.app.data.db.JournalEntity
import com.fileflow.app.data.db.JournalGroupSummary
import com.fileflow.app.data.db.StarredFileDao
import com.fileflow.app.data.db.StarredFileEntity
import com.fileflow.app.data.db.TrashDao
import com.fileflow.app.data.db.TrashEntity
import com.fileflow.app.data.db.TrashJournalDao
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class UndoSnapshot(
    val groupId: String,
    val restored: Int,
    val failed: Int,
    val skipped: Int,
    val errors: List<String>,
)

data class RenameSummary(
    val groupId: String,
    val succeeded: Int,
    val failed: Int,
    val errors: List<String>,
)

/**
 * Every user-initiated change goes through here. Keeping the journal and the
 * trash index in one place is what makes "undo" honest: if a change happened,
 * a record of it exists.
 */
@Singleton
class FileActionsRepository @Inject constructor(
    private val operator: FileOperator,
    private val volumes: VolumeRepository,
    private val journalDao: JournalDao,
    private val trashDao: TrashDao,
    private val trashJournalDao: TrashJournalDao,
    private val mediaScanner: MediaScanner,
    private val indexRepository: FileIndexRepository,
    private val starredDao: StarredFileDao,
    private val archiveEngine: ArchiveEngine,
) {

    /** Distinguishes two operations that begin within the same millisecond. */
    private val groupCounter = AtomicLong()

    /**
     * Names that would be overwritten at [destination]. The browser asks this
     * before starting so it can offer a real choice instead of silently
     * renaming or replacing.
     */
    suspend fun findConflicts(
        sources: List<String>,
        destination: String,
    ): List<String> = withContext(Dispatchers.IO) {
        operator.conflicts(sources.map(::File), File(destination))
    }

    suspend fun copy(
        sources: List<String>,
        destination: String,
        policy: ConflictPolicy,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationReport = withContext(Dispatchers.IO) {
        val group = newGroupId()
        val report = operator.copy(
            sources = sources.map(::File),
            destination = File(destination),
            policy = policy,
            onProgress = onProgress,
            onApplied = { record(group, it) },
        )
        // The gallery and every media app read Android's index, not the disk.
        mediaScanner.announce(report.changes.mapNotNull { it.targetPath })
        syncIndex(removed = emptyList(), added = report.changes.mapNotNull { it.targetPath })
        report.copy(groupId = group)
    }

    suspend fun move(
        sources: List<String>,
        destination: String,
        policy: ConflictPolicy,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationReport = withContext(Dispatchers.IO) {
        val group = newGroupId()
        val report = operator.move(
            sources = sources.map(::File),
            destination = File(destination),
            policy = policy,
            onProgress = onProgress,
            onApplied = { record(group, it) },
        )
        mediaScanner.announce(
            report.changes.mapNotNull { it.targetPath } + report.changes.map { it.sourcePath }
        )
        syncIndex(
            removed = report.changes.map { it.sourcePath },
            added = report.changes.mapNotNull { it.targetPath },
        )
        report.copy(groupId = group)
    }

    suspend fun delete(
        sources: List<String>,
        permanent: Boolean,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationReport = withContext(Dispatchers.IO) {
        val group = newGroupId()
        val report = if (permanent) {
            operator.deletePermanently(
                files = sources.map(::File),
                onProgress = onProgress,
                onApplied = { record(group, it) },
            )
        } else {
            operator.moveToTrash(
                files = sources.map(::File),
                onProgress = onProgress,
                onApplied = { record(group, it) },
            )
        }
        // Announcing a path that no longer exists is how the index learns to
        // drop it, so the deleted file stops showing up in the gallery.
        mediaScanner.announce(sources)
        syncIndex(removed = sources, added = emptyList())
        report.copy(groupId = group)
    }

    suspend fun rename(path: String, newName: String): Result<String> = withContext(Dispatchers.IO) {
        val group = newGroupId()
        operator.rename(File(path), newName).fold(
            onSuccess = { change ->
                record(group, change)
                mediaScanner.announce(listOf(path, change.targetPath ?: path))
                val target = change.targetPath
                if (target != null) {
                    // A star follows the file rather than dying with the old path.
                    starredDao.rename(path, target, target.substringAfterLast('/'))
                    syncIndex(removed = listOf(path), added = listOf(target))
                }
                Result.success(change.targetPath ?: newName)
            },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun createFolder(parent: String, name: String): Result<String> =
        withContext(Dispatchers.IO) {
            operator.createFolder(File(parent), name).map { it.absolutePath }
        }

    /**
     * Applies a batch of renames as one undoable group, so a smart-rename run
     * of two hundred files can be walked back in one tap.
     */
    suspend fun applyRenames(
        renames: List<Pair<String, String>>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RenameSummary = withContext(Dispatchers.IO) {
        val group = newGroupId()
        var succeeded = 0
        var failed = 0
        val errors = mutableListOf<String>()

        renames.forEachIndexed { index, (path, newName) ->
            onProgress(index, renames.size)
            val file = File(path)
            if (!file.exists()) {
                failed++
                errors += "${file.name}: already gone"
                return@forEachIndexed
            }
            operator.rename(file, newName).fold(
                onSuccess = { change ->
                    record(group, change)
                    succeeded++
                },
                onFailure = { error ->
                    failed++
                    errors += "${file.name}: ${error.message}"
                },
            )
        }
        onProgress(renames.size, renames.size)

        RenameSummary(groupId = group, succeeded = succeeded, failed = failed, errors = errors.take(20))
    }

    suspend fun undoGroup(
        groupId: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): UndoSnapshot = withContext(Dispatchers.IO) {
        val entries = journalDao.undoableInGroup(groupId)
        var restored = 0
        var failed = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        entries.forEachIndexed { index, entry ->
            onProgress(index, entries.size)
            val name = entry.sourcePath.substringAfterLast('/')
            val kind = runCatching { OperationKind.valueOf(entry.kind) }.getOrNull()

            // The outcome decides what the journal records afterwards. A
            // reversal that failed has to stay reversible; one that had nothing
            // to do has to stop being offered.
            val failure: String? = when (kind) {
                OperationKind.COPY -> {
                    val target = entry.targetPath?.let(::File)
                    when {
                        target == null || !target.exists() -> {
                            skipped++
                            null
                        }
                        operator.deleteRecursively(target) -> {
                            restored++
                            null
                        }
                        else -> {
                            failed++
                            "$name: still in use".also { errors += it }
                        }
                    }
                }

                OperationKind.MOVE,
                OperationKind.RENAME,
                -> {
                    val target = entry.targetPath
                    if (target == null) {
                        skipped++
                        null
                    } else {
                        operator.relocate(target, entry.sourcePath, ConflictPolicy.RENAME).fold(
                            onSuccess = {
                                restored++
                                null
                            },
                            onFailure = {
                                failed++
                                "$name: ${it.message}".also { message -> errors += message }
                            },
                        )
                    }
                }

                OperationKind.TRASH -> {
                    val trashPath = entry.targetPath
                    if (trashPath == null) {
                        skipped++
                        null
                    } else {
                        operator.restoreFromTrash(trashPath, entry.sourcePath).fold(
                            onSuccess = {
                                trashDao.byTrashPath(trashPath)
                                    ?.let { trashDao.deleteByIds(listOf(it.id)) }
                                restored++
                                null
                            },
                            onFailure = {
                                failed++
                                "$name: ${it.message}".also { message -> errors += message }
                            },
                        )
                    }
                }

                else -> {
                    skipped++
                    null
                }
            }

            if (failure == null) {
                journalDao.markUndone(entry.id, null)
            } else {
                journalDao.markUndoFailed(entry.id, failure)
            }
        }

        UndoSnapshot(groupId, restored, failed, skipped, errors.take(20))
    }

    fun observeStarred() = starredDao.observeAll()

    /** The undo journal, grouped into the batches the user actually performed. */
    fun observeRecentChanges(): Flow<List<JournalGroupSummary>> = journalDao.observeRecentGroups()

    fun observeUndoableCount(): Flow<Int> = journalDao.observeUndoableCount()

    /** Undone entries are only kept long enough to be recognisable. */
    suspend fun pruneJournal(olderThanMillis: Long = JOURNAL_RETENTION) =
        withContext(Dispatchers.IO) {
            journalDao.prune(System.currentTimeMillis() - olderThanMillis)
        }
    fun observeStarredPaths(): Flow<List<String>> = starredDao.observePaths()

    suspend fun isStarred(path: String): Boolean = withContext(Dispatchers.IO) {
        starredDao.isStarred(path)
    }

    /**
     * Starred paths are stored as plain rows rather than a flag on an indexed
     * file, so a star survives the file moving to another folder.
     */
    suspend fun toggleStar(item: FileItem): Boolean = withContext(Dispatchers.IO) {
        val already = starredDao.isStarred(item.path)
        if (already) {
            starredDao.remove(item.path)
            false
        } else {
            starredDao.upsert(
                StarredFileEntity(
                    path = item.path,
                    name = item.name,
                    addedAt = System.currentTimeMillis(),
                    sizeBytes = item.sizeBytes,
                    modified = item.lastModified,
                )
            )
            true
        }
    }

    suspend fun lastGroupId(): String? = journalDao.latestRenameGroup()

    // ------------------------------------------------------------- archives

    suspend fun compress(
        sources: List<String>,
        destination: String,
        format: ArchiveFormat = ArchiveFormat.ZIP,
        level: CompressionLevel = CompressionLevel.NORMAL,
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Result<ArchiveResult> = withContext(Dispatchers.IO) {
        archiveEngine.compress(sources, File(destination), format, level, onProgress)
            .onSuccess { result ->
                result.destination?.let { syncIndex(removed = emptyList(), added = listOf(it)) }
            }
    }

    suspend fun extract(
        archivePath: String,
        destinationDirectory: String,
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Result<ArchiveResult> = withContext(Dispatchers.IO) {
        archiveEngine.extract(File(archivePath), File(destinationDirectory), onProgress)
            .onSuccess { result ->
                // Every extracted file is new to the index and, if it is media,
                // to the gallery as well.
                val destination = result.destination ?: return@onSuccess
                val added = File(destination).walkTopDown()
                    .filter { it.isFile }
                    .map { it.absolutePath }
                    .take(MAX_INDEX_SYNC)
                    .toList()
                syncIndex(removed = emptyList(), added = added)
                mediaScanner.announce(added)
            }
    }

    /** A free name for "archive.zip", "archive (2).zip" and so on. */
    suspend fun suggestArchivePath(
        directory: String,
        baseName: String,
        format: ArchiveFormat = ArchiveFormat.ZIP,
    ): String = withContext(Dispatchers.IO) {
        CollisionResolver.unique(File(directory), "$baseName.${format.extension}")
            .let { "$directory/$it" }
    }

    /**
     * Every successful mutation is written to the journal. A mutation that
     * leaves a file sitting in the trash also needs a trash row, and both rows
     * are written in one transaction: a deletion the user cannot see is a
     * deletion the user cannot undo either.
     */
    private suspend fun record(groupId: String, change: AppliedChange) {
        val journal = JournalEntity(
            groupId = groupId,
            kind = change.kind.name,
            sourcePath = change.sourcePath,
            targetPath = change.targetPath,
            sizeBytes = change.sizeBytes,
            isDirectory = change.isDirectory,
            createdAt = System.currentTimeMillis(),
        )
        val trashPath = change.targetPath
        if (change.kind != OperationKind.TRASH || trashPath == null) {
            journalDao.insert(journal)
            return
        }
        trashJournalDao.recordTrashChange(
            journal,
            TrashEntity(
                originalPath = change.sourcePath,
                trashPath = trashPath,
                name = change.sourcePath.substringAfterLast('/'),
                sizeBytes = change.sizeBytes,
                isDirectory = change.isDirectory,
                deletedAt = System.currentTimeMillis(),
                volumeRoot = volumes.volumeRootOf(change.sourcePath),
            ),
        )
    }

    /**
     * A timestamp alone collides: two operations started in the same
     * millisecond shared a group id, so one tap of "Undo" reversed changes made
     * by two different actions.
     */
    private fun newGroupId(): String =
        "op-${System.currentTimeMillis()}-${groupCounter.incrementAndGet()}"

    /**
     * Keeps the search index in step with what just happened, so a file the
     * user moved is findable in its new place immediately rather than after the
     * next background rebuild.
     */
    private suspend fun syncIndex(removed: List<String>, added: List<String>) {
        if (removed.isNotEmpty()) indexRepository.removePaths(removed)
        if (added.isNotEmpty()) indexRepository.upsertPaths(added)
    }

    private companion object {
        /** Extracting a 40k-file archive should not stall the UI to index it. */
        const val MAX_INDEX_SYNC = 2000
        const val JOURNAL_RETENTION = 7 * 86_400_000L
    }
}
