package com.fileflow.app.domain.rules

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleCalculatorTest {

    private val zone = ZoneId.of("UTC")

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
        .toInstant()
        .toEpochMilli()

    private fun localOf(millis: Long): ZonedDateTime =
        ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone)

    @Test
    fun `an unscheduled rule has no next run`() {
        assertNull(ScheduleCalculator.nextRunAt(ScheduleSpec(kind = ScheduleKind.OFF), at(2024, 5, 1, 9, 0), zone))
    }

    @Test
    fun `daily before the target time runs later the same day`() {
        val schedule = ScheduleSpec(kind = ScheduleKind.DAILY, hour = 21, minute = 30)
        val next = ScheduleCalculator.nextRunAt(schedule, at(2024, 5, 1, 9, 0), zone)
        assertNotNull(next)
        val local = localOf(next!!)
        assertEquals(1, local.dayOfMonth)
        assertEquals(21, local.hour)
        assertEquals(30, local.minute)
    }

    @Test
    fun `daily after the target time rolls to tomorrow`() {
        val schedule = ScheduleSpec(kind = ScheduleKind.DAILY, hour = 21, minute = 30)
        val next = ScheduleCalculator.nextRunAt(schedule, at(2024, 5, 1, 22, 0), zone)!!
        val local = localOf(next)
        assertEquals(2, local.dayOfMonth)
        assertEquals(21, local.hour)
    }

    @Test
    fun `weekly picks the next matching weekday`() {
        // 2024-05-01 is a Wednesday; ask for Monday only.
        val schedule = ScheduleSpec(
            kind = ScheduleKind.WEEKLY,
            hour = 20,
            minute = 0,
            daysOfWeek = listOf(1),
        )
        val next = ScheduleCalculator.nextRunAt(schedule, at(2024, 5, 1, 9, 0), zone)!!
        val local = localOf(next)
        assertEquals(java.time.DayOfWeek.MONDAY, local.dayOfWeek)
        assertEquals(6, local.dayOfMonth)
        assertEquals(20, local.hour)
    }

    @Test
    fun `weekly on the same day but an earlier time still runs today`() {
        // 2024-05-01 is a Wednesday, day number 3.
        val schedule = ScheduleSpec(
            kind = ScheduleKind.WEEKLY,
            hour = 22,
            minute = 0,
            daysOfWeek = listOf(3),
        )
        val next = ScheduleCalculator.nextRunAt(schedule, at(2024, 5, 1, 9, 0), zone)!!
        val local = localOf(next)
        assertEquals(1, local.dayOfMonth)
        assertEquals(22, local.hour)
    }

    @Test
    fun `hourly rounds up to the next whole hour`() {
        val schedule = ScheduleSpec(kind = ScheduleKind.HOURLY, intervalHours = 3)
        val next = ScheduleCalculator.nextRunAt(schedule, at(2024, 5, 1, 9, 15), zone)!!
        val local = localOf(next)
        assertEquals(12, local.hour)
        assertEquals(0, local.minute)
    }

    @Test
    fun `an empty weekday list behaves like every day`() {
        val schedule = ScheduleSpec(
            kind = ScheduleKind.WEEKLY,
            hour = 23,
            minute = 0,
            daysOfWeek = emptyList(),
        )
        val next = ScheduleCalculator.nextRunAt(schedule, at(2024, 5, 1, 9, 0), zone)
        assertNotNull(next)
    }

    @Test
    fun `delay is never negative`() {
        val schedule = ScheduleSpec(kind = ScheduleKind.DAILY, hour = 0, minute = 0)
        val delay = ScheduleCalculator.delayUntilNextRun(schedule, at(2024, 5, 1, 12, 0))!!
        assert(delay > 0)
    }
}
