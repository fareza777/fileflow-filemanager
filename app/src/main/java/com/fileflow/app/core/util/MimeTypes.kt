package com.fileflow.app.core.util

import com.fileflow.app.core.model.FileCategory

/**
 * Pure-Kotlin type knowledge, kept free of Android classes so the rule engine
 * and the rename heuristics stay unit-testable on the JVM.
 */
object MimeTypes {

    private val EXTENSION_TO_CATEGORY: Map<String, FileCategory> = buildMap {
        fun add(category: FileCategory, vararg extensions: String) {
            extensions.forEach { put(it, category) }
        }
        add(
            FileCategory.IMAGES,
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff",
            "svg", "raw", "dng", "cr2", "nef", "arw", "avif", "ico",
        )
        add(
            FileCategory.VIDEOS,
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v", "mpg",
            "mpeg", "ts", "m2ts", "vob", "rmvb", "ogv",
        )
        add(
            FileCategory.AUDIO,
            "mp3", "wav", "flac", "aac", "ogg", "oga", "opus", "m4a", "wma", "amr",
            "aiff", "mid", "midi", "mka", "ape",
        )
        add(
            FileCategory.DOCUMENTS,
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt",
            "ods", "odp", "csv", "md", "epub", "mobi", "pages", "numbers", "key",
            "json", "xml", "yaml", "yml",
        )
        add(
            FileCategory.ARCHIVES,
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "lz", "lzma", "zst", "iso",
            "cab", "tgz", "jar", "war",
        )
        add(FileCategory.APPS, "apk", "apks", "xapk", "aab", "apkm")
        add(
            FileCategory.CODE,
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "cpp", "h",
            "hpp", "cs", "go", "rs", "rb", "php", "swift", "sh", "bat", "ps1", "sql",
            "html", "css", "scss", "dart", "lua", "gradle", "toml",
        )
    }

