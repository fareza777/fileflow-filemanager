package com.fileflow.app.domain.rules

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure schedule arithmetic, kept separate from WorkManager so "daily at 21:00"
 * can be tested without a device. WorkManager only guarantees a window, so the
 * worker re-arms itself for the next exact occurrence after every run.
 */
object ScheduleCalculator {

    fun nextRunAt(
        schedule: ScheduleSpec,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        if (!schedule.isScheduled) return null
        val current = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val candidate = when (schedule.kind) {
            ScheduleKind.OFF -> return null
            ScheduleKind.HOURLY -> current
                .plusHours(schedule.intervalHours.coerceAtLeast(1).toLong())
                .withMinute(0)
                .withSecond(0)
                .withNano(0)

            ScheduleKind.DAILY -> {
                val today = current
                    .withHour(schedule.hour.coerceIn(0, 23))
                    .withMinute(schedule.minute.coerceIn(0, 59))
                    .withSecond(0)
                    .withNano(0)
                if (today.isAfter(current)) today else today.plusDays(1)
            }

            ScheduleKind.WEEKLY -> nextWeekly(current, schedule)
        }
        return candidate.toInstant().toEpochMilli()
    }

    private fun nextWeekly(current: ZonedDateTime, schedule: ScheduleSpec): ZonedDateTime {
        val wanted = schedule.daysOfWeek
            .filter { it in 1..7 }
            .map { DayOfWeek.of(it) }
            .toSet()
            .ifEmpty { DayOfWeek.entries.toSet() }

        var day = current
            .withHour(schedule.hour.coerceIn(0, 23))
            .withMinute(schedule.minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)

        // Eight iterations cover a full week plus the "today already passed" case.
        repeat(8) {
            if (day.dayOfWeek in wanted && day.isAfter(current)) return day
            day = day.plusDays(1)
        }
        return day
    }

    fun delayUntilNextRun(
        schedule: ScheduleSpec,
        now: Long = System.currentTimeMillis(),
    ): Long? {
        val next = nextRunAt(schedule, now) ?: return null
        return (next - now).coerceAtLeast(0L)
    }

    fun describe(schedule: ScheduleSpec): String = when (schedule.kind) {
        ScheduleKind.OFF -> "Manual only"
        ScheduleKind.HOURLY -> "Every ${schedule.intervalHours}h"
        ScheduleKind.DAILY -> "Daily at ${format(schedule)}"
        ScheduleKind.WEEKLY -> schedule.daysOfWeek.joinToString(", ") {
            DayOfWeek.of(it.coerceIn(1, 7)).name.take(3)
                .lowercase()
                .replaceFirstChar(Char::uppercase)
        } + " at ${format(schedule)}"
    }

    private fun format(schedule: ScheduleSpec): String =
        "%02d:%02d".format(schedule.hour.coerceIn(0, 23), schedule.minute.coerceIn(0, 59))
}
