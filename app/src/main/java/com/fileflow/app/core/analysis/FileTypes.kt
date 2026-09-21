package com.fileflow.app.core.analysis

import java.io.File
import java.io.RandomAccessFile

/** What a file actually is, as opposed to what its name claims. */
@androidx.compose.runtime.Immutable
data class TypeVerdict(
    val format: String,
    val description: String,
    val mimeType: String,
    /** True when the extension does not match the bytes. */
    val extensionLies: Boolean,
    /** Null when the signature is unknown. */
    val detectedExtension: String?,
    val category: FileCategoryish,
) {
    val unknown: Boolean get() = format == UNKNOWN
    companion object {
        const val UNKNOWN = "unknown"
    }
}

/** A coarse grouping, so a report can be summarised without a second table. */
enum class FileCategoryish { IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, EXECUTABLE, DATABASE, FONT, TEXT, OTHER }

/**
 * True file type detection from the bytes.
 *
 * The extension is a hint written by whoever named the file — a renamed
 * installer, a photo pasted into a document, a text file with a .jpg name — and
 * the only trustworthy answer is in the first few bytes. This is the same idea
 * as Siegfried's PRONOM signatures, trimmed to the formats a phone actually
 * meets: a container signature at an offset, plus a text/plain fallback.
 *
 * Everything is checked against a bounded read: a signature 4 MB into a file is
 * not a signature anybody needs, and reading whole files to identify them is
 * how a "scan" turns into a minute of IO.
 */
object FileTypes {

    private class Signature(
        val offset: Int,
        val bytes: ByteArray? = null,
        val mask: ByteArray? = null,
        val ascii: String? = null,
        val format: String,
        val description: String,
        val mime: String,
        val extension: String,
        val category: FileCategoryish,
    )

    private fun at(offset: Int, ascii: String, format: String, description: String, mime: String, extension: String, category: FileCategoryish) =
        Signature(offset = offset, ascii = ascii, format = format, description = description, mime = mime, extension = extension, category = category)

    private fun at(offset: Int, bytes: IntArray, format: String, description: String, mime: String, extension: String, category: FileCategoryish) =
        Signature(offset = offset, bytes = ByteArray(bytes.size) { bytes[it].toByte() }, format = format, description = description, mime = mime, extension = extension, category = category)

    /**
     * Ordered so the most specific signature wins: a zip container has to be
     * checked before the plain zip entry, or every docx would be a zip.
     */
    private val SIGNATURES: List<Signature> = listOf(
        // Documents that are really zips, first.
        at(0, "PK\u0003\u0004", "zip", "Zip archive", "application/zip", "zip", FileCategoryish.ARCHIVE),
        at(0, "{\\rtf", "rtf", "Rich text", "application/rtf", "rtf", FileCategoryish.DOCUMENT),
        at(0, "%PDF-", "pdf", "PDF document", "application/pdf", "pdf", FileCategoryish.DOCUMENT),

        // Images
        at(0, intArrayOf(0xFF, 0xD8, 0xFF), "jpeg", "JPEG image", "image/jpeg", "jpg", FileCategoryish.IMAGE),
        at(0, intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), "png", "PNG image", "image/png", "png", FileCategoryish.IMAGE),
        at(0, "GIF87a", "gif", "GIF image", "image/gif", "gif", FileCategoryish.IMAGE),
        at(0, "GIF89a", "gif", "GIF image", "image/gif", "gif", FileCategoryish.IMAGE),
        at(0, "BM", "bmp", "Bitmap image", "image/bmp", "bmp", FileCategoryish.IMAGE),
        at(0, intArrayOf(0x49, 0x49, 0x2A, 0x00), "tiff", "TIFF image", "image/tiff", "tiff", FileCategoryish.IMAGE),
        at(0, intArrayOf(0x4D, 0x4D, 0x00, 0x2A), "tiff", "TIFF image", "image/tiff", "tiff", FileCategoryish.IMAGE),
        at(4, "ftypheic", "heic", "HEIC image", "image/heic", "heic", FileCategoryish.IMAGE),
        at(4, "ftypheix", "heic", "HEIC image", "image/heic", "heic", FileCategoryish.IMAGE),
        at(4, "ftypmif1", "heif", "HEIF image", "image/heif", "heif", FileCategoryish.IMAGE),
        at(8, "WEBP", "webp", "WebP image", "image/webp", "webp", FileCategoryish.IMAGE),
        at(0, "<?xml", "xml", "XML document", "application/xml", "xml", FileCategoryish.DOCUMENT),

