package com.fileflow.app.domain.rename

import com.fileflow.app.core.util.MimeTypes
import com.fileflow.app.data.prefs.RenameConvention
import com.fileflow.app.domain.Candidate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class RenameReason { JUNK, DUPLICATE_SUFFIX, SPACING, DATE, EXTENSION, RANDOM, STYLE, UNIQUENESS }

enum class Confidence { HIGH, MEDIUM, LOW }

data class NameSuggestion(
    val baseName: String,
    val extension: String,
    val reasons: Set<RenameReason>,
) {
    val confidence: Confidence
        get() = when {
            reasons.any { it in STRONG } -> Confidence.HIGH
            reasons.any { it in MODERATE } -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

    private companion object {
        val STRONG = setOf(RenameReason.EXTENSION, RenameReason.DUPLICATE_SUFFIX, RenameReason.JUNK)
        val MODERATE = setOf(
            RenameReason.DATE, RenameReason.RANDOM, RenameReason.UNIQUENESS, RenameReason.STYLE,
        )
    }
}

/**
 * The actual "unclear file name" logic. Everything here is a pure string
 * transform over a [Candidate], which is why it can be tested exhaustively
 * without touching a phone.
 *
 * The guiding rule is that a suggestion must be explainable in one short
 * phrase — if a transform cannot say why it fired, it does not fire.
 */
object NameHeuristics {

    // ------------------------------------------------------------- patterns

    private val DUPLICATE_SUFFIX = Regex("""[\s_-]*\((\d{1,3})\)\s*$""")
    private val COPY_SUFFIX = Regex(
        """[\s_-]*(copy|salinan|kopie|副本|kopya)([\s_-]*\d{1,3})?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val REPEATED_SEPARATOR = Regex("""([\s_\-.])\1{1,}""")
    private val EDGE_JUNK = Regex("""^[\s_\-.]+|[\s_\-.]+$""")
    private val CAMERA_PREFIX = Regex(
        """^(img|vid|pxl|dsc|mvimg|dscf|screenshot|screen[\s_-]?shot|photo|video|audio|aud|stk|svid|wa)[\s_\-]*""",
        RegexOption.IGNORE_CASE,
    )
    private val WHATSAPP_TAIL = Regex("""[\s_-]*(wa|wam)\d{3,}$""", RegexOption.IGNORE_CASE)
    private val HEX_NAME = Regex("""^[0-9a-f]{16,}$""", RegexOption.IGNORE_CASE)
    private val UUID_NAME = Regex(
        """^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""",
        RegexOption.IGNORE_CASE,
    )
    private val OPAQUE_NAME = Regex("""^(?=.*\d)[A-Za-z0-9]{20,}$""")
    private val ESSENTIALLY_DIGITS = Regex("""^[\d\s_\-.]+$""")

    private val DATE_TIME_PATTERNS = listOf(
        Regex("""(\d{4})(\d{2})(\d{2})[T_\-. ]?(\d{2})[.\-:]?(\d{2})[.\-:]?(\d{2})"""),
        Regex("""(\d{4})-(\d{2})-(\d{2})[T_\- ](\d{2})[.\-:]?(\d{2})(?:[.\-:]?(\d{2}))?"""),
    )
    private val DATE_ONLY_PATTERNS = listOf(
        Regex("""(\d{4})(\d{2})(\d{2})"""),
        Regex("""(\d{4})-(\d{2})-(\d{2})"""),
    )
    private val UNIX_SECONDS = Regex("""\b(1\d{9})\b""")

    /** A base name made only of digits is unusable; a formatted date is not. */
    private val ONLY_DIGITS = Regex("""^\d+$""")

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH-mm-ss")

    // ---------------------------------------------------------------- public

    /**
     * @param header first bytes of the file, when the caller could afford to
     *   read them. Only used to repair a wrong or missing extension.
     */
    fun suggest(
        candidate: Candidate,
        convention: RenameConvention,
        datePrefix: Boolean,
        header: ByteArray? = null,
        fallbackDate: Long = candidate.lastModified,
    ): NameSuggestion? {
        if (candidate.isDirectory) return null
        if (candidate.name.startsWith(".")) return null
        if (candidate.name.isBlank()) return null

        val reasons = mutableSetOf<RenameReason>()
        val declaredExtension = MimeTypes.extensionOf(candidate.name)
        var originalBase = if (declaredExtension.isEmpty()) {
            candidate.name
        } else {
            candidate.name.dropLast(declaredExtension.length + 1)
        }
        var extension = declaredExtension

        // 1. A wrong extension is the most damaging kind of unclear name:
        //    every app on the phone fails to open it.
        if (header != null) {
            val sniffed = MimeTypes.signatureExtension(header)
            if (sniffed != null && !sniffed.equals(extension, ignoreCase = true)) {
                val declaredKnown = MimeTypes.isKnownExtension(extension)
                val sameFamily = declaredKnown &&
                    MimeTypes.categoryOf(extension) == MimeTypes.categoryOf(sniffed)
                if (!sameFamily) {
                    extension = sniffed
                    reasons += RenameReason.EXTENSION
                }
            }
        }

        var working = originalBase

        // 2. "report.pdf.pdf" — a doubled extension is always a mistake.
        if (extension.isNotEmpty() && working.endsWith(".$extension", ignoreCase = true)) {
            working = working.dropLast(extension.length + 1)
            reasons += RenameReason.JUNK
        }

        // 3. Download-manager noise: "(1)", "- Copy", "final copy 2". The two
        //    markers stack ("Budget - Copy (2)"), so they are peeled in turns
        //    until the name stops shrinking.
        var stripped = working
        var guard = 0
        while (guard++ < 4) {
            val beforePass = stripped
            stripped = DUPLICATE_SUFFIX.replace(stripped, "")
            if (stripped != beforePass) reasons += RenameReason.DUPLICATE_SUFFIX
            val afterDuplicate = stripped
            stripped = COPY_SUFFIX.replace(stripped, "")
            if (stripped != afterDuplicate) reasons += RenameReason.DUPLICATE_SUFFIX
            stripped = EDGE_JUNK.replace(stripped, "").trim()
            if (stripped == beforePass || stripped.isEmpty()) break
        }
        if (stripped.isNotEmpty()) working = stripped

        // 4. A date hidden inside the name is the single most useful thing to
        //    surface, because it is what people search by.
        val dateHit = findDate(working, fallbackDate)
        if (dateHit != null) {
            reasons += RenameReason.DATE
            val remainder = cleanRemainder(working.removeRange(dateHit.range))
            working = buildString {
                append(dateHit.date.format(DATE_FORMAT))
                dateHit.time?.let { append(' ').append(it.format(TIME_FORMAT)) }
                if (remainder.isNotEmpty()) append(' ').append(remainder)
            }
        } else if (isOpaque(working)) {
            // 5. A name nobody can read: a hash, a UUID, or a long opaque
            //    token. Give it the folder and the date it was captured.
            reasons += RenameReason.RANDOM
            val stamp = Instant.ofEpochMilli(fallbackDate)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DATE_FORMAT)
            val parent = candidate.path.substringBeforeLast('/', "").substringAfterLast('/')
            working = if (parent.isNotBlank() && !parent.equals("emulated", true)) {
                "$parent $stamp"
            } else {
                "File $stamp"
            }
        }

        // 6. Collapse the separators that remain and apply the chosen style.
        val collapsed = REPEATED_SEPARATOR.replace(working, "$1")
        if (collapsed != working) reasons += RenameReason.JUNK
        working = EDGE_JUNK.replace(collapsed, "").trim()

        val styled = applyConvention(working, convention)
        if (styled != working) reasons += RenameReason.STYLE
        working = styled

        if (datePrefix && dateHit == null && !working.contains(DATE_FORMAT.format(
                Instant.ofEpochMilli(fallbackDate).atZone(ZoneId.systemDefault()).toLocalDate()
            ))
        ) {
            val stamp = Instant.ofEpochMilli(fallbackDate)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DATE_FORMAT)
            working = "$stamp $working"
            reasons += RenameReason.DATE
        }

        working = working.trim()
        if (working.isEmpty() || ONLY_DIGITS.matches(working)) return null
        if (reasons.isEmpty()) return null

        val finalName = if (extension.isEmpty()) working else "$working.$extension"
        if (finalName.equals(candidate.name, ignoreCase = false)) return null

        return NameSuggestion(
            baseName = working,
            extension = extension,
            reasons = reasons,
        )
    }

    fun assemble(suggestion: NameSuggestion): String =
        if (suggestion.extension.isEmpty()) {
            suggestion.baseName
        } else {
            "${suggestion.baseName}.${suggestion.extension}"
        }

    // --------------------------------------------------------------- helpers

    private data class DateHit(
        val range: IntRange,
        val date: LocalDate,
        val time: LocalTime?,
    )

    private fun findDate(text: String, fallback: Long): DateHit? {
        for (pattern in DATE_TIME_PATTERNS) {
            val match = pattern.find(text) ?: continue
            parse(
                year = match.groupValues[1],
                month = match.groupValues[2],
                day = match.groupValues[3],
                hour = match.groupValues[4],
                minute = match.groupValues[5],
                second = match.groupValues.getOrNull(6),
                range = match.range,
            )?.let { return it }
        }
        for (pattern in DATE_ONLY_PATTERNS) {
            val match = pattern.find(text) ?: continue
            parse(
                year = match.groupValues[1],
                month = match.groupValues[2],
                day = match.groupValues[3],
                hour = null,
                minute = null,
                second = null,
                range = match.range,
            )?.let { return it }
        }
        // A bare ten-digit number is a Unix timestamp often enough to be worth
        // recognising, but only when it lands in a plausible era.
        UNIX_SECONDS.find(text)?.let { match ->
            val seconds = match.groupValues[1].toLongOrNull() ?: return@let
            val instant = Instant.ofEpochSecond(seconds)
            val year = instant.atZone(ZoneId.systemDefault()).year
            if (year in 2005..2100) {
                val zoned = instant.atZone(ZoneId.systemDefault())
                return DateHit(match.range, zoned.toLocalDate(), zoned.toLocalTime())
            }
        }
        return null
    }

    private fun parse(
        year: String,
        month: String,
        day: String,
        hour: String?,
        minute: String?,
        second: String?,
        range: IntRange,
    ): DateHit? {
        val y = year.toIntOrNull() ?: return null
        val m = month.toIntOrNull() ?: return null
        val d = day.toIntOrNull() ?: return null
        if (y !in 1990..2100 || m !in 1..12 || d !in 1..31) return null
        val date = runCatching { LocalDate.of(y, m, d) }.getOrNull() ?: return null
        val time = if (hour != null && minute != null) {
            runCatching {
                LocalTime.of(
                    hour.toInt().coerceIn(0, 23),
                    minute.toInt().coerceIn(0, 59),
                    second?.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                )
            }.getOrNull()
        } else {
            null
        }
        return DateHit(range, date, time)
    }

    /**
     * Whatever is left after a date is removed is usually camera noise
     * ("IMG", "WA0001"). It is only kept when it still says something.
     */
    private fun cleanRemainder(raw: String): String {
        var text = raw.replace(REPEATED_SEPARATOR, "$1")
        text = CAMERA_PREFIX.replace(text, "")
        text = WHATSAPP_TAIL.replace(text, "")
        text = EDGE_JUNK.replace(text, "")
        text = text.replace(Regex("""[\s_\-]+"""), " ").trim()
        if (text.length < 3) return ""
        if (ESSENTIALLY_DIGITS.matches(text)) return ""
        return text
    }

    private fun isOpaque(base: String): Boolean {
        val trimmed = base.trim()
        if (trimmed.length < 16) return false
        return HEX_NAME.matches(trimmed) || UUID_NAME.matches(trimmed) || OPAQUE_NAME.matches(trimmed)
    }

    /**
     * Words are recovered before a style is applied, so "monthly_report_v2"
     * and "monthlyReportV2" both survive the trip through kebab-case.
     *
     * [RenameConvention.KEEP] is deliberately a no-op: the user asked for the
     * broken parts to be fixed, not for their own taste to be overridden.
     */
    fun splitWords(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val withBreaks = buildString {
            raw.forEachIndexed { index, ch ->
                if (index > 0) {
                    val prev = raw[index - 1]
                    val boundary = (prev.isLowerCase() && ch.isUpperCase()) ||
                        (prev.isDigit() && ch.isLetter()) ||
                        (prev.isLetter() && ch.isDigit() && ch == 'v' &&
                            raw.getOrNull(index + 1)?.isDigit() == true)
                    if (boundary) append(' ')
                }
                append(ch)
            }
        }
        return withBreaks
            .split(Regex("""[\s_\-.]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun applyConvention(raw: String, convention: RenameConvention): String {
        if (convention == RenameConvention.KEEP) return raw
        val words = splitWords(raw)
        if (words.isEmpty()) return raw
        return when (convention) {
            RenameConvention.KEEP -> raw
            RenameConvention.NATURAL -> words.mapIndexed { index, word ->
                if (isAcronym(word)) word
                else if (index == 0) word.lowercase().replaceFirstChar(Char::uppercase)
                else word.lowercase()
            }.joinToString(" ")
            RenameConvention.TITLE_CASE -> words.joinToString(" ") { word ->
                if (isAcronym(word)) word else word.lowercase().replaceFirstChar(Char::uppercase)
            }
            RenameConvention.KEBAB -> words.joinToString("-") { it.lowercase() }
            RenameConvention.SNAKE -> words.joinToString("_") { it.lowercase() }
        }
    }

    private fun isAcronym(word: String): Boolean =
        word.length in 2..4 && word.all { it.isUpperCase() || it.isDigit() } &&
            word.any { it.isLetter() }
}
