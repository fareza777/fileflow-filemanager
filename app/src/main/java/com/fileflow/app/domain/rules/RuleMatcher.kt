package com.fileflow.app.domain.rules

import com.fileflow.app.core.util.MimeTypes
import com.fileflow.app.core.util.Paths
import com.fileflow.app.domain.Candidate
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Pure predicate evaluation. Every condition is a small, independent test so a
 * rule can be explained to the user one line at a time — and so the whole
 * matcher stays unit-testable without touching a filesystem.
 */
object RuleMatcher {

    private const val DAY_MILLIS = 86_400_000L

    /**
     * Compiled once per distinct pattern, not once per file.
     *
     * A rule is tested against every file on the device, so building the regex
     * inside the walk meant a hundred thousand compilations on a full sweep —
     * for a pattern the user wrote once. The key space is the patterns that
     * actually exist in the user's rules, which is a handful.
     */
    private val wildcardCache = ConcurrentHashMap<String, Regex>()
    private val regexCache = ConcurrentHashMap<String, Pattern>()
    private val extensionCache = ConcurrentHashMap<String, Set<String>>()

    private fun wildcard(source: String): Regex =
        wildcardCache[source] ?: wildcardToRegex(source).also { wildcardCache[source] = it }

    private fun pattern(source: String): Pattern? =
        regexCache[source] ?: runCatching {
            Pattern.compile(source, Pattern.CASE_INSENSITIVE)
        }.getOrNull()?.also { regexCache[source] = it }

    private fun wantedExtensions(raw: List<String>): Set<String> =
        extensionCache[raw.joinToString("\u0000")] ?: raw
            .map { MimeTypes.normalizeExtension(it) }
            .filter { it.isNotEmpty() }
            .toSet()
            .also { extensionCache[raw.joinToString("\u0000")] = it }

    fun matches(
        candidate: Candidate,
        spec: RuleSpec,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (candidate.isDirectory) return false
        if (spec.conditions.isEmpty()) return false
        return when (spec.matchMode) {
            MatchMode.ALL -> spec.conditions.all { matches(candidate, it, now) }
            MatchMode.ANY -> spec.conditions.any { matches(candidate, it, now) }
        }
    }

    fun matches(
        candidate: Candidate,
        condition: MatchCondition,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val raw = when (condition) {
            is MatchCondition.Name -> matchName(candidate.name, condition)
            is MatchCondition.Extension -> matchExtension(candidate.extension, condition)
            is MatchCondition.Category -> condition.categories.any {
                it.equals(candidate.category.key, ignoreCase = true)
            }
            is MatchCondition.SourceFolder -> {
                val parent = Paths.parentOf(candidate.path) ?: ""
                condition.folders.any { folder -> matchesFolder(parent, folder) }
            }
            is MatchCondition.Size -> matchSize(candidate.sizeBytes, condition)
            is MatchCondition.Age -> matchAge(candidate.lastModified, condition, now)
        }
        return if (condition.negate) !raw else raw
    }

    // ------------------------------------------------------------------ name

    private fun matchName(name: String, condition: MatchCondition.Name): Boolean {
        val target = condition.text.trim()
        if (target.isEmpty()) return true
        return when (condition.mode) {
            NameMode.CONTAINS -> name.contains(target, ignoreCase = true)
            NameMode.STARTS_WITH -> name.startsWith(target, ignoreCase = true)
            NameMode.ENDS_WITH -> name.endsWith(target, ignoreCase = true)
            NameMode.WILDCARD -> wildcard(target).containsMatchIn(name)
            NameMode.REGEX -> pattern(target)?.matcher(name)?.find() ?: false
        }
    }

    /**
     * Compiles a shell-style glob ("Invoice*.pdf") into a regex. Everything
     * that is not a wildcard is escaped, so a stray dot cannot silently become
     * "any character".
     */
    fun wildcardToRegex(pattern: String): Regex {
        val builder = StringBuilder()
        pattern.forEach { ch ->
            when (ch) {
                '*' -> builder.append(".*")
                '?' -> builder.append('.')
                else -> builder.append(Regex.escape(ch.toString()))
            }
        }
        return Regex(builder.toString(), RegexOption.IGNORE_CASE)
    }

    fun isValidRegex(pattern: String): Boolean = try {
        Pattern.compile(pattern)
        true
    } catch (_: PatternSyntaxException) {
        false
    }

    // -------------------------------------------------------------- extension

    /**
     * Two ways to name a source folder: an absolute path means "anything under
     * here", while a bare word like "WhatsApp" matches any folder segment,
     * which is what people actually type.
     */
    private fun matchesFolder(parentPath: String, folder: String): Boolean {
        val needle = folder.trim().trimEnd('/')
        if (needle.isEmpty()) return false
        if (needle.startsWith("/")) return Paths.isInside(parentPath, needle)
        val segments = parentPath.split('/')
        return segments.any { it.equals(needle, ignoreCase = true) }
    }

    private fun matchExtension(extension: String, condition: MatchCondition.Extension): Boolean {
        val wanted = wantedExtensions(condition.extensions)
        if (wanted.isEmpty()) return true
        return wanted.contains(extension.lowercase())
    }

    // ------------------------------------------------------------------ size

    private fun matchSize(size: Long, condition: MatchCondition.Size): Boolean {
        condition.minBytes?.let { if (size < it) return false }
        condition.maxBytes?.let { if (size > it) return false }
        return true
    }

    // ------------------------------------------------------------------- age

    /**
     * The two bounds are deliberately exclusive.
     *
     * A file whose age was exactly `olderThanDays` used to satisfy both "older
     * than N" and "newer than N", so one file could be claimed by two opposite
     * rules at the same instant and an "between N and M" rule double-counted its
     * own boundary. "Older than" now owns the boundary and "newer than" does
     * not.
     *
     * This measures elapsed time rather than calendar days, so "30 days" drifts
     * by an hour across a daylight-saving change. That is the right trade for a
     * rule about age; a rule about dates would need calendar arithmetic.
     */
    private fun matchAge(modified: Long, condition: MatchCondition.Age, now: Long): Boolean {
        val ageMillis = now - modified
        condition.olderThanDays?.let { days ->
            if (ageMillis < days * DAY_MILLIS) return false
        }
        condition.newerThanDays?.let { days ->
            if (ageMillis >= days * DAY_MILLIS) return false
        }
        return true
    }
}
