package com.fileflow.app.domain.privacy

import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What was found in a file's metadata, before it is stripped. */
@androidx.compose.runtime.Immutable
data class MetadataReport(
    val format: String,
    val segments: List<String>,
    val hasLocation: Boolean,
    val removedBytes: Long,
)

/**
 * Metadata removal without re-encoding.
 *
 * The obvious way to strip a photo's metadata is to decode it and save it
 * again — which also throws away a generation of quality, and turns a 4 MB
 * photo into a different 4 MB photo. A JPEG is a sequence of marked segments,
 * so the metadata is *removed* rather than re-created: the APP1/EXIF, APP13,
 * XMP and comment segments are dropped, and every byte of the compressed image
 * is copied through untouched. What comes out is pixel-identical, which is the
 * only kind of edit that is safe to make to someone's photo.
 *
 * PNG metadata lives in named chunks before the image data, so the same idea
 * applies: drop `tEXt`, `iTXt`, `zTXt`, `eXIf`, `tIME`, keep everything else.
 */
@Singleton
class MetadataStripper @Inject constructor() {

    /** True when this file has metadata this can remove. */
    fun canStrip(file: File): Boolean = Companion.canStrip(file)

    /**
     * Removes metadata, writing the result to [target]. Never modifies the
     * original: the caller decides what to do with the copy, and a mistake here
     * cannot cost the user their photo.
     */
    suspend fun strip(
        source: File,
        target: File,
        onProgress: (bytes: Long) -> Unit = {},
    ): MetadataReport = withContext(Dispatchers.IO) {
        val format = formatOf(source)
        val before = readAll(source)
        val (cleaned, found) = when (format) {
            "jpeg" -> stripJpeg(before, onProgress)
            "png" -> stripPng(before, onProgress)
            else -> throw IllegalArgumentException("${source.name} is not a JPEG or PNG")
        }
        target.parentFile?.mkdirs()
        target.writeBytes(cleaned)
        MetadataReport(
            format = format,
            segments = found.map { it.name },
            hasLocation = found.any { namesLocation(before, it) },
            removedBytes = (before.size - cleaned.size).toLong().coerceAtLeast(0L),
        )
    }

    /** What a file carries, without writing anything. */
    suspend fun inspect(source: File): MetadataReport = withContext(Dispatchers.IO) {
        val format = formatOf(source)
        val bytes = readAll(source)
        val found = when (format) {
            "jpeg" -> scanJpeg(bytes)
            "png" -> scanPng(bytes)
            else -> emptyList()
        }
        MetadataReport(
            format = format,
            segments = found.map { it.name },
            hasLocation = found.any { namesLocation(bytes, it) },
            removedBytes = 0L,
        )
    }

    /**
     * Whether a metadata segment actually names a place.
     *
     * Deciding this from the segment's *label* would flag every photo that has
     * any EXIF at all. The bytes are the honest answer: a GPS IFD, an XMP block
     * with coordinates, or a geotag written as text all say so in ASCII, and a
     * photo that merely records its camera does not.
     */
    private fun namesLocation(bytes: ByteArray, segment: Segment): Boolean {
        val length = (segment.end - segment.start).coerceAtLeast(0)
        if (length == 0 || segment.start + length > bytes.size) return false
        val text = String(bytes, segment.start, length, Charsets.ISO_8859_1)
        return text.contains("GPS", ignoreCase = true) ||
            text.contains("latitude", ignoreCase = true) ||
            text.contains("geo:", ignoreCase = true) ||
            text.contains("exif:GPS", ignoreCase = true)
    }

    // ------------------------------------------------------------------ jpeg

    private data class Segment(val name: String, val start: Int, val end: Int)

    /**
     * JPEG is `FFxx` markers with a two-byte length, from SOI to the start of
     * scan. Everything before SOS is structure plus metadata, and the metadata
     * markers are the ones with no effect on the picture.
     */
    private fun stripJpeg(bytes: ByteArray, onProgress: (Long) -> Unit): Pair<ByteArray, List<Segment>> {
        if (bytes.size < 4 || (bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) {
            throw IllegalArgumentException("Not a JPEG")
        }
        val output = ByteArrayOutputStream(bytes.size)
        output.write(bytes, 0, 2) // SOI
        val removed = mutableListOf<Segment>()
        var index = 2

        while (index + 1 < bytes.size) {
            val marker = bytes[index].toInt() and 0xFF
            if (marker != 0xFF) break
            val code = bytes[index + 1].toInt() and 0xFF
            // Start of scan: everything after this is entropy-coded image data.
            if (code == 0xDA || code == 0xD9) break
            if (code == 0x01 || (code in 0xD0..0xD7)) {
                output.write(bytes, index, 2)
                index += 2
                continue
            }
            if (index + 3 >= bytes.size) break
            val length = ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)
            val end = (index + 2 + length).coerceAtMost(bytes.size)

            val name = when (code) {
                0xE1 -> "EXIF / XMP"
                0xED -> "Photoshop (IPTC)"
                0xFE -> "Comment"
                else -> "APP$code"
            }
            // Only the personal segments go. APP2 carries the ICC colour
            // profile and APP14 the Adobe transform: dropping either changes
            // how the photo looks, which is not what "clean metadata" means.
            val metadata = code == 0xE1 || code == 0xED || code == 0xFE
            if (metadata) {
                removed += Segment(name, index, end)
            } else {
                output.write(bytes, index, end - index)
            }
            onProgress(end.toLong())
            index = end
        }
        // The image data and everything after it, untouched.
        if (index < bytes.size) output.write(bytes, index, bytes.size - index)
        return output.toByteArray() to removed
    }

