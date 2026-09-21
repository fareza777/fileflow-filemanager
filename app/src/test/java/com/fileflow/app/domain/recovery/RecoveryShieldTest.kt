package com.fileflow.app.domain.recovery

import com.fileflow.app.domain.privacy.MetadataStripper
import java.io.ByteArrayOutputStream
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
 * Erasure coding is only worth having if it is exactly as forgiving as it
 * claims, so the tests lose shards on purpose: any `dataShards` of them must
 * rebuild the file, and one fewer must refuse rather than produce garbage.
 */
class RecoveryShieldTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun payload(size: Int): ByteArray =
        ByteArray(size) { index -> (index * 31 % 251).toByte() }

    @Test
    fun `a file survives losing as many shards as there is parity`() = runBlocking {
        val original = File(temp.root, "original.bin").apply { writeBytes(payload(100_000)) }
        val shards = temp.newFolder("shards")
        val shield = RecoveryShield()

        val result = shield.create(
            source = original,
            destination = shards,
            dataShards = 4,
            parityShards = 2,
        )
        assertEquals(6, result.shards.size)

        // Lose the maximum the geometry allows: two shards, one of them data.
        File(result.shards[0]).delete()
        File(result.shards[4]).delete()

        val restored = File(temp.root, "restored.bin")
        shield.restore(folder = shards, baseName = "original.bin", destination = restored)

        assertArrayEquals(original.readBytes(), restored.readBytes())
    }

    @Test
    fun `one shard fewer than needed refuses instead of guessing`() = runBlocking {
        val original = File(temp.root, "small.bin").apply { writeBytes(payload(40_000)) }
        val shards = temp.newFolder("few")
        val shield = RecoveryShield()

        val result = shield.create(original, shards, dataShards = 3, parityShards = 1)
        // Four shards, three needed: removing two leaves two, which is short.
        File(result.shards[0]).delete()
        File(result.shards[1]).delete()

        val outcome = runCatching {
            shield.restore(shards, "small.bin", File(temp.root, "never.bin"))
        }
        assertTrue("restoring with too few shards must fail", outcome.isFailure)
        assertFalse(File(temp.root, "never.bin").exists())
    }

    @Test
    fun `the shard geometry is readable from any single shard`() = runBlocking {
        val original = File(temp.root, "meta.bin").apply { writeBytes(payload(10_000)) }
        val shards = temp.newFolder("meta")
        val shield = RecoveryShield()
        shield.create(original, shards, dataShards = 5, parityShards = 3)

        val headers = shield.inspect(shards)
        assertEquals(8, headers.size)
        assertTrue(headers.all { it.dataShards == 5 && it.parityShards == 3 })
        assertTrue(headers.all { it.originalSize == 10_000L })
        assertEquals((0 until 8).toList(), headers.map { it.index }.sorted())
    }

    @Test
    fun `the galois field multiplies and inverts as the maths requires`() {
        // A few identities that hold in GF(256) and not in integer arithmetic.
        assertEquals(0, GF.mul(0, 77))
        assertEquals(1, GF.mul(1, 1))
        assertEquals(GF.mul(53, 200), GF.mul(200, 53))
        // Multiplying by a value and dividing by it returns the original.
        assertEquals(91, GF.div(GF.mul(91, 13), 13))
        assertEquals(200, GF.div(GF.mul(200, 53), 53))

        // A matrix multiplied by its inverse is the identity.
        val matrix = arrayOf(
            byteArrayOf(GF.pow(0), GF.pow(0)),
            byteArrayOf(GF.pow(0), GF.pow(1)),
        )
        val inverse = GF.invert(matrix)
        val identity = GF.multiply(matrix, inverse)
        assertEquals(1, identity[0][0].toInt() and 0xFF)
        assertEquals(0, identity[0][1].toInt())
        assertEquals(0, identity[1][0].toInt())
        assertEquals(1, identity[1][1].toInt() and 0xFF)
    }
}

/**
 * Metadata removal has to leave the picture alone, which is only checkable by
 * looking at the bytes: the JPEG's compressed data must come through identical
 * while the EXIF segment disappears.
 */
class MetadataStripperTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A minimal but well-formed JPEG: SOI, APP1 with EXIF, SOS, image data, EOI. */
    private fun jpegWithExif(): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))

        val exifPayload = "Exif\u0000\u0000GPS 51.5N 0.12W".toByteArray(Charsets.ISO_8859_1)
        val exifLength = exifPayload.size + 2
        output.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
        output.write(byteArrayOf((exifLength shr 8).toByte(), exifLength.toByte()))
        output.write(exifPayload)

        // A comment segment, also metadata.
        val comment = "shot on a phone".toByteArray(Charsets.ISO_8859_1)
        val commentLength = comment.size + 2
        output.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        output.write(byteArrayOf((commentLength shr 8).toByte(), commentLength.toByte()))
        output.write(comment)

        // Start of scan, then entropy-coded data, then end of image.
        output.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02))
        val imageData = ByteArray(512) { (it % 253).toByte() }
        output.write(imageData)
        output.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return output.toByteArray()
    }

    @Test
    fun `jpeg metadata is removed and the image data is untouched`() = runBlocking {
        val source = File(temp.root, "photo.jpg").apply { writeBytes(jpegWithExif()) }
        val target = File(temp.root, "clean.jpg")
        val stripper = MetadataStripper()

        val report = stripper.strip(source, target)

        assertTrue("EXIF should be reported", report.segments.any { it.contains("EXIF") })
        assertTrue(
            "the GPS tag in the metadata should be flagged, segments=${report.segments}",
            report.hasLocation,
        )
        assertTrue(report.removedBytes > 0)

        val cleaned = target.readBytes()
        val original = source.readBytes()
        // No APP1 or comment marker survives.
        assertFalse(cleaned.toList().windowed(2).any { it[0] == 0xFF.toByte() && it[1] == 0xE1.toByte() })
        assertFalse(cleaned.toList().windowed(2).any { it[0] == 0xFF.toByte() && it[1] == 0xFE.toByte() })
        // The image data itself is byte-identical.
        val imageData = ByteArray(512) { (it % 253).toByte() }
        assertTrue(
            "the compressed image must be copied through",
            cleaned.toList().windowed(512).any { window -> window.toByteArray().contentEquals(imageData) },
        )
        assertTrue("still a JPEG", cleaned[0] == 0xFF.toByte() && cleaned[1] == 0xD8.toByte())
        assertTrue("still ends with EOI", original.last() == cleaned.last())
    }

    @Test
    fun `inspecting reports what would be removed without writing`() = runBlocking {
        val source = File(temp.root, "inspect.jpg").apply { writeBytes(jpegWithExif()) }
        val stripper = MetadataStripper()
        val report = stripper.inspect(source)
        assertTrue(report.segments.isNotEmpty())
        assertEquals(0L, report.removedBytes)
        // Nothing else was created.
        assertEquals(1, temp.root.listFiles()?.size)
    }

    @Test
    fun `a file that is neither jpeg nor png is refused`() = runBlocking {
        val source = File(temp.root, "notes.txt").apply { writeText("hello") }
        val stripper = MetadataStripper()
        assertFalse(stripper.canStrip(source))
        val outcome = runCatching { stripper.strip(source, File(temp.root, "out.txt")) }
        assertTrue(outcome.isFailure)
    }
}
