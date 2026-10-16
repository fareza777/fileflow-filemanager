package com.fileflow.app.core.analysis

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The text layer of a PDF, for the content index.
 *
 * PDF is the one document format people actually search, and the one format
 * that cannot be read by looking at its bytes: the text is compressed inside
 * content streams and drawn with fonts that have to be decoded first. So it
 * gets a real parser — the Android port of Apache PDFBox — and this object is
 * the thin layer between that parser and the index.
 *
 * Everything here is defensive on purpose. Parsing a file the user downloaded
 * means parsing a file nobody vetted, so a failure has to mean an unanswered
 * search rather than a crash: [read] catches whatever the parser throws and
 * answers null.
 */
object PdfText {

    /**
     * Points the library at its own resources, once per process.
     *
     * PDFBox decodes font metadata, glyph lists and encoding tables from files
     * shipped inside its artefact, and on Android those are only reachable
     * through the asset manager; without this call some documents extract as
     * gibberish or not at all. The guard makes a second call free, and a
     * failure is swallowed: a font table that cannot be loaded should cost one
     * search result, not a crash.
     */
    fun initialise(context: Context) {
        if (!ready.compareAndSet(false, true)) return
        runCatching { PDFBoxResourceLoader.init(context.applicationContext) }
    }

    /**
     * Whether the name says the file is a PDF.
     *
     * Name only, and case-insensitive, because this runs across a whole file
     * walk before anything is opened. Whether the bytes really are a PDF is
     * the parser's question, and [read] answers it by trying.
     */
    fun isPdf(file: File): Boolean =
        file.name.substringAfterLast('.', "").lowercase() == "pdf"

    /**
     * The text of a PDF, or null when there is none to have.
     *
     * Null covers every way this can fail: a missing, empty or oversized file;
     * an encrypted or password-protected document, which would need a password
     * there is no way to ask for; a corrupt file; and pages that are really
     * images — which is most scanned paper. The read is bounded from both ends
     * — [MAX_PAGES] pages and [maxChars] characters — because a thousand-page
     * manual would stall the index long after its first chapters had said
     * everything search needs to know.
     *
     * It never throws: unreadable input is normal here, and the caller treats
     * every failure the same way.
     */
    fun read(file: File, maxChars: Int = MAX_CHARS): String? {
        if (!file.isFile || file.length() <= 0L) return null
        if (file.length() > MAX_FILE_SIZE) return null

        val raw = runCatching {
            PDDocument.load(file).use { document ->
                if (document.isEncrypted) return@use null

                // Page by page rather than one whole-document call, so the
                // character cap stops the work itself, not just the result.
                val stripper = PDFTextStripper()
                val pages = minOf(document.numberOfPages, MAX_PAGES)
                val text = StringBuilder()
                var page = 1
                while (page <= pages && text.length < maxChars) {
                    stripper.startPage = page
                    stripper.endPage = page
                    text.append(stripper.getText(document))
                    page++
                }
                text.toString()
            }
        }.getOrNull() ?: return null

        return postProcess(raw, maxChars)
    }

    /**
     * Folds the mechanical noise out of what the parser extracted.
     *
     * PDF text comes out laid out, not written: page breaks, empty stretches
     * where table cells were, trailing spaces from justified lines. This turns
     * line endings into LF, trims the end of every line, collapses a run of
     * three or more blank lines into one, and stops there. It deliberately
     * makes no attempt to guess at columns, hyphenation or page numbers — a
     * wrong guess corrupts the very text the user is searching for.
     *
     * Pure on purpose, so it can be unit tested on the JVM — the parser cannot.
     *
     * @return the tidied text, capped at [maxChars], or null when nothing of
     * substance is left.
     */
    fun postProcess(raw: String, maxChars: Int): String? {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        // Trailing spaces go before blank lines are collapsed: a "blank" line
        // with a stray space in it is still a blank line.
        text = text.replace(Regex("[ \\t]+(?=\\n)"), "")
        text = text.replace(Regex("\\n{4,}"), "\n\n")
        text = text.trim()
        val capped = text.take(maxChars.coerceAtLeast(0))
        return if (capped.isBlank()) null else capped
    }

    /**
     * How many pages are read. What a document is about is nearly always said
     * near the front, and parsing an entire manual to prove that adds nothing.
     */
    const val MAX_PAGES = 50

    /**
     * How much extracted text one document may contribute.
     *
     * The same budget as the plain-text reader, in the units this side measures
     * in. [TextExtractor] caps a text file at [TextExtractor.MAX_INDEX_BYTES]
     * bytes; a PDF is capped here in characters, and the two are deliberately
     * the same order of magnitude because the limit is not about the parser, it
     * is about the index: the content table keeps the body in a column that is
     * scanned with `LIKE` on every search, and a thousand documents allowed a
     * couple of hundred kilobytes each would turn that scan into a crawl while
     * buying nothing — what a document is about is settled long before then.
     */
    const val MAX_CHARS = 24_000

    /**
     * The size past which a PDF is not opened at all. Larger than the
     * plain-text cap ([TextExtractor.MAX_FILE_BYTES]) because PDF bytes are
     * mostly packaging and layout around a modest amount of text; the page and
     * character caps still bound the actual work.
     */
    const val MAX_FILE_SIZE = 32L * 1024 * 1024

    /** First [initialise] wins; set eagerly so two threads cannot race. */
    private val ready = AtomicBoolean(false)
}
