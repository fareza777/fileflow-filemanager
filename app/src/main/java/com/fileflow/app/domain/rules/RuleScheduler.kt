package com.fileflow.app.domain.rules

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fileflow.app.data.db.RuleDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One pending worker per rule. A one-shot request with an exact initial delay
 * beats periodic work here: it can actually fire at 21:00 instead of whenever
 * the batching window happens to open, and each rule carries its own
 * constraints.
 */
@Singleton
class RuleScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ruleDao: RuleDao,
) {

    suspend fun syncAll() = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        // Rules can be deleted while their worker is still queued, so the
        // whole tag is cleared before the surviving rules are re-armed.
        manager.cancelAllWorkByTag(TAG_RULES)
        ruleDao.all().forEach { entity ->
            val spec = RuleJson.decode(entity.specJson)
            if (entity.enabled && spec.schedule.isScheduled) {
                enqueue(entity.id, spec.schedule)
            }
        }
    }

    suspend fun sync(ruleId: Long) = withContext(Dispatchers.IO) {
        val entity = ruleDao.byId(ruleId)
        if (entity == null || !entity.enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(ruleId))
            return@withContext
        }
        val spec = RuleJson.decode(entity.specJson)
        if (!spec.schedule.isScheduled) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(ruleId))
            return@withContext
        }
        enqueue(ruleId, spec.schedule)
    }

    suspend fun scheduleNext(ruleId: Long) = withContext(Dispatchers.IO) {
        val entity = ruleDao.byId(ruleId) ?: return@withContext
        if (!entity.enabled) return@withContext
        val spec = RuleJson.decode(entity.specJson)
        if (!spec.schedule.isScheduled) return@withContext
        enqueue(ruleId, spec.schedule)
    }

    fun cancel(ruleId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(ruleId))
    }

    private fun enqueue(ruleId: Long, schedule: ScheduleSpec) {
        val delay = ScheduleCalculator.delayUntilNextRun(schedule) ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (schedule.wifiOnly) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED
            )
            .setRequiresCharging(schedule.chargingOnly)
            .build()

        val request = OneTimeWorkRequestBuilder<RuleWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInputData(Data.Builder().putLong(RuleWorker.KEY_RULE_ID, ruleId).build())
            .addTag(TAG_RULES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(ruleId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun workName(ruleId: Long) = "fileflow_rule_$ruleId"

    companion object {
        const val TAG_RULES = "fileflow_rules"
    }
}
