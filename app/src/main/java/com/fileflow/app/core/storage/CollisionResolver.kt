package com.fileflow.app.core.storage

import java.io.File

/**
 * Working out a free name is needed in three places that must agree with each
 * other: the rename preview (which promises an outcome), the rename engine
 * (which applies it), and the rules planner (which must not show two files
 * heading for the same path).
 *
 * They used to each have their own copy, which is exactly how a preview ends up
 * disagreeing with the result.
 */
object CollisionResolver {

    private const val MAX_ATTEMPTS = 500

    /**
     * Returns the first free name in [directory] for [name], trying
     * "name (2).ext", "name (3).ext" and so on.
     *
     * @param claimed names already reserved in this batch, so two files in one
     *   plan cannot be handed the same target before either exists on disk.
     */
    fun unique(
        directory: File,
        name: String,
        claimed: MutableSet<String> = mutableSetOf(),
        exists: (File) -> Boolean = { it.exists() },
    ): String {
        if (isFree(directory, name, claimed, exists)) return name

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""

        var index = 2
        while (index < MAX_ATTEMPTS) {
            val candidate = "$base ($index)$extension"
            if (isFree(directory, candidate, claimed, exists)) return candidate
            index++
        }
        return "$base-${System.currentTimeMillis()}$extension"
    }

    private fun isFree(
        directory: File,
        name: String,
        claimed: MutableSet<String>,
        exists: (File) -> Boolean,
    ): Boolean {
        val key = "${directory.absolutePath}/$name".lowercase()
        if (!claimed.add(key)) return false
        return !exists(File(directory, name))
    }

    /** A lower-cased identity used to track what a batch has already taken. */
    fun key(directoryPath: String, name: String): String =
        "$directoryPath/$name".lowercase()
}
