package com.fileflow.app.domain.timeline

import android.content.Context
import android.provider.MediaStore
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.core.util.MediaStoreItems
import com.fileflow.app.domain.FileWalker
import com.fileflow.app.domain.WalkOptions
import com.fileflow.app.domain.toFileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

enum class TimelineSource { INDEX, DEEP_SCAN }

/**
 * Everything that arrived on one calendar month, bucketed by day.
 *
 * The media index answers this instantly because it is already sorted by date,
 * which is the difference between a calendar that opens and one that spins.
 * A full filesystem scan is offered separately for the files the index does not
 * know about.
 */
@androidx.compose.runtime.Immutable
data class MonthData(
    val year: Int,
    val month: Int,
    val filesByDay: Map<Int, List<FileItem>> = emptyMap(),
    val source: TimelineSource = TimelineSource.INDEX,
) {
    /** Counts are read once per calendar cell, so they are not recomputed there. */
    private val countsByDay: Map<Int, Int> = filesByDay.mapValues { it.value.size }

    val totalFiles: Int get() = countsByDay.values.sumOf { it }
    val totalBytes: Long get() = filesByDay.values.sumOf { files -> files.sumOf { it.sizeBytes } }
    val busiestDay: Int get() = countsByDay.values.maxOrNull() ?: 0
    val isBusyMonth: Boolean get() = countsByDay.isNotEmpty()

    fun countOn(day: Int): Int = countsByDay[day] ?: 0

    fun day(day: Int): List<FileItem> = filesByDay[day] ?: emptyList()
}

@Singleton
class TimelineRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walker: FileWalker,
    private val volumes: VolumeRepository,
) {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun month(year: Int, month: Int): MonthData = withContext(Dispatchers.IO) {
        val bounds = monthBounds(year, month)
        val byDay = LinkedHashMap<Int, MutableList<FileItem>>()

        runCatching {
            val selection = "${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ? AND " +
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} < ?"
            val args = arrayOf(
                (bounds.first / 1000).toString(),
                (bounds.second / 1000).toString(),
            )

            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                MediaStoreItems.PROJECTION,
                selection,
                args,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} ASC",
            )?.use { cursor ->
                val roots = volumes.volumes().map { it.rootPath }
                val lastDay = YearMonth.of(year, month).lengthOfMonth()

                while (cursor.moveToNext()) {
                    val item = MediaStoreItems.from(cursor, roots) ?: continue
                    if (!MediaStoreItems.isUserFile(item.path)) continue
                    val day = dayOf(item.lastModified) ?: continue
                    if (day !in 1..lastDay) continue
                    byDay.getOrPut(day) { mutableListOf() } += item
                }
            }
        }.onFailure {
            // A calendar that opens empty is worse than one that says why: this
            // is the line that distinguishes "no files that month" from "the
            // query itself failed on this device".
            android.util.Log.w("FileFlow", "calendar MediaStore query failed", it)
        }

        MonthData(
            year = year,
            month = month,
            filesByDay = byDay.mapValues { (_, files) ->
                files.sortedByDescending { it.lastModified }
            },
            source = TimelineSource.INDEX,
        )
    }

    /**
     * Walks the filesystem for the month. Slower, but it also finds files the
     * media scanner never indexed.
     */
    suspend fun deepScan(
        year: Int,
        month: Int,
        onProgress: (scanned: Int, matched: Int) -> Unit = { _, _ -> },
    ): MonthData = withContext(Dispatchers.IO) {
        val bounds = monthBounds(year, month)
        val byDay = LinkedHashMap<Int, MutableList<FileItem>>()
        var scanned = 0
        var matched = 0

        walker.walkFiles(
            volumes.roots(),
            WalkOptions(recursive = true, skipHidden = true),
        ).collect { candidate ->
            scanned++
            if (scanned % 400 == 0) onProgress(scanned, matched)
            if (candidate.lastModified < bounds.first || candidate.lastModified >= bounds.second) {
                return@collect
            }
            val day = dayOf(candidate.lastModified) ?: return@collect
            if (day !in 1..YearMonth.of(year, month).lengthOfMonth()) return@collect
            matched++
            byDay.getOrPut(day) { mutableListOf() } += candidate.toFileItem()
        }
        onProgress(scanned, matched)

        MonthData(
            year = year,
            month = month,
            filesByDay = byDay.mapValues { (_, files) ->
                files.sortedByDescending { it.lastModified }
            },
            source = TimelineSource.DEEP_SCAN,
        )
    }

    private fun monthBounds(year: Int, month: Int): Pair<Long, Long> {
        val start = LocalDate.of(year, month, 1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val end = LocalDate.of(year, month, 1)
            .plusMonths(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        return start to end
    }

    private fun dayOf(epochMillis: Long): Int? {
        if (epochMillis <= 0L) return null
        return runCatching {
            java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).dayOfMonth
        }.getOrNull()
    }
}
