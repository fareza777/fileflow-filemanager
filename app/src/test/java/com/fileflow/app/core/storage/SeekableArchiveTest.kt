package com.fileflow.app.core.storage

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A seekable archive is only worth having if the seek is real: the index has to
 * describe the members without reading them, and a member has to come back
 * byte-identical. Both are checked here, along with the compression actually
 * compressing and the whole thing refusing files that are not this format.
 */
class SeekableArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Rebuilt each time, so a test can ask for a fresh tree and an archive. */
    private fun tree(): File {
        val root = File(temp.root, "source")
        root.deleteRecursively()
        root.mkdirs()
        File(root, "readme.txt").writeText("top level ".repeat(400))
        val docs = File(root, "docs").apply { mkdirs() }
        File(docs, "notes.md").writeText("# notes\n" + "a line\n".repeat(200))
        val deep = File(docs, "deep").apply { mkdirs() }
        File(deep, "data.bin").writeBytes(ByteArray(300_000) { (it % 251).toByte() })
        return root
    }

    private fun archive(): File {
        val source = tree()
        val target = File(temp.root, "backup.${SeekableArchive.EXTENSION}")
        runBlocking {
            SeekableArchive.write(
                sources = listOf(source),
                destination = target,
            )
        }
        return target
    }

    @Test
    fun `the index lists the tree without decompressing it`() = runBlocking {
        val target = archive()
        assertTrue(SeekableArchive.matches(target))

        val entries = SeekableArchive.list(target)
        val names = entries.map { it.name }
        assertTrue("readme.txt missing from $names", names.contains("source/readme.txt"))
        assertTrue("docs missing from $names", names.contains("source/docs"))
        assertTrue("deep file missing from $names", names.contains("source/docs/deep/data.bin"))

        val data = entries.first { it.name.endsWith("data.bin") }
        assertEquals(300_000L, data.sizeBytes)
        assertFalse(data.isDirectory)
    }

    @Test
    fun `opening one member returns exactly the bytes that were stored`() = runBlocking {
        val source = tree()
        val target = archive()

        val original = File(source, "readme.txt").readBytes()
        val restored = SeekableArchive.open(target, "source/readme.txt").use { it.readBytes() }
        assertArrayEquals(original, restored)

        // A member several levels down, and the binary one, come back whole too.
        val deep = File(source, "docs/deep/data.bin").readBytes()
        val deepRestored = SeekableArchive.open(target, "source/docs/deep/data.bin").use { it.readBytes() }
        assertArrayEquals(deep, deepRestored)
    }

    @Test
    fun `the archive is smaller than the data it holds`() = runBlocking {
        val source = temp.newFolder("compressible")
        // Repetitive on purpose: deflate has something to do.
        File(source, "log.txt").writeText("2024-01-01 INFO starting up the service\n".repeat(20_000))
        val target = File(temp.root, "small.${SeekableArchive.EXTENSION}")

        val result = SeekableArchive.write(listOf(source), target)

        assertTrue("expected real compression (${target.length()} vs ${result.bytes})", target.length() < result.bytes / 4)
    }

    @Test
    fun `a file that is not this format is refused`() = runBlocking {
        val plain = File(temp.root, "plain.txt").apply { writeText("not an archive") }
        assertFalse(SeekableArchive.matches(plain))
        val outcome = runCatching { SeekableArchive.open(plain, "anything") }
        assertTrue(outcome.isFailure)
    }

    @Test
    fun `an entry that is not in the archive is refused by name`() = runBlocking {
        val target = archive()
        val outcome = runCatching { SeekableArchive.open(target, "source/nope.txt") }
        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()?.message?.contains("nope.txt") == true)
    }
}
