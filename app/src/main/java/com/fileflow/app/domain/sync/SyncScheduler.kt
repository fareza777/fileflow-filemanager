package com.fileflow.app.domain.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps one periodic worker per saved folder pair.
 *
 * A periodic request rather than the one-shot trick the rules use: a sync has no
 * meaningful wall-clock time — "after midnight" and "at 02:14" are the same
 * intent — and WorkManager's batching is exactly what should decide when a
 * background copy runs. The interval comes from the job, and the work is keyed
 * by job id so editing a job replaces its schedule instead of adding a second.
 *
 * Charging is not required. A sync is the one background job in this app whose
 * whole purpose is to happen before the user next plugs the phone in, and a
 * schedule that only runs on the charger is a schedule the user will think is
 * broken.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SyncJobStore,
) {

    /** Arms one job, or disarms it when it is switched off. */
    suspend fun sync(job: SyncJob) = withContext(Dispatchers.IO) {
        if (!job.enabled) {
            cancel(job.id)
            return@withContext
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(job.interval.hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInputData(Data.Builder().putString(SyncWorker.KEY_JOB_ID, job.id).build())
            .addTag(TAG_SYNC)
            // The work's own name doubles as its tag, which is how a job that
            // was deleted can be found again at the next launch.
            .addTag(workName(job.id))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workName(job.id),
            // UPDATE rather than KEEP: the interval may have changed, and a job
            // whose new schedule is ignored is a bug the user cannot see.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Re-arms every saved job, without disturbing the ones already armed.
     *
     * Deliberately not "cancel everything and enqueue again": a periodic request
     * restarts its interval when it is re-created, so an app opened twice a day
     * would push the next run back every time and the schedule would never
     * fire. `UPDATE` leaves an unchanged job's next run time alone, so this is
     * safe to call on every launch.
     *
     * Work left behind by a job that was deleted while the app was dead is not
     * cleaned up here — enumerating it needs a Guava type this module does not
     * compile against — and it does not need to be: the worker looks its job up
     * by id and returns immediately when it is gone, so an orphan schedule costs
     * one no-op wake-up and nothing else.
     */
    suspend fun syncAll() = withContext(Dispatchers.IO) {
        store.all().filter { it.enabled }.forEach { sync(it) }
    }

    fun cancel(jobId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(jobId))
    }

    private fun workName(jobId: String) = "$JOB_TAG_PREFIX$jobId"

    companion object {
        const val TAG_SYNC = "fileflow_sync"
        private const val JOB_TAG_PREFIX = "fileflow_sync_"
    }
}
