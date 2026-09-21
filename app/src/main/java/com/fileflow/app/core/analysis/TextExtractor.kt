package com.fileflow.app.core.analysis

import java.io.File

/**
 * The text inside a file, when there is any.
 *
 * Content search is only as good as what it can read, and a phone has no
 * document engine: PDF text needs a parser per format, and the formats people
 * actually search — notes, code, CSV, logs, JSON — are plain text already. So
 * this reads what can be read honestly and refuses the rest rather than
 * indexing a binary blob as if it were words.
 *
 * Two limits are deliberate. The read is bounded, because indexing a 2 GB log
 * would cost more than the answer is worth; and the encoding is UTF-8 with a
 * BOM check, because guessing a legacy codepage correctly is a library's job
 * and guessing it wrongly produces confident nonsense.
 */
object TextExtractor {

    /** What can be read as text, and is worth indexing. */
    val EXTRACTABLE = setOf(
        "txt", "log", "md", "markdown", "json", "xml", "yaml", "yml", "csv", "tsv",
        "ini", "conf", "cfg", "properties", "env", "toml", "sql", "html", "htm",
        "css", "scss", "js", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb",
        "go", "rs", "c", "cpp", "h", "hpp", "cs", "php", "swift", "sh", "bat",
        "ps1", "gradle", "srt", "vtt", "svg", "diff", "patch", "gitignore",
    )

    fun canExtract(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (file.length() > MAX_FILE_BYTES) return false
        return file.name.substringAfterLast('.', "").lowercase() in EXTRACTABLE
    }

    /**
     * @return the readable text, or null when the bytes are not text. Empty
     * files and files past [maxBytes] come back as null too: there is nothing
     * to search in either.
     */
    fun extract(file: File, maxBytes: Int = MAX_INDEX_BYTES): String? {
        if (!file.isFile || file.length() <= 0L) return null
        val length = minOf(file.length(), maxBytes.toLong()).toInt()
        val bytes = runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(buffer, read, length - read)
                    if (count <= 0) break
                    read += count
                }
                if (read == length) buffer else buffer.copyOf(read)
            }
        }.getOrNull() ?: return null
        if (bytes.isEmpty() || !looksLikeText(bytes)) return null

        // A byte-order mark is metadata, not content; UTF-8 is assumed after it
        // and invalid sequences are replaced rather than dropping the file.
        val start = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> 3
            else -> 0
        }
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    /**
     * NUL bytes are the giveaway for binary; beyond that, a text file is mostly
     * printable characters. Bytes above 0x80 count as printable because in
     * UTF-8 they are the lead and continuation bytes of a character — treating
     * them as noise made every file with an accent in it look like a binary.
     */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        var printable = 0
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            when {
                value == 0 -> return false
                value == 9 || value == 10 || value == 13 -> printable++
                value in 32..126 -> printable++
                value >= 0x80 -> printable++
            }
        }
        return printable.toFloat() / bytes.size > 0.85f
    }

    /** A bounded amount of a large file is still a searchable document. */
    const val MAX_INDEX_BYTES = 48 * 1024

    /** Past this, reading the file costs more than the index is worth. */
    const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** Where a term was found, for the result row. */
    fun snippet(text: String, terms: List<String>, radius: Int = 60): String {
        if (terms.isEmpty()) return text.take(radius * 2)
        val lower = text.lowercase()
        val hit = terms.minOfOrNull { term -> lower.indexOf(term).takeIf { it >= 0 } ?: Int.MAX_VALUE }
            ?: return text.take(radius * 2)
        if (hit == Int.MAX_VALUE) return text.take(radius * 2)
        val start = (hit - radius).coerceAtLeast(0)
        val end = (hit + radius).coerceAtMost(text.length)
        return buildString {
            if (start > 0) append("… ")
            append(text.substring(start, end).replace('\n', ' ').trim())
            if (end < text.length) append(" …")
        }
    }
}
