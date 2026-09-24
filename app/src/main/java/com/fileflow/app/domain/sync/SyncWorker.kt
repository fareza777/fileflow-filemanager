package com.fileflow.app.domain.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fileflow.app.core.storage.StorageAccess
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * One saved folder pair, run because it is due.
 *
 * Everything it does goes through [SyncRunner], the same code the screen uses
 * behind its preview — so a schedule cannot do something the user would not
 * have approved on screen. The result is written back to the job, including a
 * failure: a schedule that fails silently is worse than one that does not run,
 * because the user goes on believing their folders match.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: SyncJobStore,
    private val runner: SyncRunner,
    private val storageAccess: StorageAccess,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val job = store.get(jobId) ?: return Result.success()
        if (!job.enabled) return Result.success()

        // Without storage access there is nothing to read and no reason to burn
        // a retry: the run is skipped, and the next occurrence tries again.
        if (!storageAccess.hasAccess()) return Result.success()

        return try {
            val outcome = runner.run(
                left = job.left,
                right = job.right,
                direction = job.direction,
                mode = job.mode,
                compareContent = job.compareContent,
            )
            store.recordResult(jobId, outcome)
            if (outcome.error != null && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            store.recordResult(jobId, SyncRunOutcome(error = error.message ?: "the sync failed"))
            if (error is Error) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val KEY_JOB_ID = "sync_job_id"
        private const val MAX_ATTEMPTS = 2
    }
}
