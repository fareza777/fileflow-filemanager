package com.fileflow.app.core.util

import android.database.Cursor

/**
 * Column access that survives a ROM that does not provide the column.
 *
 * [Cursor.getColumnIndexOrThrow] is the right call when a missing column means
 * the query itself was wrong. It is the wrong call for `MediaStore...DATA`:
 * some OEM builds drop it from the projection, and because both call sites wrap
 * the read in `runCatching`, a thrown exception did not crash — it silently
 * produced an empty result. The home screen's "recent files" and the whole
 * calendar turned blank with nothing in the logs to say why.
 *
 * Returning `-1` lets the caller decide: skip the row, or fall back.
 */
fun Cursor.columnIndexOrNull(name: String): Int =
    getColumnIndex(name).takeIf { it >= 0 } ?: -1

fun Cursor.hasColumn(name: String): Boolean = getColumnIndex(name) >= 0

/**
 * Reads a string column only when it exists, so a projection that omits it
 * yields null instead of throwing.
 */
fun Cursor.stringColumn(name: String): String? {
    val index = getColumnIndex(name)
    return if (index >= 0) getString(index) else null
}

fun Cursor.longColumn(name: String, default: Long = 0L): Long {
    val index = getColumnIndex(name)
    return if (index >= 0) getLong(index) else default
}
