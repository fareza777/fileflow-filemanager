package com.fileflow.app.domain.rules

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fileflow.app.core.storage.StorageAccess
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.data.db.RuleDao
import com.fileflow.app.data.prefs.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList

/**
 * Runs every rule that is due, in a single pass over storage.
 *
 * The worker is still enqueued per rule — that is how each rule keeps its own
 * exact wall-clock schedule — but the first one to fire does the work for all
 * of them and the rest find nothing left to do. Five nightly rules therefore
 * cost one walk of the device instead of five.
 */
@HiltWorker
class RuleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val ruleDao: RuleDao,
    private val engine: RuleEngine,
    private val executor: RuleExecutor,
    private val volumes: VolumeRepository,
    private val scheduler: RuleScheduler,
    private val notifier: RuleNotifier,
    private val storageAccess: StorageAccess,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ruleId = inputData.getLong(KEY_RULE_ID, -1L)
        if (ruleId <= 0) return Result.failure()

        if (!storageAccess.hasAccess()) return Result.success()

        val now = System.currentTimeMillis()
        val all = ruleDao.enabled().map(RuleJson::toRule).filter { it.spec.isRunnable }
        val due = all.filter { DueWindow.isDue(it, now) }

        if (due.isEmpty()) {
            // Another worker in this batch already ran them.
            scheduler.scheduleNext(ruleId)
            return Result.success()
        }

        return try {
            runBatch(due, now)
            due.forEach { scheduler.scheduleNext(it.id) }
            Result.success()
        } catch (cancelled: CancellationException) {
            // WorkManager cancels by throwing this into the coroutine. Swallowing
            // it below meant a stopped worker kept moving files and then reported
            // success or retry for work it was told to abandon.
            throw cancelled
        } catch (error: Throwable) {
            // The next occurrence is armed either way, so a transient failure
            // does not silently kill the rule — but a second attempt is worth
            // it when the device was simply busy. An Error (out of memory and
            // friends) is not transient in any sense a retry can fix.
            due.forEach { scheduler.scheduleNext(it.id) }
            if (error is Error) return Result.failure()
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    private suspend fun runBatch(rules: List<Rule>, now: Long) {
        val roots = volumes.roots()

        // Collect the plan for every due rule from one traversal, capped per
        // rule so a runaway pattern cannot flood memory.
        val matches = engine.planAll(rules, roots, now).toList()
        val byRule = matches.groupBy({ it.ruleId }, { it.operation })

        val notify = settingsRepository.settings.first().notifyRuleRuns

        rules.forEach { rule ->
            val operations = byRule[rule.id].orEmpty()
            val outcome = executor.execute(rule, operations, RunTrigger.SCHEDULED)

            if (notify && rule.spec.notify && (outcome.succeeded > 0 || outcome.failed > 0)) {
                notifier.notifyRunFinished(
                    ruleName = rule.name,
                    succeeded = outcome.succeeded,
                    failed = outcome.failed,
                    bytesMoved = outcome.bytesMoved,
                )
            }
        }
    }

    companion object {
        const val KEY_RULE_ID = "rule_id"
        private const val MAX_ATTEMPTS = 2
    }
}
