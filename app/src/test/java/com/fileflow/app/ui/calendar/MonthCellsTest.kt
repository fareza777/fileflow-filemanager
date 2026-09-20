package com.fileflow.app.ui.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthCellsTest {

    @Test
    fun `monday first puts the first of may in the first column`() {
        // 1 May 2024 is a Wednesday.
        val cells = monthCells(2024, 5, DayOfWeek.MONDAY)
        assertEquals(2, cells.take(2).count { it == null })
        assertEquals(1, cells[2])
        assertEquals(31, cells[2 + 30])
    }

    @Test
    fun `sunday first shifts the grid by one for the same month`() {
        val cells = monthCells(2024, 5, DayOfWeek.SUNDAY)
        assertEquals(3, cells.take(3).count { it == null })
        assertEquals(1, cells[3])
    }

    @Test
    fun `a month starting on the first day of the week has no leading gap`() {
        // 1 July 2024 is a Monday.
        val cells = monthCells(2024, 7, DayOfWeek.MONDAY)
        assertEquals(1, cells.first())
    }

    @Test
    fun `the grid always contains whole weeks`() {
        (1..12).forEach { month ->
            val cells = monthCells(2026, month, DayOfWeek.MONDAY)
            assertEquals("month $month", 0, cells.size % 7)
        }
    }

    @Test
    fun `every day of the month appears exactly once`() {
        val cells = monthCells(2024, 2, DayOfWeek.MONDAY)
        val days = cells.filterNotNull()
        assertEquals(29, days.size)
        assertEquals((1..29).toList(), days)
    }

    @Test
    fun `trailing blanks pad the last week`() {
        val cells = monthCells(2025, 3, DayOfWeek.MONDAY)
        assertTrue(cells.last() != null || cells.takeLast(7).any { it != null })
        assertEquals(0, cells.size % 7)
    }

    @Test
    fun `a future day is distinguishable from a past one`() {
        val today = LocalDate.now()
        val nextMonth = today.plusMonths(1)
        val cells = monthCells(nextMonth.year, nextMonth.monthValue, DayOfWeek.MONDAY)
        assertTrue(cells.filterNotNull().isNotEmpty())
        assertNull(cells.getOrNull(-1))
    }
}
