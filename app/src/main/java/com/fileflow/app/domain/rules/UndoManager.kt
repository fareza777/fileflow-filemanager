package com.fileflow.app.domain.rules

import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.FileOperator
import com.fileflow.app.data.db.JournalDao
import com.fileflow.app.data.db.JournalEntity
import com.fileflow.app.data.db.RuleLogDao
import com.fileflow.app.data.db.TrashDao
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UndoResult(
    val restored: Int,
    val failed: Int,
    val skipped: Int,
    val errors: List<String>,
) {
    val isClean: Boolean get() = failed == 0
}

/**
 * Walks a journal group backwards. Reversing in reverse order matters: if a run
 * moved A into a folder that an earlier step had just created, undoing forward
 * would fight the filesystem.
 */
@Singleton
class UndoManager @Inject constructor(
    private val operator: FileOperator,
    private val journalDao: JournalDao,
    private val logDao: RuleLogDao,
    private val trashDao: TrashDao,
) {

    suspend fun undoGroup(
        groupId: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): UndoResult = withContext(Dispatchers.IO) {
        val entries = journalDao.undoableInGroup(groupId)
        var restored = 0
        var failed = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        entries.forEachIndexed { index, entry ->
            onProgress(index, entries.size)
            val outcome = reverse(entry)
            when (outcome) {
                is ReverseOutcome.Restored -> {
                    restored++
                    journalDao.markUndone(entry.id, null)
                }
                is ReverseOutcome.Skip -> {
                    skipped++
                    journalDao.markUndone(entry.id, outcome.reason)
                }
                is ReverseOutcome.Failure -> {
                    failed++
                    errors += outcome.reason
                    journalDao.markUndone(entry.id, outcome.reason)
                }
            }
        }

        // A run id is an internal detail; only undo groups that came from a run
        // need their history entry flagged.
        groupId.removePrefix("run-").toLongOrNull()?.let { logId ->
            if (failed == 0) logDao.markUndone(logId, System.currentTimeMillis())
        }

        UndoResult(restored, failed, skipped, errors.take(30))
    }

    private sealed interface ReverseOutcome {
        data object Restored : ReverseOutcome
        data class Skip(val reason: String) : ReverseOutcome
        data class Failure(val reason: String) : ReverseOutcome
    }

    private suspend fun reverse(entry: JournalEntity): ReverseOutcome {
        val kind = runCatching { com.fileflow.app.core.storage.OperationKind.valueOf(entry.kind) }
            .getOrNull() ?: return ReverseOutcome.Skip("Unknown change")

        return when (kind) {
            // A fresh copy has nothing to go back to: undo means remove it.
            com.fileflow.app.core.storage.OperationKind.COPY -> {
                val target = entry.targetPath?.let { File(it) }
                if (target == null || !target.exists()) {
                    ReverseOutcome.Skip("Already removed")
                } else if (operator.deleteRecursively(target)) {
                    ReverseOutcome.Restored
                } else {
                    ReverseOutcome.Failure("Could not remove the copy")
                }
            }

            com.fileflow.app.core.storage.OperationKind.MOVE,
            com.fileflow.app.core.storage.OperationKind.RENAME,
            -> {
                val target = entry.targetPath
                if (target == null) {
                    ReverseOutcome.Skip("Nothing to restore")
                } else {
                    operator.relocate(target, entry.sourcePath, ConflictPolicy.RENAME).fold(
                        onSuccess = { ReverseOutcome.Restored },
                        onFailure = { ReverseOutcome.Failure(it.message ?: "Could not move it back") },
                    )
                }
            }

            com.fileflow.app.core.storage.OperationKind.TRASH -> {
                val trashPath = entry.targetPath
                if (trashPath == null) {
                    ReverseOutcome.Skip("Nothing to restore")
                } else {
                    operator.restoreFromTrash(trashPath, entry.sourcePath).fold(
                        onSuccess = {
                            trashDao.byTrashPath(trashPath)?.let { trashDao.deleteByIds(listOf(it.id)) }
                            ReverseOutcome.Restored
                        },
                        onFailure = { ReverseOutcome.Failure(it.message ?: "Could not restore") },
                    )
                }
            }

            com.fileflow.app.core.storage.OperationKind.DELETE ->
                ReverseOutcome.Skip("Permanently deleted")

            // Cleaning metadata writes a new file and leaves the original alone,
            // so there is nothing to put back — and the copy it made is the
            // answer, not a mistake to undo.
            com.fileflow.app.core.storage.OperationKind.CLEAN ->
                ReverseOutcome.Skip("Nothing was changed")

            com.fileflow.app.core.storage.OperationKind.CREATE_FOLDER -> {
                val folder = File(entry.sourcePath)
                if (folder.exists() && folder.listFiles()?.isEmpty() == true) {
                    if (folder.delete()) ReverseOutcome.Restored
                    else ReverseOutcome.Failure("Could not remove the folder")
                } else {
                    ReverseOutcome.Skip("Folder is no longer empty")
                }
            }

            // An archive is a new artifact, not a moved file: there is nothing
            // to put back, and removing it is the user's call.
            com.fileflow.app.core.storage.OperationKind.ARCHIVE ->
                ReverseOutcome.Skip("Archive created")

            // A restore is never journaled — undoing "put it back" would mean
            // re-trashing a file the user deliberately rescued.
            com.fileflow.app.core.storage.OperationKind.RESTORE ->
                ReverseOutcome.Skip("Restore cannot be undone")
        }
    }

    /** Used by the "undo last run" affordance after a rule finishes. */
    suspend fun undoLatestRun(): UndoResult? {
        val latest = logDao.latest() ?: return null
        return undoGroup("run-${latest.id}")
    }
}
