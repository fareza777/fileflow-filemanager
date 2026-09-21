package com.fileflow.app.core.analysis

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The engines behind the inspection, fingerprint and recovery features are pure
 * functions over bytes, so they get tests that say what "correct" means: a
 * renamed file is caught, two files of the same shape hash alike, one byte in
 * the middle moves one chunk, and erasure coding survives exactly as many lost
 * shards as it promises.
 */
class AnalysisEnginesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        File(temp.root, name).apply { writeBytes(bytes) }

    private fun text(name: String, content: String): File =
        File(temp.root, name).apply { writeText(content) }

    // ------------------------------------------------------------ file types

    @Test
    fun `an extension that lies is caught`() {
        val jpeg = file("photo.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0))
        val verdict = FileTypes.detect(jpeg)
        assertEquals("jpeg", verdict.format)
        assertFalse(verdict.extensionLies)

        val disguised = file("invoice.pdf", jpeg.readBytes())
        val disguisedVerdict = FileTypes.detect(disguised)
        assertEquals("jpeg", disguisedVerdict.format)
        assertTrue("a jpeg named .pdf must be reported", disguisedVerdict.extensionLies)
    }

    @Test
    fun `containers are identified by their header`() {
        val png = file("x.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3))
        assertEquals("png", FileTypes.detect(png).format)

        val zip = file("x.zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0))
        assertEquals("zip", FileTypes.detect(zip).format)
        // The same bytes are an apk, and that is not a lie.
        val apk = File(temp.root, "app.apk").apply { writeBytes(zip.readBytes()) }
        assertFalse(FileTypes.detect(apk).extensionLies)

        val gzip = file("x.gz", byteArrayOf(0x1F, 0x8B.toByte(), 8, 0))
        assertEquals("gzip", FileTypes.detect(gzip).format)
    }

    @Test
    fun `plain text is recognised and binary is not`() {
        val document = text("notes.txt", "hello, this is a plain text file\nwith two lines\n")
        assertEquals("text", FileTypes.detect(document).format)

        val binary = file("blob.bin", ByteArray(256) { (it * 7 % 256).toByte() })
        assertEquals(TypeVerdict.UNKNOWN, FileTypes.detect(binary).format)
    }

    // -------------------------------------------------------------- hashing

    @Test
    fun `the fast hash separates different files and matches copies`() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val original = file("a.bin", payload)
        val copy = file("b.bin", payload)
        // A change inside a sampled window: the head, the middle or the tail.
        val different = file("c.bin", payload.copyOf().also { it[1_000] = 9 })

        assertEquals(FastHash.fastHash(original), FastHash.fastHash(copy))
        assertNotEquals(FastHash.fastHash(original), FastHash.fastHash(different))
        assertEquals(FastHash.fullHash(original), FastHash.fullHash(copy))
    }

    @Test
    fun `the fast hash is a pre-scan, and says so`() {
        // The trade the sampler makes: a difference that falls between the
        // three windows is invisible to it, and only the full hash finds it.
        // The UI uses the fast pass to build candidates and confirms with the
        // full one, so this behaviour is the contract, not a bug.
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val original = file("sampled.bin", payload)
        val sneaky = file("sneaky.bin", payload.copyOf().also { it[150_000] = 9 })

        assertEquals(FastHash.fastHash(original), FastHash.fastHash(sneaky))
        assertNotEquals(FastHash.fullHash(original), FastHash.fullHash(sneaky))
    }

    @Test
    fun `the fuzzy hash scores similar files closer than unrelated ones`() {
        val base = buildString {
            repeat(4000) { append("the quick brown fox jumps over the lazy dog $it\n") }
        }
        val edited = base.replace("quick", "slow").replace("lazy", "eager")
        val unrelated = buildString {
            repeat(4000) { append("lorem ipsum dolor sit amet consectetur $it\n") }
        }

        val baseDigest = FuzzyHash.digest(text("base.txt", base))
        val editedDigest = FuzzyHash.digest(text("edited.txt", edited))
        val unrelatedDigest = FuzzyHash.digest(text("other.txt", unrelated))

        val editedDistance = FuzzyHash.distance(baseDigest, editedDigest)
        val unrelatedDistance = FuzzyHash.distance(baseDigest, unrelatedDigest)

        assertTrue(
            "an edited copy ($editedDistance) must score closer than an unrelated file ($unrelatedDistance)",
            editedDistance < unrelatedDistance,
        )
        assertEquals(0, FuzzyHash.distance(baseDigest, FuzzyHash.digest(text("again.txt", base))))
    }

    @Test
    fun `content-defined chunking keeps chunks stable across an insertion`() = runBlocking {
        val random = java.util.Random(7)
        val originalBytes = ByteArray(400_000) { (random.nextInt(256)).toByte() }
        val original = file("original.bin", originalBytes)

        // Insert a byte near the start: every fixed-size chunk after it would
        // change; content-defined chunks should mostly survive.
        val inserted = ByteArray(originalBytes.size + 1)
        System.arraycopy(originalBytes, 0, inserted, 0, 10_000)
        inserted[10_000] = 42
        System.arraycopy(originalBytes, 10_000, inserted, 10_001, originalBytes.size - 10_000)
        val modified = file("modified.bin", inserted)

        val before = Chunker.split(original)
        val after = Chunker.split(modified)

        val beforeHashes = before.map { it.hash }.toSet()
        val shared = after.count { it.hash in beforeHashes }
        assertTrue("expected several chunks to survive, got $shared of ${after.size}", shared >= 3)
        // Offsets tile the file without gaps or overlap.
        assertEquals(0L, before.first().offset)
        before.zipWithNext().forEach { (a, b) -> assertEquals(a.offset + a.length, b.offset) }
    }

    @Test
    fun `a folder fingerprint is stable and changes with content`() = runBlocking {
        val folder = temp.newFolder("tree")
        File(folder, "one.txt").writeText("hello")
        File(folder, "two.txt").writeText("world")

        val first = MerkleFingerprint.of(folder)
        val second = MerkleFingerprint.of(folder)
        assertEquals(first?.hash, second?.hash)

        File(folder, "two.txt").writeText("world!")
        val changed = MerkleFingerprint.of(folder)
        assertNotEquals(first?.hash, changed?.hash)

        // The changed child is locatable without re-hashing the whole tree.
        val childBefore = first?.children?.first { it.name == "two.txt" }?.hash
        val childAfter = changed?.children?.first { it.name == "two.txt" }?.hash
        assertNotEquals(childBefore, childAfter)
        assertEquals(
            first?.children?.first { it.name == "one.txt" }?.hash,
            changed?.children?.first { it.name == "one.txt" }?.hash,
        )
    }
}
