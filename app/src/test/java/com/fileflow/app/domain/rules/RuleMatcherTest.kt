package com.fileflow.app.domain.rules

import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.domain.Candidate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleMatcherTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun candidate(
        name: String = "invoice.pdf",
        extension: String = "pdf",
        category: FileCategory = FileCategory.DOCUMENTS,
        size: Long = 1024,
        modified: Long = now - 10 * day,
        path: String = "/storage/emulated/0/Download/$name",
        isDirectory: Boolean = false,
    ) = Candidate(
        path = path,
        name = name,
        extension = extension,
        category = category,
        sizeBytes = size,
        lastModified = modified,
        isHidden = name.startsWith("."),
        isDirectory = isDirectory,
    )

    private fun spec(vararg conditions: MatchCondition, mode: MatchMode = MatchMode.ALL) =
        RuleSpec(conditions = conditions.toList(), matchMode = mode)

    // ------------------------------------------------------------------ name

    @Test
    fun `contains matches case insensitively`() {
        val file = candidate(name = "Invoice-January.pdf")
        assertTrue(RuleMatcher.matches(file, spec(MatchCondition.Name("invoice"))))
    }

    @Test
    fun `starts with respects the anchor`() {
        val condition = MatchCondition.Name("invoice", NameMode.STARTS_WITH)
        assertTrue(RuleMatcher.matches(candidate(name = "invoice-2024.pdf"), spec(condition)))
        assertFalse(RuleMatcher.matches(candidate(name = "my-invoice.pdf"), spec(condition)))
    }

    @Test
    fun `wildcard treats the rest of the name as literal`() {
        val condition = MatchCondition.Name("invoice*.pdf", NameMode.WILDCARD)
        assertTrue(RuleMatcher.matches(candidate(name = "invoice-jan.pdf"), spec(condition)))
        assertTrue(RuleMatcher.matches(candidate(name = "Invoice 2024.pdf"), spec(condition)))
        // The dot in ".pdf" must not behave like any-character.
        assertFalse(RuleMatcher.matches(candidate(name = "invoice-janxpdf"), spec(condition)))
    }

    @Test
    fun `regex condition is evaluated`() {
        val condition = MatchCondition.Name("^IMG_\\d{8}", NameMode.REGEX)
        assertTrue(RuleMatcher.matches(candidate(name = "IMG_20230101_120000.jpg"), spec(condition)))
        assertFalse(RuleMatcher.matches(candidate(name = "PXL_20230101.jpg"), spec(condition)))
    }

    @Test
    fun `invalid regex never matches instead of throwing`() {
        val condition = MatchCondition.Name("([unclosed", NameMode.REGEX)
        assertFalse(RuleMatcher.matches(candidate(), spec(condition)))
        assertFalse(RuleMatcher.isValidRegex("([unclosed"))
    }

    // ------------------------------------------------------------ extensions

    @Test
    fun `extension condition accepts a list and normalises dots`() {
        val condition = MatchCondition.Extension(listOf(".PDF", "docx"))
        assertTrue(RuleMatcher.matches(candidate(extension = "pdf"), spec(condition)))
        assertFalse(RuleMatcher.matches(candidate(extension = "txt"), spec(condition)))
    }

    @Test
    fun `empty extension list is not a constraint`() {
        assertTrue(RuleMatcher.matches(candidate(), spec(MatchCondition.Extension(emptyList()))))
    }

    // ------------------------------------------------------------------ size

    @Test
    fun `size range is inclusive`() {
        val condition = MatchCondition.Size(minBytes = 100, maxBytes = 2000)
        assertTrue(RuleMatcher.matches(candidate(size = 100), spec(condition)))
        assertTrue(RuleMatcher.matches(candidate(size = 2000), spec(condition)))
        assertFalse(RuleMatcher.matches(candidate(size = 99), spec(condition)))
        assertFalse(RuleMatcher.matches(candidate(size = 2001), spec(condition)))
    }

    // ------------------------------------------------------------------- age

    @Test
    fun `older than requires the file to be at least that old`() {
        val condition = MatchCondition.Age(olderThanDays = 30)
        assertTrue(RuleMatcher.matches(candidate(modified = now - 31 * day), spec(condition), now))
        assertFalse(RuleMatcher.matches(candidate(modified = now - 29 * day), spec(condition), now))
    }

    @Test
    fun `newer than requires the file to be recent`() {
        val condition = MatchCondition.Age(newerThanDays = 7)
        assertTrue(RuleMatcher.matches(candidate(modified = now - 2 * day), spec(condition), now))
        assertFalse(RuleMatcher.matches(candidate(modified = now - 8 * day), spec(condition), now))
    }

    // ---------------------------------------------------------------- folder

    @Test
    fun `a bare folder name matches any path segment`() {
        val condition = MatchCondition.SourceFolder(listOf("WhatsApp"))
        assertTrue(
            RuleMatcher.matches(
                candidate(path = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/a.pdf"),
                spec(condition),
            )
        )
        assertFalse(
            RuleMatcher.matches(
                candidate(path = "/storage/emulated/0/Download/a.pdf"),
                spec(condition),
            )
        )
    }

    @Test
    fun `absolute folder matches everything beneath it`() {
        val condition = MatchCondition.SourceFolder(listOf("/storage/emulated/0/Download"))
        assertTrue(
            RuleMatcher.matches(
                candidate(path = "/storage/emulated/0/Download/2024/a.pdf"),
                spec(condition),
            )
        )
        assertFalse(
            RuleMatcher.matches(
                candidate(path = "/storage/emulated/0/Documents/a.pdf"),
                spec(condition),
            )
        )
    }

    // ------------------------------------------------------------- combinator

    @Test
    fun `all mode requires every condition`() {
        val rule = spec(
            MatchCondition.Extension(listOf("pdf")),
            MatchCondition.SourceFolder(listOf("WhatsApp")),
        )
        assertTrue(
            RuleMatcher.matches(
                candidate(path = "/storage/emulated/0/WhatsApp/a.pdf"),
                rule,
            )
        )
        assertFalse(
            RuleMatcher.matches(
                candidate(path = "/storage/emulated/0/Download/a.pdf"),
                rule,
            )
        )
    }

    @Test
    fun `any mode needs only one condition`() {
        val rule = spec(
            MatchCondition.Extension(listOf("apk")),
            MatchCondition.SourceFolder(listOf("Download")),
            mode = MatchMode.ANY,
        )
        assertTrue(
            RuleMatcher.matches(candidate(extension = "apk", path = "/x/Elsewhere/a.apk"), rule)
        )
        assertTrue(
            RuleMatcher.matches(
                candidate(extension = "txt", path = "/storage/emulated/0/Download/a.txt"),
                rule,
            )
        )
        assertFalse(
            RuleMatcher.matches(
                candidate(extension = "txt", path = "/storage/emulated/0/Documents/a.txt"),
                rule,
            )
        )
    }

    @Test
    fun `negate flips a single condition`() {
        val condition = MatchCondition.Extension(listOf("apk"), negate = true)
        assertTrue(RuleMatcher.matches(candidate(extension = "pdf"), spec(condition)))
        assertFalse(RuleMatcher.matches(candidate(extension = "apk"), spec(condition)))
    }

    @Test
    fun `directories are never matched`() {
        assertFalse(
            RuleMatcher.matches(
                candidate(name = "Folder", isDirectory = true, extension = ""),
                spec(MatchCondition.Name("folder")),
            )
        )
    }

    @Test
    fun `a rule with no conditions matches nothing`() {
        assertFalse(RuleMatcher.matches(candidate(), RuleSpec()))
    }
}
