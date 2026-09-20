package com.fileflow.app.data.repo

import com.fileflow.app.core.storage.FileOperator
import com.fileflow.app.core.storage.TrashStore
import com.fileflow.app.data.db.TrashDao
import com.fileflow.app.data.db.TrashEntity
import com.fileflow.app.data.prefs.SettingsRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class RestoreSummary(val restored: Int, val failed: Int, val errors: List<String>)

data class DeleteSummary(val deleted: Int, val failed: Int, val errors: List<String>)

/**
 * The trash is what makes "delete" a safe verb. Entries live in the database
 * for the UI, but the truth is the file on disk: anything whose file has
 * vanished is dropped from the index on the next pass rather than being shown
 * as restorable.
 */
@Singleton
class TrashRepository @Inject constructor(
    private val trashDao: TrashDao,
    private val operator: FileOperator,
    private val trashStore: TrashStore,
    private val settings: SettingsRepository,
) {

    fun observeItems(): Flow<List<TrashEntity>> = trashDao.observeAll()

    fun observeCount(): Flow<Int> = trashDao.observeCount()

    fun observeSize(): Flow<Long> = trashDao.observeTotalSize()

    suspend fun restore(
        ids: List<Long>,
        onProgress: ((done: Int, total: Int, name: String) -> Unit)? = null,
    ): RestoreSummary = withContext(Dispatchers.IO) {
        val entries = trashDao.byIds(ids)
        var restored = 0
        var failed = 0
        val errors = mutableListOf<String>()

        entries.forEachIndexed { index, entry ->
            onProgress?.invoke(index, entries.size, entry.name)
            operator.restoreFromTrash(entry.trashPath, entry.originalPath).fold(
                onSuccess = {
                    restored++
                    trashDao.deleteByIds(listOf(entry.id))
                },
                onFailure = { error ->
                    failed++
                    errors += "${entry.name}: ${error.message}"
                },
            )
        }
        RestoreSummary(restored, failed, errors.take(20))
    }

    suspend fun deleteForever(
        ids: List<Long>,
        onProgress: ((done: Int, total: Int, name: String) -> Unit)? = null,
    ): DeleteSummary = withContext(Dispatchers.IO) {
        clear(trashDao.byIds(ids), onProgress)
    }

    suspend fun emptyAll(
        onProgress: ((done: Int, total: Int, name: String) -> Unit)? = null,
    ): DeleteSummary = withContext(Dispatchers.IO) {
        val summary = clear(trashDao.all(), onProgress)
        // The dated folders themselves are cheap to recreate and look like
        // clutter if they linger.
        trashStore.purgeOlderThan(Long.MAX_VALUE)
        summary
    }

    /**
     * Only the entries whose file actually went away are dropped from the
     * index. Clearing the row regardless is how a file ends up still on disk,
     * invisible in the app, and impossible to reach again.
     */
    private suspend fun clear(
        entries: List<TrashEntity>,
        onProgress: ((done: Int, total: Int, name: String) -> Unit)? = null,
    ): DeleteSummary {
        var deleted = 0
        var failed = 0
        val errors = mutableListOf<String>()
        val gone = mutableListOf<Long>()

        entries.forEachIndexed { index, entry ->
            onProgress?.invoke(index, entries.size, entry.name)
            if (operator.deleteRecursively(File(entry.trashPath))) {
                deleted++
                gone += entry.id
            } else {
                failed++
                errors += "${entry.name}: the system refused it"
            }
        }

        if (gone.isNotEmpty()) trashDao.deleteByIds(gone)
        return DeleteSummary(deleted, failed, errors.take(20))
    }

    /**
     * Called on every cold start. Two jobs in one pass: drop entries whose file
     * is gone, and honour the retention window the user picked.
     */
    suspend fun purgeExpired() = withContext(Dispatchers.IO) {
        val retentionDays = settings.settings.first().trashRetentionDays
        val entries = trashDao.all()
        val cutoff = System.currentTimeMillis() - retentionDays * DAY_MILLIS
        val expired = mutableListOf<Long>()

        entries.forEach { entry ->
            val file = File(entry.trashPath)
            when {
                !file.exists() -> expired += entry.id
                retentionDays in 1..365 && entry.deletedAt < cutoff -> {
                    if (operator.deleteRecursively(file)) expired += entry.id
                }
            }
        }

        if (expired.isNotEmpty()) {
            trashDao.deleteByIds(expired)
        }
        trashStore.purgeOlderThan(cutoff)
    }

    companion object {
        private const val DAY_MILLIS = 86_400_000L
    }
}
