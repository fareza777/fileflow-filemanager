package com.fileflow.app.domain.scan

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fileflow.app.core.storage.StorageAccess
import com.fileflow.app.data.repo.FileIndexRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rebuilds the file index while the device is idle and charging.
 *
 * Indexing is the one job in the app that reads every file's metadata, so it
 * belongs on the terms the system sets for background work rather than on the
 * user's terms while they are trying to browse.
 */
@HiltWorker
class FileIndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val indexRepository: FileIndexRepository,
    private val storageAccess: StorageAccess,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!storageAccess.hasAccess()) return Result.success()
        return runCatching { indexRepository.rebuild() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        const val UNIQUE_NAME = "fileflow_index"
    }
}

@Singleton
class FileIndexScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun ensureScheduled() {
        // Charging, but not idle: WorkManager can starve a job that needs both
        // for a long time on a phone that is rarely left alone while plugged in,
        // and an index that never rebuilds is a search that misses files. Being
        // plugged in is the part worth keeping — a full walk of storage should
        // not run on battery.
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .build()
        val request = PeriodicWorkRequestBuilder<FileIndexWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                FileIndexWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