        // Video and audio containers
        at(4, "ftypisom", "mp4", "MP4 video", "video/mp4", "mp4", FileCategoryish.VIDEO),
        at(4, "ftypmp42", "mp4", "MP4 video", "video/mp4", "mp4", FileCategoryish.VIDEO),
        at(4, "ftypM4V", "m4v", "M4V video", "video/x-m4v", "m4v", FileCategoryish.VIDEO),
        at(4, "ftypqt", "mov", "QuickTime video", "video/quicktime", "mov", FileCategoryish.VIDEO),
        at(0, intArrayOf(0x1A, 0x45, 0xDF, 0xA3), "matroska", "Matroska / WebM", "video/x-matroska", "mkv", FileCategoryish.VIDEO),
        at(0, "RIFF", "riff", "RIFF container (AVI/WAV/WebP)", "application/octet-stream", "riff", FileCategoryish.OTHER),
        at(0, "OggS", "ogg", "Ogg stream", "audio/ogg", "ogg", FileCategoryish.AUDIO),
        at(0, "fLaC", "flac", "FLAC audio", "audio/flac", "flac", FileCategoryish.AUDIO),
        at(0, "ID3", "mp3", "MP3 audio", "audio/mpeg", "mp3", FileCategoryish.AUDIO),
        at(0, intArrayOf(0xFF, 0xFB), "mp3", "MP3 audio", "audio/mpeg", "mp3", FileCategoryish.AUDIO),

        // Archives and packages
        at(0, "Rar!\u001A\u0007", "rar", "RAR archive", "application/vnd.rar", "rar", FileCategoryish.ARCHIVE),
        at(0, "7z\u00BC\u00AF\u0027\u001C", "7z", "7-Zip archive", "application/x-7z-compressed", "7z", FileCategoryish.ARCHIVE),
        at(0, intArrayOf(0x1F, 0x8B), "gzip", "Gzip stream", "application/gzip", "gz", FileCategoryish.ARCHIVE),
        at(0, intArrayOf(0x28, 0xB5, 0x2F, 0xFD), "zstd", "Zstandard stream", "application/zstd", "zst", FileCategoryish.ARCHIVE),
        at(0, intArrayOf(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00), "xz", "XZ stream", "application/x-xz", "xz", FileCategoryish.ARCHIVE),
        at(0, "BZh", "bzip2", "Bzip2 stream", "application/x-bzip2", "bz2", FileCategoryish.ARCHIVE),
        at(257, "ustar", "tar", "Tar archive", "application/x-tar", "tar", FileCategoryish.ARCHIVE),

        // Executables and databases
        at(0, intArrayOf(0x7F, 0x45, 0x4C, 0x46), "elf", "ELF binary", "application/x-elf", "elf", FileCategoryish.EXECUTABLE),
        at(0, "SQLite format 3\u0000", "sqlite", "SQLite database", "application/vnd.sqlite3", "db", FileCategoryish.DATABASE),
        at(0, intArrayOf(0xCA, 0xFE, 0xBA, 0xBE), "class", "Java class", "application/java-vm", "class", FileCategoryish.EXECUTABLE),
        at(0, intArrayOf(0xD0, 0xCF, 0x11, 0xE0), "ole", "Legacy Office document", "application/msword", "doc", FileCategoryish.DOCUMENT),

