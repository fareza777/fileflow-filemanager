package com.fileflow.app.domain.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.syncStore: DataStore<Preferences> by preferencesDataStore(name = "fileflow_sync")

/**
 * The folder pairs that sync on their own.
 *
 * A preferences file like the remote connections, for the same reason: a
 * handful of records the user edits by hand, and no schema migration the next
 * time a field is added. Unlike those, this one is read from a worker, so
 * [get] exists next to the flow — a background run cannot collect a StateFlow
 * that only updates while a screen is open.
 */
@Singleton
class SyncJobStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }

    val jobs: Flow<List<SyncJob>> = context.syncStore.data.map { prefs ->
        decode(prefs[KEY_JOBS].orEmpty())
    }

    suspend fun all(): List<SyncJob> = jobs.first()

    suspend fun get(id: String): SyncJob? = all().firstOrNull { it.id == id }

    /** Adds or replaces one job, keeping the list ordered by folder pair. */
    suspend fun save(job: SyncJob) {
        val next = all().filterNot { it.id == job.id } + job
        write(next.sortedBy { it.left.lowercase() + it.right.lowercase() })
    }

    suspend fun remove(id: String) {
        write(all().filterNot { it.id == id })
    }

    /**
     * Records what a run did.
     *
     * Written even when the run failed, because "it ran and could not finish" is
     * the answer the user needs to see; a job that silently keeps its old
     * success line looks like it is working.
     */
    suspend fun recordResult(id: String, outcome: SyncRunOutcome) {
        val job = get(id) ?: return
        save(
            job.copy(
                lastRunAt = System.currentTimeMillis(),
                lastCopied = outcome.copied,
                lastDeleted = outcome.trashed,
                lastFailed = outcome.failed,
                lastConflicts = outcome.conflicts,
                lastError = outcome.error,
            )
        )
    }

    private suspend fun write(items: List<SyncJob>) {
        val encoded = json.encodeToString(SyncJobs.serializer(), SyncJobs(items))
        context.syncStore.edit { prefs -> prefs[KEY_JOBS] = encoded }
    }

    private fun decode(raw: String): List<SyncJob> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(SyncJobs.serializer(), raw).items }
            .getOrDefault(emptyList())
    }

    private companion object {
        val KEY_JOBS = stringPreferencesKey("sync_jobs")
    }
}
