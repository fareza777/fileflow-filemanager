package com.fileflow.app.domain.sync

import com.fileflow.app.ui.util.SelectionHandoff
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

/**
 * What a saved sync promises: it remembers the direction and the mode, it can
 * be read back by the worker with nothing lost, and a hand-off carries a
 * selection exactly once.
 */
class SyncJobTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun job(
        direction: SyncDirection = SyncDirection.TWO_WAY,
        mode: SyncMode = SyncMode.MIRROR,
        interval: SyncInterval = SyncInterval.DAILY,
    ) = SyncJob(
        id = "sync-1",
        left = "/storage/emulated/0/Documents",
        right = "/storage/emulated/0/Backup",
        direction = direction,
        mode = mode,
        interval = interval,
        compareContent = true,
    )

    @Test
    fun `a saved job survives the trip through storage`() {
        val original = job().copy(
            lastRunAt = 1_700_000_000_000L,
            lastCopied = 12,
            lastDeleted = 3,
            lastConflicts = 2,
            lastFailed = 1,
        )
        val encoded = json.encodeToString(SyncJobs.serializer(), SyncJobs(listOf(original)))
        val decoded = json.decodeFromString(SyncJobs.serializer(), encoded).items.single()

        // Every field a worker acts on has to come back unchanged: a schedule
        // that forgets the direction would copy the wrong way at 3 a.m.
        assertEquals(original, decoded)
        assertEquals(SyncDirection.TWO_WAY, decoded.direction)
        assertEquals(SyncMode.MIRROR, decoded.mode)
        assertEquals(SyncInterval.DAILY, decoded.interval)
        assertTrue(decoded.compareContent)
        assertEquals(12, decoded.lastCopied)
    }

    @Test
    fun `an unknown field does not lose the jobs around it`() {
        // The same leniency the connections store has: a file written by a
        // newer build must not wipe the schedule in an older one.
        val withExtra = """
            {"items":[{"id":"a","left":"/l","right":"/r","direction":"LEFT_TO_RIGHT",
            "mode":"INCREMENTAL","interval":"WEEKLY","enabled":true,"somethingNew":42}]}
        """.trimIndent()
        val decoded = json.decodeFromString(SyncJobs.serializer(), withExtra).items.single()
        assertEquals("a", decoded.id)
        assertEquals(SyncInterval.WEEKLY, decoded.interval)
    }

    @Test
    fun `an empty store decodes to no jobs`() {
        assertTrue(json.decodeFromString(SyncJobs.serializer(), "{}").items.isEmpty())
    }

    @Test
    fun `intervals are long enough for periodic work`() {
        // WorkManager rejects a periodic interval under fifteen minutes, so a
        // short option here would be a schedule that never runs.
        SyncInterval.entries.forEach { interval ->
            assertTrue("${interval.name} is too short", interval.hours * 60 >= 15)
        }
        assertEquals(168L, SyncInterval.WEEKLY.hours)
    }

    @Test
    fun `the pair name reads as the two folders`() {
        assertEquals("Documents ↔ Backup", job().pair)
    }

    @Test
    fun `a job that has never run says so, and a failed one says why`() {
        assertEquals("not run yet", job().lastSummary)
        assertEquals(
            "the other folder is gone",
            job().copy(lastRunAt = 1L, lastError = "the other folder is gone").lastSummary,
        )
        assertEquals(
            "4 copied, 1 trashed, 2 kept, 1 failed",
            job().copy(
                lastRunAt = 1L,
                lastCopied = 4,
                lastDeleted = 1,
                lastConflicts = 2,
                lastFailed = 1,
            ).lastSummary,
        )
    }
}

/** The hand-off between a bulk bar and the screen that does the work. */
class SelectionHandoffTest {

    @Test
    fun `a selection is taken exactly once`() {
        val handoff = SelectionHandoff()
        handoff.put(listOf("/a", "/b"))

        assertEquals(listOf("/a", "/b"), handoff.take())
        // The second read must be empty: a screen reopened later must not act
        // on a selection the user made in a previous session.
        assertTrue(handoff.take().isEmpty())
        assertTrue(handoff.pending.value.isEmpty())
    }

    @Test
    fun `duplicates collapse, and an empty put clears`() {
        val handoff = SelectionHandoff()
        handoff.put(listOf("/a", "/a", "/b"))
        assertEquals(listOf("/a", "/b"), handoff.take())

        handoff.put(listOf("/c"))
        handoff.put(emptyList())
        assertTrue(handoff.take().isEmpty())
    }

    @Test
    fun `nothing handed over reads as nothing`() {
        val handoff = SelectionHandoff()
        assertTrue(handoff.take().isEmpty())
    }
}