        // Fonts
        at(0, intArrayOf(0x00, 0x01, 0x00, 0x00), "ttf", "TrueType font", "font/ttf", "ttf", FileCategoryish.FONT),
        at(0, "OTTO", "otf", "OpenType font", "font/otf", "otf", FileCategoryish.FONT),
        at(0, "wOFF", "woff", "WOFF font", "font/woff", "woff", FileCategoryish.FONT),
    )

    /**
     * What the bytes say this file is. [name] is only used to say whether the
     * extension agrees, never to decide the answer.
     */
    fun detect(file: File, header: ByteArray? = null): TypeVerdict {
        val bytes = header ?: readHeader(file)
        if (bytes.isEmpty()) {
            return TypeVerdict(
                format = TypeVerdict.UNKNOWN,
                description = "Empty or unreadable",
                mimeType = "application/octet-stream",
                extensionLies = false,
                detectedExtension = null,
                category = FileCategoryish.OTHER,
            )
        }

        val declared = file.name.substringAfterLast('.', "").lowercase()
        SIGNATURES.forEach { signature ->
            if (matches(bytes, signature)) {
                val detected = signature.extension
                return TypeVerdict(
                    format = signature.format,
                    description = signature.description,
                    mimeType = signature.mime,
                    // An extension that differs is worth saying out loud; an
                    // unknown extension is not a lie, it is a missing label.
                    extensionLies = declared.isNotEmpty() && !extensionMatches(signature, declared),
                    detectedExtension = detected,
                    category = signature.category,
                )
            }
        }

        if (isProbablyText(bytes)) {
            return TypeVerdict(
                format = "text",
                description = "Text",
                mimeType = "text/plain",
                extensionLies = declared in BINARY_EXTENSIONS,
                detectedExtension = "txt",
                category = FileCategoryish.TEXT,
            )
        }

        return TypeVerdict(
            format = TypeVerdict.UNKNOWN,
            description = "Unrecognised data",
            mimeType = "application/octet-stream",
            extensionLies = declared in KNOWN_EXTENSIONS,
            detectedExtension = null,
            category = FileCategoryish.OTHER,
        )
    }

    private fun matches(bytes: ByteArray, signature: Signature): Boolean {
        val ascii = signature.ascii
        if (ascii != null) {
            val needle = ascii.toByteArray(Charsets.ISO_8859_1)
            if (signature.offset + needle.size > bytes.size) return false
            for (index in needle.indices) {
                if (bytes[signature.offset + index] != needle[index]) return false
            }
            return true
        }
        val expected = signature.bytes ?: return false
        if (signature.offset + expected.size > bytes.size) return false
        for (index in expected.indices) {
            if (bytes[signature.offset + index] != expected[index]) return false
        }
        return true
    }

    private fun extensionMatches(signature: Signature, declared: String): Boolean {
        val detected = signature.extension
        if (declared == detected) return true
        return when (signature.format) {
            "jpeg" -> declared in setOf("jpg", "jpeg", "jpe")
            "heic" -> declared in setOf("heic", "heif", "avif")
            "matroska" -> declared in setOf("mkv", "webm", "mka")
            "riff" -> declared in setOf("avi", "wav", "webp", "riff")
            "mp4" -> declared in setOf("mp4", "m4a", "m4v", "mov", "3gp")
            "tiff" -> declared in setOf("tif", "tiff")
            "tar" -> declared in setOf("tar", "tar.gz", "tgz")
            "sqlite" -> declared in setOf("db", "sqlite", "sqlite3")
            "elf" -> declared in setOf("elf", "so", "bin")
            "ole" -> declared in setOf("doc", "xls", "ppt", "msi")
            "zip" -> declared in setOf(
                "zip", "apk", "xapk", "jar", "docx", "xlsx", "pptx", "epub", "kml", "kmz",
            )
            else -> false
        }
    }

    /** UTF-8 without a BOM, no NUL bytes, and no control characters to speak of. */
    private fun isProbablyText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var printable = 0
        var checked = 0
        bytes.forEach { byte ->
            checked++
            val value = byte.toInt() and 0xFF
            when {
                value == 0 -> return false
                value == 9 || value == 10 || value == 13 -> printable++
                value in 32..126 -> printable++
                value >= 0xC2 -> printable++ // a UTF-8 continuation start
            }
        }
        return checked > 0 && printable.toFloat() / checked > 0.9f
    }

    private fun readHeader(file: File): ByteArray {
        val length = minOf(file.length(), HEADER_BYTES.toLong()).toInt()
        if (length <= 0) return ByteArray(0)
        return runCatching {
            RandomAccessFile(file, "r").use { access ->
                val buffer = ByteArray(length)
                access.readFully(buffer)
                buffer
            }
        }.getOrDefault(ByteArray(0))
    }

    /** Enough to reach the `ustar` marker at 257 and any ftyp brand. */
    private const val HEADER_BYTES = 512

    private val BINARY_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "mp4", "mkv", "mov", "avi",
        "mp3", "flac", "ogg", "zip", "apk", "pdf", "docx", "xlsx", "ttf", "otf", "db",
    )

    private val KNOWN_EXTENSIONS = BINARY_EXTENSIONS
}
