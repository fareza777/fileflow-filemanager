package com.fileflow.app.domain.rename

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameTemplateTest {

    // 2023-01-01 12:00:00 UTC. The date tokens read it through the device zone,
    // so every expectation below is built with the same formatter NameTemplate
    // uses rather than with a hardcoded day — otherwise this test would only
    // pass in one timezone.
    private val modified = Instant.parse("2023-01-01T12:00:00Z").toEpochMilli()

    private fun render(
        pattern: String,
        name: String = "holiday.jpg",
        extension: String = "jpg",
        category: String = "images",
        parentName: String = "Camera",
        sequence: Int = 1,
    ): String = NameTemplate.render(
        pattern = pattern,
        name = name,
        extension = extension,
        lastModified = modified,
        category = category,
        parentName = parentName,
        sequence = sequence,
    )

    private fun stamp(pattern: String): String =
        DateTimeFormatter.ofPattern(pattern, Locale.US)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(modified))

    /**
     * The rule every caller applies after rendering: the pattern produces the
     * stem of a name, and the file keeps its own extension unless the pattern
     * already spelled it out.
     */
    private fun complete(rendered: String, extension: String): String =
        if (extension.isNotEmpty() && !rendered.endsWith(".$extension", ignoreCase = true)) {
            "$rendered.$extension"
        } else {
            rendered
        }

    // ---------------------------------------------------------------- tokens

    @Test
    fun `every supported token renders its own part of the name`() {
        val rendered = render(
            pattern = "{name}|{ext}|{date}|{time}|{year}|{month}|{day}|{category}|{parent}|{n}",
        )
        val parts = rendered.split('|')

        assertEquals("holiday", parts[0])
        assertEquals("jpg", parts[1])
        assertEquals(stamp("yyyy-MM-dd"), parts[2])
        assertEquals(stamp("HH-mm-ss"), parts[3])
        assertEquals(stamp("yyyy"), parts[4])
        assertEquals(stamp("MM"), parts[5])
        assertEquals(stamp("dd"), parts[6])
        assertEquals("images", parts[7])
        assertEquals("Camera", parts[8])
        assertEquals("01", parts[9])
    }

    @Test
    fun `the name token drops the extension so the extension token can carry it`() {
        assertEquals("holiday.jpg", render("{name}.{ext}"))
        assertEquals("holiday", render("{name}"))
    }

    @Test
    fun `a file without an extension renders an empty extension token`() {
        assertEquals("", render("{ext}", name = "README", extension = ""))
        assertEquals("README", render("{name}", name = "README", extension = ""))
    }

    @Test
    fun `a dotfile keeps its leading dot`() {
        assertEquals(".nomedia", render("{name}", name = ".nomedia", extension = ""))
    }

    @Test
    fun `tokens are matched whatever their case`() {
        assertEquals("holiday-2023", render("{NAME}-{Year}"))
    }

    @Test
    fun `the rendered name is trimmed`() {
        assertEquals("holiday", render("  {name}  "))
    }

    // --------------------------------------------------------- unknown tokens

    @Test
    fun `an unknown token is reported`() {
        assertEquals(listOf("foo"), NameTemplate.unknownTokens("{foo}-{name}"))
        assertEquals(listOf("foo", "bar"), NameTemplate.unknownTokens("{foo}{bar}"))
        assertTrue(NameTemplate.unknownTokens("{name}-{n}").isEmpty())
    }

    @Test
    fun `an unknown token is left in the name exactly as typed`() {
        // Nothing is silently dropped: a pattern that says "{shot}" produces a
        // name that says "{shot}", which is how the user finds out it is wrong.
        assertEquals("{shot}-holiday", render("{shot}-{name}"))
        assertEquals(listOf("shot"), NameTemplate.unknownTokens("{shot}-{name}"))
    }

    // -------------------------------------------------------------- sequence

    @Test
    fun `the sequence token is zero padded to two digits`() {
        assertEquals("01", render("{n}", sequence = 1))
        assertEquals("07", render("{n}", sequence = 7))
        assertEquals("42", render("{n}", sequence = 42))
    }

    @Test
    fun `the sequence keeps going past ninety nine rather than wrapping`() {
        // The padding is a minimum, not a width: batch 100 of a folder is not
        // numbered "00" and it is not truncated to two characters either.
        assertEquals("99", render("{n}", sequence = 99))
        assertEquals("100", render("{n}", sequence = 100))
        assertEquals("1000", render("{n}", sequence = 1000))
        assertEquals("photo-1000", render("{name}-{n}", name = "photo.png", extension = "png", sequence = 1000))
    }

    @Test
    fun `a pattern without the sequence token does not gain one`() {
        assertEquals("holiday", render("{name}", sequence = 137))
    }

    // ------------------------------------------------------------- extension

    @Test
    fun `a pattern with no extension keeps the original one where the caller appends it`() {
        val rendered = render("{name}-{n}", sequence = 3)
        assertEquals("holiday-03", rendered)
        assertEquals("holiday-03.jpg", complete(rendered, "jpg"))
    }

    @Test
    fun `a pattern that spells the extension is not given a second one`() {
        val rendered = render("{name}.{ext}")
        assertEquals("holiday.jpg", rendered)
        assertEquals("holiday.jpg", complete(rendered, "jpg"))
    }

    @Test
    fun `the extension is appended as the file spells it`() {
        // ".JPG" has to stay ".JPG": the append rule is case insensitive, so a
        // pattern that already produced it is not given the lowercase one too.
        val rendered = render("{name}.JPG", name = "Holiday.JPG", extension = "JPG")
        assertEquals("Holiday.JPG", rendered)
        assertEquals("Holiday.JPG", complete(rendered, "JPG"))
    }

    // ------------------------------------------------------------ date zone

    @Test
    fun `date tokens use the device zone`() {
        // Same instant, same formatter, same zone — so this holds in every
        // timezone the tests happen to run in.
        assertEquals(stamp("yyyy-MM-dd"), render("{date}"))
        assertEquals(stamp("HH-mm-ss"), render("{time}"))
    }

    @Test
    fun `year month and day agree with the full date`() {
        val full = render("{date}")
        assertEquals(full, "${render("{year}")}-${render("{month}")}-${render("{day}")}")
    }

    // --------------------------------------------------------------- hasTokens

    @Test
    fun `hasTokens reports whether anything would be replaced`() {
        assertTrue(NameTemplate.hasTokens("{name}"))
        assertTrue(NameTemplate.hasTokens("photo-{n}"))
        assertFalse(NameTemplate.hasTokens("photo"))
        assertFalse(NameTemplate.hasTokens(""))
        assertFalse(NameTemplate.hasTokens("{}"))
    }

    @Test
    fun `the help line lists every token the renderer knows`() {
        val tokens = NameTemplate.PLACEHOLDER_HELP.split(' ')
        tokens.forEach { token ->
            assertTrue(
                "$token is offered in the help line but not known to the renderer",
                NameTemplate.unknownTokens(token).isEmpty(),
            )
            assertTrue(NameTemplate.hasTokens(token))
        }
    }
}
