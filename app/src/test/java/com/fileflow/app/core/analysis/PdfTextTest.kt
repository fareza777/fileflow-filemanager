package com.fileflow.app.core.analysis

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PDF extraction, minus the parser. The parts that decide what the index
 * receives — the tidy-up applied to extracted text, the name check, and the
 * limits — are pure, so they are pinned here on the JVM; the parser itself is
 * Android-only and is deliberately not exercised.
 */
class PdfTextTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ------------------------------------------------------ post-processing

    @Test
    fun `line endings are normalised to LF`() {
        val typed = "Hello world.\r\n\r\nSecond paragraph.\r\n"
        assertEquals("Hello world.\n\nSecond paragraph.", PdfText.postProcess(typed, 1_000))

        // A page break from the parser can arrive as a lone CR; it is a line
        // ending all the same.
        assertEquals("one\ntwo", PdfText.postProcess("one\rtwo", 1_000))
    }

    @Test
    fun `three or more blank lines become one`() {
        // The page break between two pages of a PDF usually carries a few
        // empty lines with it.
        assertEquals("top\n\nbottom", PdfText.postProcess("top\n\n\n\n\nbottom", 1_000))

        // Two blank lines are below the threshold and stay as they are: this
        // is a cleanup, not a re-flow.
        assertEquals("top\n\n\nbottom", PdfText.postProcess("top\n\n\nbottom", 1_000))
    }

    @Test
    fun `trailing spaces are trimmed from every line`() {
        val padded = "alpha   \nbeta \n   \ngamma  "
        assertEquals("alpha\nbeta\n\ngamma", PdfText.postProcess(padded, 1_000))
    }

    @Test
    fun `the result is capped at the requested length`() {
        val long = "x".repeat(5_000)
        val capped = PdfText.postProcess(long, 100)!!
        assertEquals(100, capped.length)
        assertEquals(long.take(100), capped)

        // A cap that leaves nothing means there is nothing to index.
        assertNull(PdfText.postProcess(long, 0))
    }

    @Test
    fun `blank input has nothing to index`() {
        assertNull(PdfText.postProcess("", 1_000))
    }

    @Test
    fun `whitespace-only input has nothing to index`() {
        assertNull(PdfText.postProcess("   \n\t\n \n\n", 1_000))
    }

    // ----------------------------------------------------------- name check

    @Test
    fun `the pdf check is case-insensitive`() {
        assertTrue(PdfText.isPdf(File(temp.root, "report.pdf")))
        assertTrue(PdfText.isPdf(File(temp.root, "REPORT.PDF")))
        assertTrue(PdfText.isPdf(File(temp.root, "Scan.PdF")))
    }

    @Test
    fun `a non-pdf name is not a pdf`() {
        assertFalse(PdfText.isPdf(File(temp.root, "notes.txt")))
        assertFalse(PdfText.isPdf(File(temp.root, "pdf")))
        assertFalse(PdfText.isPdf(File(temp.root, "archive.pdf.zip")))
    }

    // ----------------------------------------------------------- index gate

    @Test
    fun `a pdf is indexed by the parser and not by the byte reader`() {
        // Two gates, deliberately different questions: `canExtract` asks whether
        // the bytes can be read as text, which a PDF is not — it is compressed
        // streams and font tables — while `canIndex` asks whether the file is
        // worth indexing, which a PDF very much is. ContentIndexRepository asks
        // the second, so a PDF goes to the parser instead of being decoded as
        // if its container bytes were words.
        val pdf = File(temp.root, "laporan.pdf").apply { writeText("%PDF-1.4 (stub)") }
        assertTrue(TextExtractor.canIndex(pdf))
        assertFalse(TextExtractor.canExtract(pdf))
        assertTrue("pdf" in TextExtractor.INDEXABLE)
        assertFalse("pdf" in TextExtractor.EXTRACTABLE)
    }

    @Test
    fun `nothing else gained a place in the index`() {
        // The extension list is what decides this, so a file that was not
        // indexable before PDF support must not have become indexable with it.
        val image = File(temp.root, "photo.jpg").apply { writeText("not text") }
        val archive = File(temp.root, "backup.zip").apply { writeText("PK stub") }
        assertFalse(TextExtractor.canIndex(image))
        assertFalse(TextExtractor.canIndex(archive))
    }

    // --------------------------------------------------------------- limits

    @Test
    fun `the document limits are the ones the index relies on`() {
        assertEquals(50, PdfText.MAX_PAGES)
        assertEquals(24_000, PdfText.MAX_CHARS)
        assertEquals(32L * 1024 * 1024, PdfText.MAX_FILE_SIZE)

        assertTrue(PdfText.MAX_CHARS > 0)
        // The two readers bound the same thing — how much a single document may
        // add to a table that is scanned with LIKE on every search — and they
        // have to stay in the same order of magnitude, or one format quietly
        // makes the index several times bigger than the other.
        assertTrue(
            "a PDF must not contribute more than a text file is allowed",
            PdfText.MAX_CHARS <= TextExtractor.MAX_INDEX_BYTES,
        )
        // The file-size caps differ on purpose: PDF bytes are mostly packaging
        // and layout around a modest amount of text, and the page and character
        // caps bound the real work.
        assertTrue(PdfText.MAX_FILE_SIZE > TextExtractor.MAX_FILE_BYTES)
    }
}
