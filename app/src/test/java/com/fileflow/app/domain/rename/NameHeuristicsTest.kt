package com.fileflow.app.domain.rename

import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.data.prefs.RenameConvention
import com.fileflow.app.domain.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameHeuristicsTest {

    // 2023-01-01 12:00:00 UTC, expressed as a local instant the heuristics
    // will read back through the default zone.
    private val modified = 1_672_567_200_000L

    private fun candidate(
        name: String,
        path: String = "/storage/emulated/0/Download/$name",
        lastModified: Long = modified,
    ): Candidate {
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0) name.substring(dot + 1).lowercase() else ""
        return Candidate(
            path = path,
            name = name,
            extension = extension,
            category = if (extension.isEmpty()) FileCategory.OTHER
            else com.fileflow.app.core.util.MimeTypes.categoryOf(extension),
            sizeBytes = 1024,
            lastModified = lastModified,
            isHidden = name.startsWith("."),
            isDirectory = false,
        )
    }

    private fun suggest(
        name: String,
        convention: RenameConvention = RenameConvention.KEEP,
        datePrefix: Boolean = false,
        header: ByteArray? = null,
        path: String? = null,
    ): NameSuggestion? = NameHeuristics.suggest(
        candidate = if (path != null) candidate("x", path = path).copy(name = name) else candidate(name),
        convention = convention,
        datePrefix = datePrefix,
        header = header,
    )

    // ------------------------------------------------------------ junk names

    @Test
    fun `duplicate download marker is removed`() {
        val suggestion = suggest("report (1).pdf")
        assertNotNull(suggestion)
        assertEquals("report", suggestion!!.baseName)
        assertTrue(RenameReason.DUPLICATE_SUFFIX in suggestion.reasons)
        assertEquals(Confidence.HIGH, suggestion.confidence)
    }

    @Test
    fun `copy suffix is removed`() {
        val suggestion = suggest("Budget - Copy (2).xlsx")
        assertNotNull(suggestion)
        assertEquals("Budget", suggestion!!.baseName)
    }

    @Test
    fun `doubled extension is collapsed`() {
        val suggestion = suggest("letter.pdf.pdf")
        assertNotNull(suggestion)
        assertEquals("letter", suggestion!!.baseName)
        assertEquals("pdf", suggestion.extension)
    }

    @Test
    fun `repeated separators collapse but the words survive`() {
        val suggestion = suggest("my___file---v2.txt")
        assertNotNull(suggestion)
        assertEquals("my_file-v2", suggestion!!.baseName)
    }

    @Test
    fun `a name with nothing wrong produces no suggestion`() {
        assertNull(suggest("holiday-photo.jpg"))
        assertNull(suggest("Quarterly Report.pdf"))
    }

    // --------------------------------------------------------------- dates

    @Test
    fun `camera name keeps name but surfaces the date`() {
        val suggestion = suggest("IMG_20230101_120000.jpg")
        assertNotNull(suggestion)
        assertEquals("2023-01-01 12-00-00", suggestion!!.baseName)
        assertTrue(RenameReason.DATE in suggestion.reasons)
    }

    @Test
    fun `whatsapp style name keeps the meaningful tail`() {
        val suggestion = suggest("IMG-20230101-WA0004.jpg")
        assertNotNull(suggestion)
        assertTrue(suggestion!!.baseName.startsWith("2023-01-01"))
        // "WA0004" is noise and must not survive.
        assertTrue(!suggestion.baseName.contains("WA", ignoreCase = true))
    }

    @Test
    fun `date with separators and time is normalised`() {
        val suggestion = suggest("Screenshot_2023-01-01-12-30-45.png")
        assertNotNull(suggestion)
        assertEquals("2023-01-01 12-30-45", suggestion!!.baseName)
    }

    @Test
    fun `an impossible date is left alone`() {
        val suggestion = suggest("report-20239999.pdf")
        // No date transform should fire on a nonsense month.
        assertTrue(suggestion == null || RenameReason.DATE !in suggestion.reasons)
    }

    // ---------------------------------------------------------------- opaque

    @Test
    fun `hex hash is replaced with folder and date`() {
        val suggestion = suggest("a3f9c1b2d4e5f6078899aabbccddeeff.pdf")
        assertNotNull(suggestion)
        assertTrue(RenameReason.RANDOM in suggestion!!.reasons)
        assertTrue(suggestion.baseName.contains("Download"))
    }

    @Test
    fun `uuid name is treated as opaque`() {
        val suggestion = suggest("550e8400-e29b-41d4-a716-446655440000.bin")
        assertNotNull(suggestion)
        assertTrue(RenameReason.RANDOM in suggestion!!.reasons)
    }

    // ------------------------------------------------------------ extension

    @Test
    fun `a png pretending to be txt is corrected`() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val suggestion = suggest("scan.txt", header = pngHeader)
        assertNotNull(suggestion)
        assertEquals("png", suggestion!!.extension)
        assertTrue(RenameReason.EXTENSION in suggestion.reasons)
    }

    @Test
    fun `jpg stays jpg because it is the same family`() {
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val suggestion = suggest("holiday.jpeg", header = jpegHeader)
        assertNull(suggestion)
    }

    @Test
    fun `a missing extension is filled in from the file header`() {
        val pdfHeader = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        val suggestion = suggest("invoice", header = pdfHeader)
        assertNotNull(suggestion)
        assertEquals("pdf", suggestion!!.extension)
    }

    // ------------------------------------------------------------ convention

    @Test
    fun `kebab convention lowercases and joins words`() {
        assertEquals("my-monthly-report", NameHeuristics.applyConvention("My Monthly Report", RenameConvention.KEBAB))
    }

    @Test
    fun `snake convention keeps digits attached to words`() {
        assertEquals("budget_2024_q1", NameHeuristics.applyConvention("budget 2024 Q1", RenameConvention.SNAKE))
    }

    @Test
    fun `camel case is split before a style is applied`() {
        assertEquals(
            "monthly-report-v2",
            NameHeuristics.applyConvention("monthlyReportV2", RenameConvention.KEBAB),
        )
    }

    @Test
    fun `acronyms survive title case`() {
        assertEquals(
            "Invoice PDF January",
            NameHeuristics.applyConvention("invoice PDF january", RenameConvention.TITLE_CASE),
        )
    }

    @Test
    fun `keep convention leaves a name that is already readable alone`() {
        assertEquals("my_file", NameHeuristics.applyConvention("my_file", RenameConvention.KEEP))
        assertNull(suggest("my_report_2024.pdf"))
    }

    @Test
    fun `date prefix uses the file date when the name has none`() {
        val suggestion = suggest("holiday-photo.jpg", datePrefix = true)
        assertNotNull(suggestion)
        assertTrue(suggestion!!.baseName.startsWith("2023-01-01"))
        assertTrue(suggestion.baseName.endsWith("holiday-photo"))
    }

    @Test
    fun `directories and dotfiles are never suggested`() {
        assertNull(
            NameHeuristics.suggest(
                candidate("DCIM (1)", path = "/storage/emulated/0/DCIM (1)").copy(isDirectory = true),
                RenameConvention.NATURAL,
                false,
            )
        )
        assertNull(suggest(".nomedia"))
    }
}
