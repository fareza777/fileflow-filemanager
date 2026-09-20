package com.fileflow.app.domain.search

import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.core.util.MimeTypes
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.FileWalker
import com.fileflow.app.domain.WalkOptions
import com.fileflow.app.domain.rules.MatchCondition
import com.fileflow.app.domain.rules.NameMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * Filters are data so a search can be turned into a rule verbatim — "find
 * every APK over 50 MB" and "act on every APK over 50 MB" are the same
 * sentence, and the user should only have to say it once.
 */
data class SearchFilters(
    val text: String = "",
    val extensions: Set<String> = emptySet(),
    val categories: Set<FileCategory> = emptySet(),
    val minBytes: Long? = null,
    val maxBytes: Long? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val includeFolders: Boolean = true,
) {
    val isActive: Boolean
        get() = text.isNotBlank() || extensions.isNotEmpty() || categories.isNotEmpty() ||
            minBytes != null || maxBytes != null || modifiedAfter != null || modifiedBefore != null

    fun asConditions(): List<MatchCondition> = buildList {
        if (text.isNotBlank()) {
            add(MatchCondition.Name(text.trim(), mode = NameMode.CONTAINS))
        }
        if (extensions.isNotEmpty()) {
            add(MatchCondition.Extension(extensions.map { it.lowercase() }.sorted()))
        }
        if (categories.isNotEmpty()) {
            add(MatchCondition.Category(categories.map { it.key }))
        }
        if (minBytes != null || maxBytes != null) {
            add(MatchCondition.Size(minBytes = minBytes, maxBytes = maxBytes))
        }
        if (modifiedAfter != null || modifiedBefore != null) {
            val newerThan = modifiedAfter?.let {
                ((System.currentTimeMillis() - it) / DAY_MILLIS).toInt()
            }
            val olderThan = modifiedBefore?.let {
                ((System.currentTimeMillis() - it) / DAY_MILLIS).toInt()
            }
            add(MatchCondition.Age(olderThanDays = olderThan, newerThanDays = newerThan))
        }
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}

@Singleton
class FileSearchEngine @Inject constructor(
    private val walker: FileWalker,
    private val volumes: VolumeRepository,
) {

    /**
     * Streams as it walks so the first hits appear while the rest of the
     * device is still being read, and stops the moment the caller cancels.
     */
    fun search(
        query: String,
        filters: SearchFilters,
        roots: List<String> = volumes.roots(),
        deep: Boolean = true,
    ): Flow<Candidate> = flow {
        val options = WalkOptions(
            recursive = deep,
            skipHidden = true,
            includeDirectories = filters.includeFolders,
        )
        val needle = query.trim()
        walker.walkFiles(roots, options).collect { candidate ->
            if (matches(candidate, needle, filters)) emit(candidate)
        }
    }.flowOn(Dispatchers.IO)

    fun matches(candidate: Candidate, needle: String, filters: SearchFilters): Boolean {
        if (candidate.isDirectory) {
            if (!filters.includeFolders) return false
            return needle.isEmpty() || candidate.name.contains(needle, ignoreCase = true)
        }

        if (needle.isNotEmpty()) {
            val nameHit = candidate.name.contains(needle, ignoreCase = true)
            // Searching "apk" should find installers even though no installer
            // has "apk" in its display name beyond the extension.
            val extensionHit = candidate.extension.equals(
                MimeTypes.normalizeExtension(needle), ignoreCase = true
            )
            if (!nameHit && !extensionHit) return false
        }

        if (filters.extensions.isNotEmpty()) {
            val wanted = filters.extensions.map { MimeTypes.normalizeExtension(it) }
            if (candidate.extension !in wanted) return false
        }

        if (filters.categories.isNotEmpty() && candidate.category !in filters.categories) return false

        filters.minBytes?.let { if (candidate.sizeBytes < it) return false }
        filters.maxBytes?.let { if (candidate.sizeBytes > it) return false }
        filters.modifiedAfter?.let { if (candidate.lastModified < it) return false }
        filters.modifiedBefore?.let { if (candidate.lastModified > it) return false }
        return true
    }

    /**
     * A quick type filter for the search screen's chips.
     */
    fun extensionChips(category: FileCategory): List<String> = when (category) {
        FileCategory.APPS -> listOf("apk", "apks", "xapk", "apkm")
        FileCategory.IMAGES -> listOf("jpg", "jpeg", "png", "gif", "webp", "heic")
        FileCategory.VIDEOS -> listOf("mp4", "mkv", "avi", "mov", "webm")
        FileCategory.AUDIO -> listOf("mp3", "m4a", "wav", "flac", "ogg")
        FileCategory.DOCUMENTS -> listOf("pdf", "docx", "xlsx", "pptx", "txt")
        FileCategory.ARCHIVES -> listOf("zip", "rar", "7z", "tar", "gz")
        FileCategory.CODE -> listOf("kt", "java", "py", "js", "json", "xml")
        FileCategory.OTHER -> emptyList()
    }
}
