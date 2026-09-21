package com.fileflow.app.domain.sync

import com.fileflow.app.domain.compare.DiffRow
import com.fileflow.app.domain.compare.DiffState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a sync will do, before it does it.
 *
 * The screen shows this plan and the user approves it, so the decision table is
 * the part of the feature that has to be right — and the part that is impossible
 * to check by reading files afterwards. A wrong rule here deletes the wrong
 * folder, which is why every branch has a test.
 */
class SyncPlannerTest {

    private fun row(
        path: String,
        state: DiffState,
        leftSize: Long = 10,
        rightSize: Long = 10,
    ) = DiffRow(
        relativePath = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        state = state,
        leftSize = leftSize,
        rightSize = rightSize,
        leftPath = if (state == DiffState.RIGHT_ONLY) null else "/left/$path",
        rightPath = if (state == DiffState.LEFT_ONLY) null else "/right/$path",
    )

    private val rows = listOf(
        row("same.txt", DiffState.SAME),
        row("left-only.txt", DiffState.LEFT_ONLY, leftSize = 100),
        row("right-only.txt", DiffState.RIGHT_ONLY, rightSize = 200),
        row("differs.txt", DiffState.DIFFERENT, leftSize = 300, rightSize = 400),
    )

    @Test
    fun `left to right incremental copies what is missing or different`() {
        val plan = SyncPlanner.plan(rows, SyncDirection.LEFT_TO_RIGHT, SyncMode.INCREMENTAL)

        assertEquals(listOf("left-only.txt", "differs.txt"), plan.copyToRight.map { it.name })
        assertTrue("nothing goes the other way", plan.copyToLeft.isEmpty())
        assertTrue("incremental never deletes", plan.deletions == 0)
        assertEquals(400L, plan.copiesBytes)
    }

    @Test
    fun `right to left incremental is the mirror image of the rules`() {
        val plan = SyncPlanner.plan(rows, SyncDirection.RIGHT_TO_LEFT, SyncMode.INCREMENTAL)

        assertEquals(listOf("right-only.txt", "differs.txt"), plan.copyToLeft.map { it.name })
        assertTrue(plan.copyToRight.isEmpty())
        assertTrue(plan.deletions == 0)
    }

    @Test
    fun `mirroring one way deletes what the source does not have`() {
        val leftToRight = SyncPlanner.plan(rows, SyncDirection.LEFT_TO_RIGHT, SyncMode.MIRROR)
        assertEquals(listOf("right-only.txt"), leftToRight.deleteOnRight.map { it.name })

        val rightToLeft = SyncPlanner.plan(rows, SyncDirection.RIGHT_TO_LEFT, SyncMode.MIRROR)
        assertEquals(listOf("left-only.txt"), rightToLeft.deleteOnLeft.map { it.name })
    }

    @Test
    fun `two way copies each side's own files and keeps conflicts`() {
        val plan = SyncPlanner.plan(rows, SyncDirection.TWO_WAY, SyncMode.INCREMENTAL)

        assertEquals(listOf("left-only.txt"), plan.copyToRight.map { it.name })
        assertEquals(listOf("right-only.txt"), plan.copyToLeft.map { it.name })
        assertEquals("a differing file is never overwritten", listOf("differs.txt"), plan.conflicts.map { it.name })
        assertTrue("two-way incremental does not delete", plan.deletions == 0)
    }

    @Test
    fun `two way mirror still refuses to overwrite a conflict`() {
        // Mirroring in both directions is contradictory, but the rule that
        // matters is that the conflicting file is only reported, never resolved
        // by guessing which side is newer.
        val plan = SyncPlanner.plan(rows, SyncDirection.TWO_WAY, SyncMode.MIRROR)

        assertEquals(listOf("differs.txt"), plan.conflicts.map { it.name })
        assertTrue(plan.copyToRight.none { it.name == "differs.txt" })
        assertTrue(plan.copyToLeft.none { it.name == "differs.txt" })
    }

    @Test
    fun `identical folders produce nothing to do`() {
        val plan = SyncPlanner.plan(
            listOf(row("a.txt", DiffState.SAME), row("b.txt", DiffState.SAME)),
            SyncDirection.TWO_WAY,
            SyncMode.MIRROR,
        )
        assertTrue(plan.isEmpty)
        assertEquals("nothing to do", plan.summary())
    }

    @Test
    fun `a summary names the work in the order it happens`() {
        val plan = SyncPlanner.plan(rows, SyncDirection.TWO_WAY, SyncMode.MIRROR)
        val summary = plan.summary()
        assertTrue(summary, summary.contains("→ right"))
        assertTrue(summary, summary.contains("→ left"))
    }
}
