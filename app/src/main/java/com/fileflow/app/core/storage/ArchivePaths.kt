package com.fileflow.app.core.storage

import java.io.File

/**
 * Where an archive entry is allowed to land.
 *
 * Both readers in this package — zip and tar — need the same two answers, and
 * both of them are security decisions: an archive is the one input that arrives
 * from somewhere else and names its own output paths.
 */
internal object ArchivePaths {

    /**
     * Returns null when the entry would land outside [root] — the zip-slip and
     * tar-slip guard. Anything absolute, or containing "..", is refused rather
     * than sanitised, because a file with a doctored name is not something to
     * silently rewrite.
     *
     * The prefix is compared with a separator attached: "/…/Download" is a
     * prefix of "/…/Downloads", and a plain startsWith would let an entry
     * resolve into the sibling directory.
     */
    fun resolveSafely(root: File, entryName: String): File? {
        if (entryName.isBlank()) return null
        val normalised = entryName.replace('\\', '/').trimStart('/')
        if (normalised.isEmpty() || normalised.contains("..")) return null
        val candidate = File(root, normalised)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val rootPath = root.path.trimEnd(File.separatorChar)
        val inside = canonical.path == rootPath ||
            canonical.path.startsWith(rootPath + File.separator)
        return if (inside) canonical else null
    }

    /**
     * A name that is free on disk and has not already been handed out during
     * this extraction. Extracting never replaces something the user already
     * had: an archive is the likeliest input to be full of names that collide,
     * and the file it would land on is one the user never saw.
     */
    fun claim(target: File, claimed: MutableSet<String>): File {
        if (!target.exists() && claimed.add(target.absolutePath)) return target
        val parent = target.parentFile ?: return target
        val base = target.name.substringBeforeLast('.', target.name)
        val suffix = target.name.substringAfterLast('.', "")
        var index = 2
        while (index < 10_000) {
            val candidate = File(
                parent,
                if (suffix.isEmpty()) "$base ($index)" else "$base ($index).$suffix",
            )
            if (!candidate.exists() && claimed.add(candidate.absolutePath)) return candidate
            index++
        }
        return target
    }
}
