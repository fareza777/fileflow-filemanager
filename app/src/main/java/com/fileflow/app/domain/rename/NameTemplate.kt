package com.fileflow.app.domain.rename

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A tiny placeholder language shared by rule-driven renames and the smart
 * rename suggestions, so a pattern written in one place means the same thing
 * in the other.
 *
 * Supported tokens: {name} {ext} {date} {time} {year} {month} {day}
 * {category} {parent} {n}
 */
object NameTemplate {

    private val TOKEN = Regex("\\{([a-zA-Z]+)}")

    const val PLACEHOLDER_HELP =
        "{name} {ext} {date} {time} {year} {month} {day} {category} {parent} {n}"

    /**
     * Shared and immutable.
     *
     * [render] runs once per renamed file, and it used to construct a fresh
     * SimpleDateFormat for every token it met — so "{year}-{month}-{day}" built
     * three of them, each cloning its own calendar, for every file in a batch.
     * DateTimeFormatter is immutable, so one instance is safe for all of them.
     */
    private val DATE: DateTimeFormatter = formatter("yyyy-MM-dd")
    private val TIME: DateTimeFormatter = formatter("HH-mm-ss")
    private val YEAR: DateTimeFormatter = formatter("yyyy")
    private val MONTH: DateTimeFormatter = formatter("MM")
    private val DAY: DateTimeFormatter = formatter("dd")

    private fun formatter(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(ZoneId.systemDefault())

    fun render(
        pattern: String,
        name: String,
        extension: String,
        lastModified: Long,
        category: String,
        parentName: String,
        sequence: Int = 0,
    ): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val stamp = Instant.ofEpochMilli(lastModified)
        val rendered = TOKEN.replace(pattern) { match ->
            when (match.groupValues[1].lowercase()) {
                "name" -> base
                "ext" -> extension
                "date" -> DATE.format(stamp)
                "time" -> TIME.format(stamp)
                "year" -> YEAR.format(stamp)
                "month" -> MONTH.format(stamp)
                "day" -> DAY.format(stamp)
                "category" -> category
                "parent" -> parentName
                "n" -> sequence.toString().padStart(2, '0')
                else -> match.value
            }
        }
        return rendered.trim()
    }

    /** True when a pattern still carries at least one live token. */
    fun hasTokens(pattern: String): Boolean = TOKEN.containsMatchIn(pattern)

    fun unknownTokens(pattern: String): List<String> = TOKEN.findAll(pattern)
        .map { it.groupValues[1].lowercase() }
        .filterNot { it in KNOWN }
        .toList()

    private val KNOWN = setOf(
        "name", "ext", "date", "time", "year", "month", "day", "category", "parent", "n",
    )
}