    private val MIME_FALLBACK: Map<String, String> = buildMap {
        put("jpg", "image/jpeg"); put("jpeg", "image/jpeg"); put("png", "image/png")
        put("gif", "image/gif"); put("webp", "image/webp"); put("bmp", "image/bmp")
        put("heic", "image/heic"); put("heif", "image/heif"); put("svg", "image/svg+xml")
        put("tif", "image/tiff"); put("tiff", "image/tiff"); put("avif", "image/avif")
        put("mp4", "video/mp4"); put("mkv", "video/x-matroska"); put("avi", "video/x-msvideo")
        put("mov", "video/quicktime"); put("webm", "video/webm"); put("3gp", "video/3gpp")
        put("m4v", "video/x-m4v"); put("wmv", "video/x-ms-wmv"); put("flv", "video/x-flv")
        put("mp3", "audio/mpeg"); put("wav", "audio/wav"); put("flac", "audio/flac")
        put("aac", "audio/aac"); put("ogg", "audio/ogg"); put("opus", "audio/opus")
        put("m4a", "audio/mp4"); put("wma", "audio/x-ms-wma"); put("amr", "audio/amr")
        put("pdf", "application/pdf")
        put("doc", "application/msword")
        put(
            "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
        put("xls", "application/vnd.ms-excel")
        put(
            "xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )
        put("ppt", "application/vnd.ms-powerpoint")
        put(
            "pptx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        )
        put("txt", "text/plain"); put("csv", "text/csv"); put("json", "application/json")
        put("xml", "text/xml"); put("md", "text/markdown"); put("rtf", "application/rtf")
        put("epub", "application/epub+zip")
        put("zip", "application/zip"); put("rar", "application/vnd.rar")
        put("7z", "application/x-7z-compressed"); put("tar", "application/x-tar")
        put("gz", "application/gzip"); put("iso", "application/x-iso9660-image")
        put("apk", "application/vnd.android.package-archive")
        put("aab", "application/x-authorware-bin")
        put("html", "text/html"); put("css", "text/css"); put("js", "text/javascript")
        put("kt", "text/x-kotlin"); put("java", "text/x-java"); put("py", "text/x-python")
        put("json5", "application/json")
    }

    /**
     * Signatures are checked longest-first so container formats that share a
     * prefix (zip vs apk vs docx) resolve to the most specific match.
     */
    private data class Signature(val extension: String, val bytes: IntArray, val offset: Int = 0)

    private val SIGNATURES: List<Signature> = listOf(
        Signature("png", intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        Signature("jpg", intArrayOf(0xFF, 0xD8, 0xFF)),
        Signature("gif", intArrayOf(0x47, 0x49, 0x46, 0x38)),
        Signature("bmp", intArrayOf(0x42, 0x4D)),
        Signature("tiff", intArrayOf(0x49, 0x49, 0x2A, 0x00)),
        Signature("tiff", intArrayOf(0x4D, 0x4D, 0x00, 0x2A)),
        Signature("pdf", intArrayOf(0x25, 0x50, 0x44, 0x46)),
        Signature("rar", intArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)),
        Signature("7z", intArrayOf(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)),
        Signature("gz", intArrayOf(0x1F, 0x8B)),
        Signature("flac", intArrayOf(0x66, 0x4C, 0x61, 0x43)),
        Signature("ogg", intArrayOf(0x4F, 0x67, 0x67, 0x53)),
        Signature("wav", intArrayOf(0x57, 0x41, 0x56, 0x45), offset = 8),
        Signature("avi", intArrayOf(0x41, 0x56, 0x49), offset = 8),
        Signature("webp", intArrayOf(0x57, 0x45, 0x42, 0x50), offset = 8),
        Signature("mp3", intArrayOf(0x49, 0x44, 0x33)),
        Signature("mp4", intArrayOf(0x66, 0x74, 0x79, 0x70), offset = 4),
        Signature("heic", intArrayOf(0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63), offset = 4),
    )

    private val ZIP_MAGIC = intArrayOf(0x50, 0x4B)
    private val ZIP_ENTRY_MARKERS = mapOf(
        "apk" to "AndroidManifest.xml",
        "docx" to "word/",
        "xlsx" to "xl/",
        "pptx" to "ppt/",
        "epub" to "mimetype",
    )

    fun normalizeExtension(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().removePrefix(".").lowercase()
    }

    fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return ""
        val candidate = fileName.substring(dot + 1)
        // A trailing "2023" or "v2" is not an extension.
        if (candidate.length > 8 || candidate.any { it.isWhitespace() }) return ""
        if (candidate.all { it.isDigit() }) return ""
        return candidate.lowercase()
    }

    fun categoryOf(extension: String): FileCategory =
        EXTENSION_TO_CATEGORY[normalizeExtension(extension)] ?: FileCategory.OTHER

    /** Every extension that belongs to a category, for index-backed filtering. */
    fun extensionsFor(category: FileCategory): List<String> =
        EXTENSION_TO_CATEGORY.filterValues { it == category }.keys.toList()

    fun mimeFor(extension: String): String =
        MIME_FALLBACK[normalizeExtension(extension)] ?: "application/octet-stream"

    fun isKnownExtension(extension: String): Boolean =
        MIME_FALLBACK.containsKey(normalizeExtension(extension))

    fun signatureExtension(header: ByteArray): String? {
        if (header.isEmpty()) return null

        for (signature in SIGNATURES) {
            if (matches(header, signature.bytes, signature.offset)) return signature.extension
        }
        if (matches(header, ZIP_MAGIC, 0)) {
            val text = String(header, Charsets.ISO_8859_1)
            ZIP_ENTRY_MARKERS.forEach { (extension, marker) ->
                if (text.contains(marker)) return extension
            }
            return "zip"
        }
        // RIFF....WEBP handled above; anything else RIFF is most likely WAV.
        if (matches(header, intArrayOf(0x52, 0x49, 0x46, 0x46), 0)) return "wav"
        return null
    }

    private fun matches(header: ByteArray, pattern: IntArray, offset: Int): Boolean {
        if (pattern.isEmpty()) return false
        if (header.size < offset + pattern.size) return false
        for (i in pattern.indices) {
            if (header[offset + i].toInt() and 0xFF != pattern[i]) return false
        }
        return true
    }
}
