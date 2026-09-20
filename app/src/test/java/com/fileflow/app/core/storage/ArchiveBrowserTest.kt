package com.fileflow.app.core.storage

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Browsing an archive as a folder is the one feature here that reads data it
 * did not write: a tar is walked by hand, entry by entry, and a zip is listed
 * from its central directory. Both get a round trip against an archive this
 * app wrote, so a wrong offset or a missed prefix shows up as a test failure
 * rather than as an empty folder on a phone.
 */
class ArchiveBrowserTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val engine = ArchiveEngine()
    private val browser = ArchiveBrowser()

    private fun sourceTree(): File {
        val root = temp.newFolder("tree")
        File(root, "readme.txt").writeText("top level")
        val folder = File(root, "docs").apply { mkdirs() }
        File(folder, "notes.md").writeText("# notes")
        val deep = File(folder, "deep").apply { mkdirs() }
        File(deep, "answer.txt").writeText("42")
        return root
    }

    private fun archive(name: String, format: ArchiveFormat): File {
        val source = sourceTree()
        val target = File(temp.root, name)
        val result = runBlocking {
            engine.compress(
                sources = listOf(source.absolutePath),
                destination = target,
                format = format,
                level = CompressionLevel.NORMAL,
            )
        }
        assertTrue("could not build the archive: ${result.exceptionOrNull()?.message}", result.isSuccess)
        return target
    }

    private fun assertBrowsable(archive: File) = runBlocking {
        // The archive's own folder name is the top-level entry.
        val root = browser.list(archive, "")
        assertTrue("expected the tree folder, got ${root.map { it.name }}", root.any { it.name == "tree" && it.isDirectory })

        val inside = browser.list(archive, "tree")
        val names = inside.map { it.name }
        assertTrue("readme.txt missing from $names", names.contains("readme.txt"))
        assertTrue("docs missing from $names", names.contains("docs"))
        assertTrue("docs should be a folder", inside.first { it.name == "docs" }.isDirectory)

        // Folders that exist only as a prefix of deeper entries still appear.
        val deepNames = browser.list(archive, "tree/docs").map { it.name }
        assertTrue("notes.md missing from $deepNames", deepNames.contains("notes.md"))
        assertTrue("deep folder missing from $deepNames", deepNames.contains("deep"))

        // And an entry reads back whole.
        val text = browser.open(archive, "tree/readme.txt").use { it.readBytes().decodeToString() }
        assertEquals("top level", text)

        val nested = browser.open(archive, "tree/docs/deep/answer.txt").use { it.readBytes().decodeToString() }
        assertEquals("42", nested)
    }

    @Test
    fun `a zip browses like a folder`() {
        val archive = archive("browsable.zip", ArchiveFormat.ZIP)
        assertTrue(browser.isArchive(archive))
        assertBrowsable(archive)
    }

    @Test
    fun `a tar browses like a folder`() {
        val archive = archive("browsable.tar", ArchiveFormat.TAR)
        assertBrowsable(archive)
    }

    @Test
    fun `a tar gz browses like a folder`() {
        val archive = archive("browsable.tar.gz", ArchiveFormat.TAR_GZ)
        assertBrowsable(archive)
    }

    @Test
    fun `extracting one entry writes just that entry`() = runBlocking {
        val archive = archive("pick.tar", ArchiveFormat.TAR)
        val target = File(temp.root, "picked.txt")

        val written = browser.extract(archive, "tree/docs/deep/answer.txt", target)

        assertTrue(target.exists())
        assertEquals("42", target.readText())
        assertEquals(2L, written)
    }

    @Test
    fun `a file that is not an archive is refused`() = runBlocking {
        val plain = File(temp.root, "notes.txt").apply { writeText("not an archive") }
        assertFalse(browser.isArchive(plain))
        val opened = runCatching { browser.open(plain, "anything").use { it.readBytes() } }
        assertTrue(opened.isFailure)
    }
}
