package com.fileflow.app.core.analysis

import com.fileflow.app.domain.versions.VersionStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Content search is only useful if it reads what it claims and refuses what it
 * cannot, and if "similar" means something a substring search would not find.
 * Both are cheap to check without a device.
 */
class ContentSearchEnginesTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---------------------------------------------------------- text reading

    @Test
    fun `text files are read and binaries are refused`() {
        val text = File(temp.root, "notes.md").apply {
            writeText("# Notes\n\nThe invoice for March is attached.\n")
        }
        val extracted = TextExtractor.extract(text)
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("invoice"))

        // A file with NUL bytes is not text, whatever its extension says.
        val binary = File(temp.root, "fake.txt").apply {
            writeBytes(ByteArray(512) { if (it % 7 == 0) 0 else 65 })
        }
        assertNull(TextExtractor.extract(binary))

        // And an extension nobody can read is skipped before it is opened.
        val image = File(temp.root, "photo.jpg").apply { writeBytes(ByteArray(64) { 1 }) }
        assertFalse(TextExtractor.canExtract(image))
    }

    @Test
    fun `a byte order mark is not part of the content`() {
        val file = File(temp.root, "bom.txt").apply {
            writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello".toByteArray())
        }
        assertEquals("hello", TextExtractor.extract(file))
    }

    @Test
    fun `a snippet is taken around the hit`() {
        val body = "prefix ".repeat(40) + "invoice" + " suffix".repeat(40)
        val snippet = TextExtractor.snippet(body, listOf("invoice"))
        assertTrue(snippet.contains("invoice"))
        assertTrue("a long body must be trimmed", snippet.length < body.length)
    }

    // --------------------------------------------------------------- vectors

    @Test
    fun `vectors put documents about the same thing closer together`() {
        val invoice = TextVectors.embed(
            "Invoice 2024 for consulting services, payment due in thirty days, tax included"
        )
        val receipt = TextVectors.embed(
            "Receipt for consulting services, payment received, tax noted on the invoice"
        )
        val unrelated = TextVectors.embed(
            "Photosynthesis converts light into chemical energy in plant chloroplasts"
        )

        val close = TextVectors.cosine(invoice, receipt)
        val far = TextVectors.cosine(invoice, unrelated)
        assertTrue("expected the related document to score higher ($close vs $far)", close > far)
        assertTrue("a document is closest to itself", TextVectors.cosine(invoice, invoice) > 0.99f)
    }

    @Test
    fun `a query with none of its words still ranks by meaning`() {
        val about = TextVectors.embed("quarterly financial report with revenue and expenses")
        val query = TextVectors.embed("financial revenue report")
        val noise = TextVectors.embed("how to cook rice in a pressure cooker")
        assertTrue(TextVectors.cosine(query, about) > TextVectors.cosine(query, noise))
    }

    @Test
    fun `packing a vector round trips`() {
        val vector = TextVectors.embed("the quick brown fox jumps over the lazy dog")
        val unpacked = TextVectors.unpack(TextVectors.pack(vector), TextVectors.DIMENSION)
        assertEquals(vector.size, unpacked.size)
        vector.indices.forEach { index ->
            assertEquals(vector[index], unpacked[index], 0.0001f)
        }
    }
}

/**
 * The version store's promise is that a version costs the chunks that changed,
 * not the file — and that restoring gives back exactly the bytes that were
 * snapshotted, which is checked by comparing them.
 */
class VersionStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * Random rather than patterned on purpose. A periodic payload chunks into
     * identical pieces and the store de-duplicates nearly all of it, which is
     * correct behaviour and a useless measurement of "did it store the file".
     */
    private fun payload(size: Int, seed: Int): ByteArray {
        val random = java.util.Random(seed.toLong())
        return ByteArray(size) { random.nextInt(256).toByte() }
    }

    @Test
    fun `repeating content costs almost nothing after the first copy`() = runBlocking {
        val store = VersionStore(File(temp.root, "store-repeat"))
        // A file that is the same paragraph over and over: content-defined
        // chunking produces the same chunk for the same paragraph, so the store
        // keeps one of each.
        val paragraph = ByteArray(300) { index -> ((index * 31 + 5) % 251).toByte() }
        val document = File(temp.root, "repeating.bin").apply {
            writeBytes(ByteArray(60_000) { paragraph[it % paragraph.size] })
        }

        val version = store.snapshot(document)!!
        assertTrue(
            "60 KB of one repeated paragraph should store far less (stored=${version.storedBytes})",
            version.storedBytes < 40_000,
        )
    }

    @Test
    fun `a second version stores only what changed`() = runBlocking {
        val store = VersionStore(File(temp.root, "store"))
        val document = File(temp.root, "document.bin").apply { writeBytes(payload(400_000, 1)) }

        val first = store.snapshot(document)
        assertNotNull(first)
        assertTrue(
            "the first version stores the whole file (stored=${first!!.storedBytes}, chunks=${first.chunks})",
            first.storedBytes > 300_000,
        )

        // Change a small part in the middle: content-defined chunking should
        // keep every other chunk, so almost nothing new is stored.
        val edited = document.readBytes()
        for (index in 200_000 until 200_100) edited[index] = 9
        document.writeBytes(edited)

        val second = store.snapshot(document)
        assertNotNull(second)
        assertTrue(
            "an edit of 100 bytes should store far less than the file (${second!!.storedBytes} bytes)",
            second.storedBytes < 120_000,
        )
    }

    @Test
    fun `an unchanged file is not snapshotted twice`() = runBlocking {
        val store = VersionStore(File(temp.root, "store2"))
        val document = File(temp.root, "same.bin").apply { writeBytes(payload(120_000, 3)) }

        assertNotNull(store.snapshot(document))
        assertNull("nothing changed, so nothing is stored", store.snapshot(document))
    }

    @Test
    fun `every version restores to its own bytes`() = runBlocking {
        val store = VersionStore(File(temp.root, "store3"))
        val document = File(temp.root, "report.bin").apply { writeBytes(payload(300_000, 7)) }
        val originalBytes = document.readBytes()
        val first = store.snapshot(document)!!

        val edited = document.readBytes()
        for (index in 50_000 until 50_400) edited[index] = 42
        document.writeBytes(edited)
        val second = store.snapshot(document)!!

        val restoredOld = File(temp.root, "old.bin")
        val restoredNew = File(temp.root, "new.bin")
        store.restore(document, first.id, restoredOld)
        store.restore(document, second.id, restoredNew)

        assertTrue("the first version must come back byte for byte", restoredOld.readBytes().contentEquals(originalBytes))
        assertTrue("the second version must come back byte for byte", restoredNew.readBytes().contentEquals(edited))
    }

    @Test
    fun `the store reports what it saved`() = runBlocking {
        val store = VersionStore(File(temp.root, "store4"))
        val document = File(temp.root, "big.bin").apply { writeBytes(payload(500_000, 11)) }
        store.snapshot(document)

        val edited = document.readBytes()
        for (index in 250_000 until 250_050) edited[index] = 5
        document.writeBytes(edited)
        store.snapshot(document)

        val stats = store.stats()
        assertEquals(2, stats.versions)
        assertTrue("two versions of a 500 KB file should not cost a megabyte", stats.storedBytes < 800_000)
        assertTrue("and the saving should be reported", stats.savedBytes > 0)
    }
}
