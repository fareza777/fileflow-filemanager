package com.fileflow.app.domain.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DueWindowTest {

    private val now = 1_700_000_000_000L

    private fun rule(
        lastRunAt: Long?,
        schedule: ScheduleSpec,
        enabled: Boolean = true,
    ) = Rule(
        id = 1,
        name = "r",
        enabled = enabled,
        spec = RuleSpec(
            conditions = listOf(MatchCondition.Extension(listOf("pdf"))),
            schedule = schedule,
        ),
        createdAt = 0,
        updatedAt = 0,
        lastRunAt = lastRunAt,
        lastRunStatus = null,
        lastMatchedCount = 0,
    )

    @Test
    fun `a rule that has never run is due`() {
        val schedule = ScheduleSpec(kind = ScheduleKind.DAILY, hour = 21)
        assertTrue(DueWindow.isDue(rule(null, schedule), now))
    }

    @Test
    fun `a manual rule is never due on its own`() {
        val schedule = ScheduleSpec(kind = ScheduleKind.OFF)
        assertFalse(DueWindow.isDue(rule(null, schedule), now))
    }

    @Test
    fun `a disabled rule is never due`() {
        val schedule = ScheduleSpec(kind = ScheduleKind.DAILY, hour = 21)
        assertFalse(DueWindow.isDue(rule(null, schedule, enabled = false), now))
    }

    @Test
    fun `a daily rule is not due twice in one evening`() {
        val schedule = ScheduleSpec(kind = ScheduleKind.DAILY, hour = 21)
        // Ran ten minutes ago when a second worker fired.
        assertFalse(DueWindow.isDue(rule(now - 10 * 60_000L, schedule), now))
    }

    @Test
    fun `a daily rule is due again the next day`() {
        val schedule = ScheduleSpec(kind = ScheduleKind.DAILY, hour = 21)
        assertTrue(DueWindow.isDue(rule(now - 23 * 3_600_000L, schedule), now))
    }

    @Test
    fun `an hourly rule becomes due within the hour`() {
        val schedule = ScheduleSpec(kind = ScheduleKind.HOURLY, intervalHours = 1)
        assertFalse(DueWindow.isDue(rule(now - 20 * 60_000L, schedule), now))
        assertTrue(DueWindow.isDue(rule(now - 56 * 60_000L, schedule), now))
    }

    @Test
    fun `a weekly rule waits most of the week`() {
        val schedule = ScheduleSpec(kind = ScheduleKind.WEEKLY, hour = 20, daysOfWeek = listOf(1))
        assertFalse(DueWindow.isDue(rule(now - 2 * 86_400_000L, schedule), now))
        assertTrue(DueWindow.isDue(rule(now - 7 * 86_400_000L, schedule), now))
    }
}
