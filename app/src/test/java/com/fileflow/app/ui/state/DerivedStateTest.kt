package com.fileflow.app.ui.state

import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.scan.JunkBucket
import com.fileflow.app.domain.scan.JunkKind
import com.fileflow.app.domain.scan.JunkReport
import com.fileflow.app.domain.timeline.MonthData
import com.fileflow.app.ui.browser.BrowserState
import com.fileflow.app.ui.junk.JunkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivedStateTest {

    private fun item(path: String, size: Long = 100, isDirectory: Boolean = false) = FileItem(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = isDirectory,
        // Directory listings report no size, the same as FileItems.from does.
        sizeBytes = if (isDirectory) 0L else size,
        lastModified = 0L,
        extension = "txt",
        mimeType = "text/plain",
        category = FileCategory.DOCUMENTS,
        isHidden = false,
    )

    // -------------------------------------------------------- browser state

    @Test
    fun `selection derives items and total size once`() {
        val state = BrowserState(
            items = listOf(item("/a", 10), item("/b", 20), item("/c", 30)),
        ).withSelection(setOf("/a", "/c"))

        assertEquals(2, state.selectedItems.size)
        assertEquals(40L, state.selectedBytes)
        assertTrue(state.selectionMode)
    }

    @Test
    fun `clearing the selection zeroes everything`() {
        val state = BrowserState(items = listOf(item("/a", 10)))
            .withSelection(setOf("/a"))
            .withSelection(emptySet())

        assertTrue(state.selectedItems.isEmpty())
        assertEquals(0L, state.selectedBytes)
        assertFalse(state.selectionMode)
    }

    @Test
    fun `a reload drops selected paths that are gone`() {
        // Selecting two files, then reloading a listing that only has one.
        val selected = BrowserState(items = listOf(item("/a"), item("/b")))
            .withSelection(setOf("/a", "/b"))

        val reloaded = selected.copy(items = listOf(item("/a"))).withSelection(
            selected.selection.filterTo(HashSet()) { path ->
                listOf("/a").any { it == path }
            }
        )

        assertEquals(setOf("/a"), reloaded.selection)
        assertEquals(1, reloaded.selectedItems.size)
    }

    @Test
    fun `folders count toward the selection size as zero`() {
        val state = BrowserState(items = listOf(item("/dir", isDirectory = true)))
            .withSelection(setOf("/dir"))
        assertEquals(0L, state.selectedBytes)
        assertEquals(1, state.selectedItems.size)
    }

    // ------------------------------------------------------- calendar state

    @Test
    fun `a day with no files reports zero rather than throwing`() {
        val data = MonthData(year = 2026, month = 9, filesByDay = mapOf(3 to listOf(item("/x"))))
        assertEquals(1, data.countOn(3))
        assertEquals(0, data.countOn(4))
        assertTrue(data.day(4).isEmpty())
    }

    @Test
    fun `busiest day and totals are computed from counts`() {
        val data = MonthData(
            year = 2026,
            month = 9,
            filesByDay = mapOf(
                1 to listOf(item("/a", 5)),
                2 to listOf(item("/b", 5), item("/c", 5)),
            ),
        )
        assertEquals(3, data.totalFiles)
        assertEquals(15L, data.totalBytes)
        assertEquals(2, data.busiestDay)
    }

    // ----------------------------------------------------------- junk state

    private fun candidate(path: String, size: Long) = Candidate(
        path = path,
        name = path.substringAfterLast('/'),
        extension = "tmp",
        category = FileCategory.OTHER,
        sizeBytes = size,
        lastModified = 0L,
        isHidden = false,
        isDirectory = false,
    )

    @Test
    fun `accepted buckets drive the reclaimable total`() {
        val report = JunkReport(
            buckets = listOf(
                JunkBucket(JunkKind.TEMP, listOf(candidate("/t1", 100)), 100),
                JunkBucket(JunkKind.LOGS, listOf(candidate("/l1", 250)), 250),
            )
        )
        val state = JunkState().withAccepted(report, setOf(JunkKind.LOGS))

        assertEquals(1, state.selectedCount)
        assertEquals(250L, state.selectedBytes)
    }

    @Test
    fun `accepting nothing reports nothing reclaimable`() {
        val report = JunkReport(
            buckets = listOf(JunkBucket(JunkKind.TEMP, listOf(candidate("/t1", 100)), 100))
        )
        val state = JunkState().withAccepted(report, emptySet())

        assertEquals(0, state.selectedCount)
        assertEquals(0L, state.selectedBytes)
        assertTrue(state.acceptedItems().isEmpty())
    }
}
