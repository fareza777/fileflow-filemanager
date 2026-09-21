package com.fileflow.app.core.delta

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A file that changed a little must move a little.
 *
 * This is the whole point of content-defined chunking, and it is the one
 * property that is easy to believe and hard to verify by eye: insert a
 * paragraph near the top of a large file and, with fixed-size blocks, every
 * later block changes. These tests assert the opposite, with the numbers.
 */
class DeltaTransferTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Random-ish text, so the rolling hash has something to cut on. */
    private fun text(seed: Int, lines: Int): String {
        val random = java.util.Random(seed.toLong())
        return buildString {
            repeat(lines) {
                append(random.nextInt(1_000_000))
                append(' ')
                append("lorem ipsum dolor sit amet ")
                append(random.nextInt(1000))
                append('\n')
            }
        }
    }

    private fun write(name: String, content: String): File =
        folder.newFile(name).apply { writeText(content) }

    @Test
    fun `an unchanged file is all reuse and no transfer`() = runBlocking {
        val first = write("a.bin", text(seed = 1, lines = 4000))

        val plan = DeltaTransfer.plan(first, DeltaTransfer.manifest(first))

        assertTrue("nothing should have to move", plan.nothingToDo)
        assertEquals(0L, plan.fetchBytes)
        assertEquals(100, plan.savedPercent)
        assertEquals(first.length(), plan.reusableBytes)
    }

    @Test
    fun `an insertion near the top moves a fraction of the file, not the file`() = runBlocking {
        val before = text(seed = 2, lines = 8000)
        val local = write("before.bin", before)

        // A paragraph is inserted after the first line, the way an edit lands
        // in a document: every later byte shifts, but the bytes themselves are
        // unchanged.
        val edited = before.replaceFirst("\n", "\nthe quick brown fox jumps over the lazy dog\n")
        val remoteFile = write("after.bin", edited)
        val remote = DeltaTransfer.manifest(remoteFile)
        assertTrue("the file did change", remoteFile.length() > local.length())

        // The local copy is the pre-edit revision and the manifest describes
        // the post-edit one. With fixed-size blocks nothing here would match.
        val plan = DeltaTransfer.plan(local, remote)

        val movedPercent = (plan.fetchBytes * 100 / remote.size).toInt()
        assertTrue(
            "an insertion should move well under half the file, moved $movedPercent%",
            movedPercent < 20,
        )
        assertTrue("the pieces after the edit are reused", plan.reuse.isNotEmpty())
    }

    @Test
    fun `a single edited line leaves almost everything reusable`() = runBlocking {
        val original = text(seed = 3, lines = 6000)
        val local = write("local.bin", original)

        // Change one line in the middle. Fixed-size blocks would make every
        // block up to the end differ.
        val edited = original.replace(
            original.lines()[3000],
            "one line that is completely different from what was there before",
        )
        val remoteFile = write("remote.bin", edited)
        val remote = DeltaTransfer.manifest(remoteFile)

        val plan = DeltaTransfer.plan(local, remote)

        val movedPercent = (plan.fetchBytes * 100 / remote.size).toInt()
        assertTrue(
            "one edited line should move a few percent, moved $movedPercent%",
            movedPercent < 15,
        )
        assertTrue("most of the file is reused", plan.savedPercent > 85)
    }

    @Test
    fun `an empty local file fetches everything`() = runBlocking {
        val local = folder.newFile("empty.bin")
        val remoteFile = write("full.bin", text(seed = 4, lines = 2000))
        val remote = DeltaTransfer.manifest(remoteFile)

        val plan = DeltaTransfer.plan(local, remote)

        assertEquals(0L, plan.reusableBytes)
        assertEquals(remote.size, plan.fetchBytes)
        assertEquals(0, plan.savedPercent)
    }

    @Test
    fun `applying a plan rebuilds the remote file byte for byte`() = runBlocking {
        val original = text(seed = 5, lines = 5000)
        val local = write("old.bin", original)
        val edited = original.replaceFirst("lorem", "LOREM, CHANGED")
        val remoteFile = write("new.bin", edited)
        val remote = DeltaTransfer.manifest(remoteFile)
        val plan = DeltaTransfer.plan(local, remote)

        // A source that counts what it is asked for, and reads it off the
        // remote file — exactly what the HTTP source does.
        var served = 0
        val source = object : ChunkSource {
            override suspend fun chunk(offset: Long, length: Int): ByteArray? {
                served += length
                val bytes = ByteArray(length)
                java.io.RandomAccessFile(remoteFile, "r").use {
                    it.seek(offset)
                    it.readFully(bytes)
                }
                return bytes
            }
        }

        val output = File(folder.root, "result.bin")
        assertTrue(DeltaTransfer.apply(local, plan, source, output))

        assertEquals(edited, output.readText())
        assertEquals("the source served only the missing pieces", plan.fetchBytes, served.toLong())
        assertTrue("and fewer bytes than the file", served < remote.size)
    }

    @Test
    fun `a source that fails leaves no half written file`() = runBlocking {
        val local = write("local.bin", text(seed = 6, lines = 500))
        val remoteFile = write("remote.bin", text(seed = 7, lines = 500))
        val plan = DeltaTransfer.plan(local, DeltaTransfer.manifest(remoteFile))

        val source = object : ChunkSource {
            override suspend fun chunk(offset: Long, length: Int): ByteArray? = null
        }

        val output = File(folder.root, "never.bin")
        assertFalse(DeltaTransfer.apply(local, plan, source, output))

        assertFalse("nothing is left behind", output.exists())
        assertFalse(File(folder.root, "never.bin.part").exists())
    }

    @Test
    fun `a manifest survives a trip through json`() = runBlocking {
        val file = write("manifest.bin", text(seed = 8, lines = 800))
        val manifest = DeltaTransfer.manifest(file)

        val parsed = DeltaManifest.parse(manifest.toJson())

        assertEquals(manifest, parsed)
        assertEquals(manifest.chunks.size, parsed?.chunks?.size)
        assertEquals(file.length(), parsed?.size)
    }

    @Test
    fun `junk json is rejected rather than half read`() {
        assertEquals(null, DeltaManifest.parse("not json at all"))
        assertEquals(null, DeltaManifest.parse("{}"))
    }
}
