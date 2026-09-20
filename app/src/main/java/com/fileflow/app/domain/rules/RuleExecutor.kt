package com.fileflow.app.domain.rules

import com.fileflow.app.core.storage.AppliedChange
import com.fileflow.app.core.storage.FileOperator
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.data.db.JournalDao
import com.fileflow.app.data.db.JournalEntity
import com.fileflow.app.data.db.RuleDao
import com.fileflow.app.data.db.RuleLogDao
import com.fileflow.app.data.db.RuleLogEntity
import com.fileflow.app.data.db.TrashEntity
import com.fileflow.app.data.db.TrashJournalDao
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ExecutionProgress(
    val processed: Int,
    val total: Int,
    val currentName: String,
    val succeeded: Int,
    val failed: Int,
)

/**
 * Applies a plan the user has already seen. Files are applied one at a time so
 * a single locked file cannot take a whole run down with it, and every
 * successful change is written to the undo journal the moment it lands — a run
 * that dies halfway through still leaves a fully reversible history.
 */
@Singleton
class RuleExecutor @Inject constructor(
    private val operator: FileOperator,
    private val ruleDao: RuleDao,
    private val logDao: RuleLogDao,
    private val journalDao: JournalDao,
    private val trashJournalDao: TrashJournalDao,
    private val volumes: VolumeRepository,
) {

    suspend fun execute(
        rule: Rule,
        operations: List<PlannedOperation>,
        trigger: RunTrigger,
        onProgress: (ExecutionProgress) -> Unit = {},
    ): RunOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val logId = logDao.insert(
            RuleLogEntity(
                ruleId = rule.id,
                ruleName = rule.name,
                startedAt = startedAt,
                status = RunStatus.RUNNING.name,
                trigger = trigger.name,
                matched = operations.size,
            )
        )
        val groupId = "run-$logId"
        val action = rule.spec.action
        val issues = mutableListOf<String>()

        var succeeded = 0
        var failed = 0
        var skipped = 0
        var bytes = 0L
        var cancelled = false
        var ranOutOfTime = false

        val deadline = startedAt + TIME_BUDGET_MILLIS

        try {
            for ((index, operation) in operations.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() > deadline) {
                    ranOutOfTime = true
                    break
                }
                onProgress(
                    ExecutionProgress(
                        processed = index,
                        total = operations.size,
                        currentName = operation.sourceName,
                        succeeded = succeeded,
                        failed = failed,
                    )
                )

                val source = File(operation.sourcePath)
                if (!source.exists()) {
                    failed++
                    issues += "${operation.sourceName}: already gone"
                    continue
                }

                when (action.kind) {
                    ActionKind.MOVE, ActionKind.COPY -> {
                        val destination = operation.destinationPath
                            ?.let { File(it).parentFile }
                        if (destination == null) {
                            failed++
                            issues += "${operation.sourceName}: no destination"
                            continue
                        }
                        val report = if (action.kind == ActionKind.MOVE) {
                            operator.move(
                                sources = listOf(source),
                                destination = destination,
                                policy = action.conflictPolicy,
                                targetName = operation.destinationName,
                            ) { applied -> record(groupId, applied) }
                        } else {
                            operator.copy(
                                sources = listOf(source),
                                destination = destination,
                                policy = action.conflictPolicy,
                                targetName = operation.destinationName,
                            ) { applied -> record(groupId, applied) }
                        }
                        succeeded += report.succeeded
                        failed += report.failed
                        skipped += report.skipped
                        bytes += report.bytesMoved
                        report.issues.forEach { issues += "${operation.sourceName}: ${it.message}" }
                    }

                    ActionKind.TRASH -> {
                        val report = operator.moveToTrash(listOf(source)) { applied ->
                            record(groupId, applied)
                        }
                        succeeded += report.succeeded
                        failed += report.failed
                        bytes += report.bytesMoved
                        report.issues.forEach { issues += "${operation.sourceName}: ${it.message}" }
                    }

                    ActionKind.DELETE -> {
                        val report = operator.deletePermanently(listOf(source)) { applied ->
                            record(groupId, applied)
                        }
                        succeeded += report.succeeded
                        failed += report.failed
                        bytes += report.bytesMoved
                        report.issues.forEach { issues += "${operation.sourceName}: ${it.message}" }
                    }

                    ActionKind.RENAME -> {
                        val newName = operation.destinationName
                        if (newName == null) {
                            failed++
                            issues += "${operation.sourceName}: no new name"
                            continue
                        }
                        operator.rename(source, newName).fold(
                            onSuccess = { applied ->
                                record(groupId, applied)
                                succeeded++
                            },
                            onFailure = { error ->
                                failed++
                                issues += "${operation.sourceName}: ${error.message}"
                            },
                        )
                    }
                }
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        val status = when {
            cancelled -> RunStatus.CANCELLED
            failed > 0 && succeeded > 0 -> RunStatus.PARTIAL
            failed > 0 -> RunStatus.FAILED
            else -> RunStatus.SUCCESS
        }

        val finishedAt = System.currentTimeMillis()
        val note = buildString {
            when {
                cancelled -> append("Stopped by the user")
                ranOutOfTime -> append("Time limit reached, continues next run")
            }
            if (skipped > 0) {
                if (isNotEmpty()) append(" · ")
                append("$skipped skipped")
            }
        }.ifBlank { null }

        logDao.finish(
            id = logId,
            finishedAt = finishedAt,
            status = status.name,
            matched = operations.size,
            succeeded = succeeded,
            failed = failed,
            bytes = bytes,
            note = note,
        )
        ruleDao.recordRun(
            id = rule.id,
            at = finishedAt,
            status = status.name,
            matched = operations.size,
        )

        RunOutcome(
            logId = logId,
            status = status,
            matched = operations.size,
            succeeded = succeeded,
            failed = failed,
            bytesMoved = bytes,
            issues = issues.take(50),
            cancelled = cancelled,
        )
    }

    /**
     * A rule that deletes leaves a file in the trash folder, so it owes the
     * user a trash row as much as a manual delete does. Without one the file
     * sits on disk, absent from the Trash screen and impossible to restore.
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

    companion object {
        private const val TIME_BUDGET_MILLIS = 8 * 60 * 1000L
    }
}
