package com.fileflow.app.domain.sync

import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.domain.compare.DirectoryComparer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Compares two folders and does what the plan says.
 *
 * Shared between the screen, where the user approves a plan they can read, and
 * the worker, where nobody is looking. Both paths therefore run the same code:
 * a scheduled sync cannot quietly do something the previewed one would not.
 *
 * The order is deliberately boring and deliberate: copies first, deletions
 * last. A run that stops half-way — the phone locks, the process dies — leaves
 * the target with extra files rather than missing ones, and the copies are the
 * part that was actually asked for. A file that differs on both sides is kept
 * on both sides, because without a record of which side changed the overwrite
 * is a coin flip.
 */
@Singleton
class SyncRunner @Inject constructor(
    private val comparer: DirectoryComparer,
    private val actions: FileActionsRepository,
    private val operationRunner: OperationRunner,
) {

    /** Builds the plan without touching anything. */
    suspend fun plan(
        left: String,
        right: String,
        direction: SyncDirection,
        mode: SyncMode,
        compareContent: Boolean,
        onScanned: (Int) -> Unit = {},
    ): SyncPlan {
        val comparison = comparer.compare(left, right, compareContent, onScanned)
        return SyncPlanner.plan(comparison.rows, direction, mode)
    }

    suspend fun run(
        left: String,
        right: String,
        direction: SyncDirection,
        mode: SyncMode,
        compareContent: Boolean,
        onScanned: (Int) -> Unit = {},
        onProgress: (String) -> Unit = {},
    ): SyncRunOutcome {
        val plan = runCatching {
            plan(left, right, direction, mode, compareContent, onScanned)
        }.getOrElse { error ->
            return SyncRunOutcome(error = error.message ?: "could not compare the two folders")
        }

        var copied = 0
        var trashed = 0
        var failed = 0

        try {
            val copies = buildList {
                if (plan.copyToRight.isNotEmpty()) {
                    add(plan.copyToRight.mapNotNull { it.leftPath } to right)
                }
                if (plan.copyToLeft.isNotEmpty()) {
                    add(plan.copyToLeft.mapNotNull { it.rightPath } to left)
                }
            }
            copies.forEach { (paths, destination) ->
                if (paths.isEmpty()) return@forEach
                onProgress("Copying ${paths.size} to ${destination.substringAfterLast('/')}")
                operationRunner.run(OperationKind.COPY) { progress ->
                    val report = actions.copy(paths, destination, ConflictPolicy.RENAME, progress)
                    copied += report.succeeded
                    failed += report.failed
                }
            }

            val deletions = buildList {
                if (plan.deleteOnRight.isNotEmpty()) add(plan.deleteOnRight.mapNotNull { it.rightPath })
                if (plan.deleteOnLeft.isNotEmpty()) add(plan.deleteOnLeft.mapNotNull { it.leftPath })
            }
            deletions.forEach { paths ->
                if (paths.isEmpty()) return@forEach
                onProgress("Removing ${paths.size} from the mirror")
                operationRunner.run(OperationKind.TRASH) { progress ->
                    val report = actions.delete(paths, permanent = false, progress)
                    trashed += report.succeeded
                    failed += report.failed
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return SyncRunOutcome(
                copied = copied,
                trashed = trashed,
                failed = failed,
                conflicts = plan.conflictsCount,
                error = error.message ?: "the sync stopped early",
            )
        }

        return SyncRunOutcome(
            copied = copied,
            trashed = trashed,
            failed = failed,
            conflicts = plan.conflictsCount,
        )
    }
}
