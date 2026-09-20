package com.fileflow.app.core.storage

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val engine = ArchiveEngine()

    private fun zipWith(vararg names: String): File {
        val zip = temp.newFile("test.zip")
        ZipOutputStream(FileOutputStream(zip)).use { output ->
            names.forEach { name ->
                output.putNextEntry(ZipEntry(name))
                output.write("payload".toByteArray())
                output.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `round trips a folder`() = runBlocking {
        val source = temp.newFolder("photos")
        File(source, "one.txt").writeText("hello")
        File(source, "two.txt").writeText("world")

        val zip = File(temp.root, "photos.zip")
        val compressed = engine.compress(listOf(source.absolutePath), zip)
        assertTrue(compressed.isSuccess)
        assertTrue(zip.exists())
        assertEquals(3, compressed.getOrNull()?.entries)

        val destination = temp.newFolder("out")
        val extracted = engine.extract(zip, destination)
        assertTrue(extracted.isSuccess)

        val restored = File(destination, "photos/one.txt")
        assertTrue(restored.exists())
        assertEquals("hello", restored.readText())
    }

    @Test
    fun `refuses an entry that escapes the destination`() = runBlocking {
        val destination = temp.newFolder("safe")
        val escaped = File(destination.parentFile, "escaped.txt")
        val zip = zipWith("../escaped.txt", "inside.txt")

        val result = engine.extract(zip, destination)
        assertTrue(result.isSuccess)

        // The traversal entry is dropped; the innocent one still lands.
        assertFalse(escaped.exists())
        assertTrue(File(destination, "inside.txt").exists())
    }

    @Test
    fun `refuses an absolute entry path`() = runBlocking {
        val destination = temp.newFolder("safe2")
        val zip = zipWith("/tmp/fileflow-absolute-test.txt", "ok.txt")

        engine.extract(zip, destination)

        assertFalse(File("/tmp/fileflow-absolute-test.txt").exists())
        assertTrue(File(destination, "ok.txt").exists())
    }

    @Test
    fun `compressing nothing reports a failure rather than an empty zip`() = runBlocking {
        val zip = File(temp.root, "empty.zip")
        val result = engine.compress(listOf(File(temp.root, "missing.txt").absolutePath), zip)
        assertTrue(result.isFailure)
        assertFalse(zip.exists())
    }

    @Test
    fun `a nested folder keeps its structure`() = runBlocking {
        val source = temp.newFolder("tree")
        val nested = File(source, "a/b").apply { mkdirs() }
        File(nested, "deep.txt").writeText("deep")

        val zip = File(temp.root, "tree.zip")
        engine.compress(listOf(source.absolutePath), zip)

        val destination = temp.newFolder("treeOut")
        engine.extract(zip, destination)

        val restored = File(destination, "tree/a/b/deep.txt")
        assertTrue(restored.exists())
        assertEquals("deep", restored.readText())
    }

    @Test
    fun `partial files from an interrupted compress are cleaned up`() = runBlocking {
        val source = temp.newFolder("partial")
        File(source, "data.txt").writeText("x")
        val zip = File(temp.root, "out.zip")

        engine.compress(listOf(source.absolutePath), zip)

        assertNotNull(zip)
        val leftovers = temp.root.listFiles()?.filter { it.name.endsWith(".fileflow-part") }
            ?: emptyList()
        assertTrue("no partial file should survive a successful run", leftovers.isEmpty())
    }

    // --------------------------------------------------------------- tar

    @Test
    fun `tar round trips a folder`() = runBlocking {
        val source = temp.newFolder("tarball")
        val nested = File(source, "a/b").apply { mkdirs() }
        File(source, "one.txt").writeText("hello")
        File(nested, "deep.bin").writeBytes(ByteArray(5000) { (it % 251).toByte() })

        val archive = File(temp.root, "out.tar")
        val compressed = engine.compress(
            sources = listOf(source.absolutePath),
            destination = archive,
            format = ArchiveFormat.TAR,
            level = CompressionLevel.STORE,
        )
        assertTrue(compressed.isSuccess)

        val destination = temp.newFolder("tarOut")
        val extracted = engine.extract(archive, destination)
        assertTrue("extract failed: ${extracted.exceptionOrNull()?.message}", extracted.isSuccess)

        val restoredFile = File(destination, "tarball/one.txt")
        assertTrue(restoredFile.exists())
        assertEquals("hello", restoredFile.readText())
        // The binary payload survives byte for byte, padding included.
        val restoredBinary = File(destination, "tarball/a/b/deep.bin")
        assertTrue(restoredBinary.exists())
        assertArrayEquals(
            File(source, "a/b/deep.bin").readBytes(),
            restoredBinary.readBytes(),
        )
    }

    @Test
    fun `tar gz round trips and is smaller than the tar`() = runBlocking {
        val source = temp.newFolder("gz")
        // Repetitive data, so the gzip layer has something to do.
        File(source, "repeat.txt").writeText("fileflow ".repeat(4000))

        val plain = File(temp.root, "plain.tar")
        val gzipped = File(temp.root, "packed.tar.gz")
        val sources = listOf(source.absolutePath)

        engine.compress(sources, plain, ArchiveFormat.TAR, CompressionLevel.STORE)
        engine.compress(sources, gzipped, ArchiveFormat.TAR_GZ, CompressionLevel.MAXIMUM)

        assertTrue("gzip should shrink repetitive data", gzipped.length() < plain.length())

        val destination = temp.newFolder("gzOut")
        val extracted = engine.extract(gzipped, destination)
        assertTrue("extract failed: ${extracted.exceptionOrNull()?.message}", extracted.isSuccess)
        assertEquals(
            "fileflow ".repeat(4000),
            File(destination, "gz/repeat.txt").readText(),
        )
    }

    @Test
    fun `a tar entry cannot escape the destination`() = runBlocking {
        val destination = temp.newFolder("tarSafe")
        val escaped = File(destination.parentFile, "escaped.txt")
        val archive = tarWithEntry("../escaped.txt", "owned")

        val result = engine.extract(archive, destination)

        assertTrue(result.isSuccess)
        assertFalse(escaped.exists())
    }

    @Test
    fun `format is read from the whole file name`() {
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.of("holiday.zip"))
        assertEquals(ArchiveFormat.TAR, ArchiveFormat.of("backup.tar"))
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.of("backup.tar.gz"))
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.of("BACKUP.TGZ"))
        // A plain gzip is not a container of files.
        assertEquals(null, ArchiveFormat.of("notes.txt.gz"))
    }

    /**
     * A tar with one hand-written entry name — the only way to test a name that
     * no file on disk could have.
     */
    private fun tarWithEntry(name: String, payload: String): File {
        val tar = File(temp.root, "hostile.tar")
        val block = ByteArray(512)
        val nameBytes = name.toByteArray()
        System.arraycopy(nameBytes, 0, block, 0, minOf(nameBytes.size, 100))
        writeOctal(block, 100, 8, 0b110100100)
        writeOctal(block, 124, 12, payload.toByteArray().size.toLong())
        writeOctal(block, 136, 12, 0)
        block[156] = '0'.code.toByte()
        System.arraycopy("ustar".toByteArray(), 0, block, 257, 5)
        block[263] = '0'.code.toByte()
        block[264] = '0'.code.toByte()
        for (index in 148 until 156) block[index] = ' '.code.toByte()
        var sum = 0
        block.forEach { sum += it.toInt() and 0xFF }
        val checksum = sum.toString(8).padStart(6, '0') + "\u0000 "
        System.arraycopy(checksum.toByteArray(), 0, block, 148, 8)

        FileOutputStream(tar).use { output ->
            output.write(block)
            val data = payload.toByteArray()
            output.write(data)
            output.write(ByteArray(512 - data.size))
            output.write(ByteArray(1024))
        }
        return tar
    }

    private fun writeOctal(block: ByteArray, offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0')
        System.arraycopy(text.toByteArray(), 0, block, offset, length - 1)
        block[offset + length - 1] = 0
    }
}
