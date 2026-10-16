package com.fileflow.app.core.analysis

import java.io.File

/**
 * The text inside a file, when there is any.
 *
 * Content search is only as good as what it can read. Most of what people
 * search — notes, code, CSV, logs, JSON — is plain text, and this reads it
 * honestly, refusing a binary blob rather than indexing it as if it were
 * words. PDF is the exception: its text needs a real parser, so those files
 * are handed to [PdfText] instead.
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

    /** Formats that hold text but need a parser to get it out. */
    val DOCUMENTS = setOf("pdf")

    /** Everything the indexer is willing to look inside. */
    val INDEXABLE = EXTRACTABLE + DOCUMENTS

    /**
     * Whether the plain-text reader can make sense of this file. PDFs are not
     * its job — [canIndex] accepts them, and [extract] sends them to [PdfText].
     */
    fun canExtract(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (file.length() > MAX_FILE_BYTES) return false
        return file.name.substringAfterLast('.', "").lowercase() in EXTRACTABLE
    }

    /**
     * Whether the indexer should look inside this file, whichever reader ends
     * up handling it.
     *
     * The size rule follows the reader: plain text has to fit in
     * [MAX_FILE_BYTES] (8 MB), while a PDF is allowed the larger cap the
     * parser sets for itself, [PdfText.MAX_FILE_SIZE] (32 MB) — PDF bytes are
     * mostly packaging and layout around a modest amount of text. Both are
     * still bounded, so one oversized file cannot stall the index.
     */
    fun canIndex(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return when (file.name.substringAfterLast('.', "").lowercase()) {
            in EXTRACTABLE -> file.length() <= MAX_FILE_BYTES
            in DOCUMENTS -> file.length() <= PdfText.MAX_FILE_SIZE
            else -> false
        }
    }

    /**
     * @return the readable text, or null when the bytes are not text. Empty
     * files and files past [maxBytes] come back as null too: there is nothing
     * to search in either. A PDF is parsed as a document instead of read as
     * bytes, so for those the parser's own limits apply ([PdfText.read]).
     */
    fun extract(file: File, maxBytes: Int = MAX_INDEX_BYTES): String? {
        if (PdfText.isPdf(file)) return PdfText.read(file)
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