    private fun scanJpeg(bytes: ByteArray): List<Segment> {
        val found = mutableListOf<Segment>()
        if (bytes.size < 4) return found
        var index = 2
        while (index + 1 < bytes.size) {
            val marker = bytes[index].toInt() and 0xFF
            if (marker != 0xFF) break
            val code = bytes[index + 1].toInt() and 0xFF
            if (code == 0xDA || code == 0xD9) break
            if (code == 0x01 || (code in 0xD0..0xD7)) {
                index += 2
                continue
            }
            if (index + 3 >= bytes.size) break
            val length = ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)
            val end = (index + 2 + length).coerceAtMost(bytes.size)
            val payload = String(bytes, index + 2, (end - (index + 2)).coerceAtLeast(0), Charsets.ISO_8859_1)
            when (code) {
                0xE1 -> found += Segment(
                    if (payload.contains("http://ns.adobe.com/xap", true)) "XMP" else "EXIF (may include GPS)",
                    index,
                    end,
                )
                0xED -> found += Segment("IPTC", index, end)
                0xFE -> found += Segment("Comment", index, end)
            }
            index = end
        }
        return found
    }

    // ------------------------------------------------------------------- png

    private fun stripPng(bytes: ByteArray, onProgress: (Long) -> Unit): Pair<ByteArray, List<Segment>> {
        if (bytes.size < 8 || bytes[0] != 0x89.toByte() || bytes[1] != 'P'.code.toByte()) {
            throw IllegalArgumentException("Not a PNG")
        }
        val output = ByteArrayOutputStream(bytes.size)
        output.write(bytes, 0, 8) // signature
        val removed = mutableListOf<Segment>()
        var index = 8

        while (index + 8 <= bytes.size) {
            val length = readInt(bytes, index)
            val type = String(bytes, index + 4, 4, Charsets.ISO_8859_1)
            val end = (index + 12 + length).coerceAtMost(bytes.size)
            if (type in PNG_METADATA_CHUNKS) {
                removed += Segment(chunkLabel(type), index, end)
            } else {
                output.write(bytes, index, end - index)
            }
            onProgress(end.toLong())
            index = end
            if (type == "IEND") break
        }
        return output.toByteArray() to removed
    }

    private fun scanPng(bytes: ByteArray): List<Segment> {
        val found = mutableListOf<Segment>()
        var index = 8
        while (index + 8 <= bytes.size) {
            val length = readInt(bytes, index)
            val type = String(bytes, index + 4, 4, Charsets.ISO_8859_1)
            val end = (index + 12 + length).coerceAtMost(bytes.size)
            if (type in PNG_METADATA_CHUNKS) found += Segment(chunkLabel(type), index, end)
            index = end
            if (type == "IEND") break
        }
        return found
    }

    private fun chunkLabel(type: String): String = when (type) {
        "eXIf" -> "EXIF (including GPS)"
        "tEXt", "iTXt", "zTXt" -> "Text chunk"
        "tIME" -> "Timestamp"
        else -> type
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    // ---------------------------------------------------------------- shared

    private fun formatOf(file: File): String {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "jpeg"
            "png" -> "png"
            "webp" -> "webp"
            else -> extension
        }
    }

    private fun readAll(file: File): ByteArray {
        val length = file.length()
        if (length > MAX_BYTES) {
            throw IllegalArgumentException("${file.name} is too large to clean in one pass")
        }
        return file.inputStream().use { it.readBytes() }
    }

    companion object {
        /**
         * The formats whose metadata can be removed without re-encoding. WebP
         * keeps its metadata in RIFF chunks with a different layout, so it is
         * left alone rather than half-handled.
         */
        fun canStrip(file: File): Boolean =
            file.name.substringAfterLast('.', "").lowercase() in STRIPPABLE

        private val STRIPPABLE = setOf("jpg", "jpeg", "png")
        val PNG_METADATA_CHUNKS = setOf("tEXt", "iTXt", "zTXt", "eXIf", "tIME")
        const val MAX_BYTES = 512L * 1024 * 1024
    }
}
