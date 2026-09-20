package com.fileflow.app.core.util

import android.content.Context
import android.text.format.Formatter
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object Formatters {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    /** Binary-prefix sizes with the precision a person actually reads. */
    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < 1024) return "$value B"
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024 && unit < UNITS.lastIndex) {
            size /= 1024
            unit++
        }
        val decimals = when {
            size < 10 -> 2
            size < 100 -> 1
            else -> 0
        }
        return String.format(Locale.getDefault(), "%.${decimals}f %s", size, UNITS[unit])
    }

    fun count(value: Int, singular: String, plural: String): String =
        if (value == 1) "1 $singular" else "$value $plural"

    fun dateTime(epochMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(epochMillis))

    fun date(epochMillis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

    fun time(epochMillis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMillis))

    fun relative(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val delta = now - epochMillis
        return when {
            delta < 0 -> dateTime(epochMillis)
            delta < 60_000 -> "just now"
            delta < 3_600_000 -> "${delta / 60_000} min ago"
            delta < 86_400_000 -> "${delta / 3_600_000} h ago"
            delta < 7 * 86_400_000L -> "${delta / 86_400_000} d ago"
            else -> date(epochMillis)
        }
    }

    /** Disk usage strings for the system picker's own wording. */
    fun freeSpace(context: Context, bytes: Long): String =
        Formatter.formatFileSize(context, bytes)

    fun percent(part: Long, whole: Long): Float =
        if (whole <= 0) 0f else (part.toFloat() / whole.toFloat()).coerceIn(0f, 1f)

    fun durationShort(millis: Long): String {
        val seconds = abs(millis) / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }
}

object Paths {

    private const val INTERNAL_LABEL = "Internal storage"
    private const val REMOVABLE_LABEL = "SD card"

    fun join(parent: String, child: String): String =
        if (parent.endsWith("/")) parent + child else "$parent/$child"

    fun parentOf(path: String): String? {
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        if (index <= 0) return null
        return trimmed.substring(0, index)
    }

    /**
     * The last path segment — except at a volume root, where that segment is a
     * number or a UUID. The browser title read "0" after navigating up to the
     * top of internal storage, which looks like a bug rather than a place.
     *
     * Matched exactly, not by prefix: `/storage/emulated/0/Download` shares that
     * prefix and is a perfectly ordinary folder called Download.
     */
    fun nameOf(path: String): String {
        val trimmed = path.trimEnd('/')
        return when {
            trimmed.isEmpty() || trimmed == "/" -> INTERNAL_LABEL
            EMULATED_ROOT.matches(trimmed) -> INTERNAL_LABEL
            SD_CARD_ROOT.matches(trimmed) -> REMOVABLE_LABEL
            else -> trimmed.substringAfterLast('/')
        }
    }

    private val EMULATED_ROOT = Regex("^/storage/emulated/\\d+$")

    private val SD_CARD_ROOT = Regex("^/storage/[A-Za-z0-9]{4}-[A-Za-z0-9]{4}$")

    fun isInside(child: String, ancestor: String): Boolean {
        val a = ancestor.trimEnd('/')
        val c = child.trimEnd('/')
        if (c == a) return true
        return c.startsWith("$a/")
    }

    /**
     * Folder names are the only part of a path worth showing in a breadcrumb,
     * so the shared-storage prefix collapses to a friendly label.
     */
    fun display(path: String, primaryRoot: String? = null): String {
        val root = primaryRoot ?: "/storage/emulated/0"
        return when {
            path == root || path == "$root/" -> INTERNAL_LABEL
            path.startsWith("$root/") -> path.removePrefix("$root/")
            else -> path
        }
    }

    fun breadcrumbSegments(path: String, primaryRoot: String?): List<Pair<String, String>> {
        val root = primaryRoot ?: "/storage/emulated/0"
        val result = mutableListOf<Pair<String, String>>()
        if (!path.startsWith(root)) {
            result += path.trimEnd('/').substringAfterLast('/') to path
            return result
        }
        result += INTERNAL_LABEL to root
        val rest = path.removePrefix(root).trim('/')
        if (rest.isEmpty()) return result
        var accumulated = root
        rest.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            accumulated = "$accumulated/$segment"
            result += segment to accumulated
        }
        return result
    }

    /** Rejects names that would escape the destination folder or break FAT. */
    fun isValidFileName(name: String): Boolean {
        if (name.isBlank() || name.length > 255) return false
        if (name == "." || name == "..") return false
        val illegal = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')
        if (name.any { it in illegal }) return false
        if (name.any { it.code < 32 }) return false
        return name.none { it in setOf('\n', '\r', '\t') }
    }

    fun sanitizeFileName(name: String): String {
        val illegal = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        val cleaned = buildString {
            name.forEach { ch ->
                when {
                    ch in illegal -> append('_')
                    ch.code < 32 -> Unit
                    else -> append(ch)
                }
            }
        }.trim().trimEnd('.')
        return cleaned.ifBlank { "untitled" }.take(200)
    }
}
