package com.fileflow.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathsTest {

    private val root = "/storage/emulated/0"

    @Test
    fun `parent is stripped one level`() {
        assertEquals("/storage/emulated/0", Paths.parentOf("/storage/emulated/0/Download"))
        assertEquals("/storage/emulated/0/Download", Paths.parentOf("/storage/emulated/0/Download/a.txt"))
    }

    @Test
    fun `root has no parent`() {
        assertNull(Paths.parentOf("/"))
    }

    @Test
    fun `containment is path aware, not textual`() {
        assertTrue(Paths.isInside("/storage/emulated/0/Download/a.txt", "/storage/emulated/0/Download"))
        assertTrue(Paths.isInside("/storage/emulated/0/Download", "/storage/emulated/0/Download"))
        // "Download2" must not count as being inside "Download".
        assertFalse(Paths.isInside("/storage/emulated/0/Download2/a.txt", "/storage/emulated/0/Download"))
    }

    @Test
    fun `names and joins behave on trailing slashes`() {
        assertEquals("Download", Paths.nameOf("/storage/emulated/0/Download/"))
        assertEquals("/a/b", Paths.join("/a", "b"))
        assertEquals("/a/b", Paths.join("/a/", "b"))
    }

    @Test
    fun `shared storage collapses to a friendly label`() {
        assertEquals("Internal storage", Paths.display(root, root))
        assertEquals("Download", Paths.display("$root/Download", root))
        assertEquals("/storage/1A2B-3C4D/DCIM", Paths.display("/storage/1A2B-3C4D/DCIM", root))
    }

    @Test
    fun `breadcrumbs walk from the volume down`() {
        val crumbs = Paths.breadcrumbSegments("$root/Download/Invoices", root)
        assertEquals(3, crumbs.size)
        assertEquals("Internal storage", crumbs[0].first)
        assertEquals("Download", crumbs[1].first)
        assertEquals("Invoices", crumbs[2].first)
        assertEquals("$root/Download/Invoices", crumbs[2].second)
    }

    @Test
    fun `illegal file names are rejected`() {
        assertFalse(Paths.isValidFileName(""))
        assertFalse(Paths.isValidFileName("."))
        assertFalse(Paths.isValidFileName(".."))
        assertFalse(Paths.isValidFileName("a/b"))
        assertFalse(Paths.isValidFileName("a:b"))
        assertFalse(Paths.isValidFileName("a\u0000b"))
        assertTrue(Paths.isValidFileName("holiday photo.jpg"))
        assertTrue(Paths.isValidFileName("2023-01-01 12-00-00.jpeg"))
    }

    @Test
    fun `sanitiser replaces illegal characters and keeps the rest`() {
        assertEquals("a_b", Paths.sanitizeFileName("a/b"))
        assertEquals("a b", Paths.sanitizeFileName("a b"))
        assertEquals("untitled", Paths.sanitizeFileName("   "))
        assertEquals("report", Paths.sanitizeFileName("report."))
    }
}

class FormattersTest {

    @Test
    fun `bytes switch units at the right thresholds`() {
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("1.00 KB", Formatters.bytes(1024))
        assertEquals("1.00 MB", Formatters.bytes(1024L * 1024))
        assertEquals("1.00 GB", Formatters.bytes(1024L * 1024 * 1024))
    }

    @Test
    fun `precision drops as the number grows`() {
        assertEquals("1.50 MB", Formatters.bytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("15.0 MB", Formatters.bytes((15.0 * 1024 * 1024).toLong()))
        assertEquals("150 MB", Formatters.bytes((150.0 * 1024 * 1024).toLong()))
    }

    @Test
    fun `negative sizes are shown as unknown`() {
        assertEquals("—", Formatters.bytes(-1))
    }

    @Test
    fun `percent is clamped`() {
        assertEquals(0.5f, Formatters.percent(50, 100))
        assertEquals(0f, Formatters.percent(10, 0))
        assertEquals(1f, Formatters.percent(200, 100))
    }

    @Test
    fun `count pluralises`() {
        assertEquals("1 file", Formatters.count(1, "file", "files"))
        assertEquals("2 files", Formatters.count(2, "file", "files"))
    }
}
