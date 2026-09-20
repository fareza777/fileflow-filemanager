package com.fileflow.app.data.repo

import android.content.Context
import android.provider.MediaStore
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.model.SortDirection
import com.fileflow.app.core.model.SortField
import com.fileflow.app.core.model.SortOrder
import com.fileflow.app.core.storage.FileItems
import com.fileflow.app.core.storage.StorageAccess
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.core.util.MediaStoreItems
import com.fileflow.app.data.db.ShortcutDao
import com.fileflow.app.data.db.ShortcutEntity
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.Candidates
import com.fileflow.app.domain.toFileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The reading side of the app: folder listings, bookmarks, and the recent-file
 * feed. Kept separate from the mutation path so nothing in the UI can change
 * storage while merely rendering a list.
 */
@Singleton
class BrowseRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shortcutDao: ShortcutDao,
    private val volumeRepository: VolumeRepository,
    private val storageAccess: StorageAccess,
    private val indexRepository: FileIndexRepository,
) {

    suspend fun listItems(
        directory: String,
        includeHidden: Boolean,
        sort: SortOrder,
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val folder = File(directory)
        if (!folder.isDirectory) return@withContext emptyList()

        val cached = listingCache.get(directory, includeHidden)
        val items = cached ?: FileItems.listDirectory(folder, includeHidden).also { fresh ->
            listingCache.put(directory, includeHidden, fresh)
        }
        sortItems(items, sort)
    }

    /** Called after any change to a folder so the next visit re-reads it. */
    fun invalidateListing(directory: String? = null) {
        if (directory == null) listingCache.clear() else listingCache.invalidate(directory)
    }

    /**
     * Folder listings are re-read on every visit, which makes stepping in and
     * back out feel slower than it is. A short-lived cache removes that cost
     * while staying honest: anything the app itself changes invalidates the
     * entry immediately, and the TTL covers changes made elsewhere.
     */
    private class ListingCache(private val ttlMillis: Long, private val maxEntries: Int) {
        private class Entry(val at: Long, val items: List<FileItem>)

        private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) =
                size > maxEntries
        }

        @Synchronized
        fun get(directory: String, includeHidden: Boolean): List<FileItem>? {
            val entry = entries[key(directory, includeHidden)] ?: return null
            if (System.currentTimeMillis() - entry.at > ttlMillis) {
                entries.remove(key(directory, includeHidden))
                return null
            }
            return entry.items
        }

        @Synchronized
        fun put(directory: String, includeHidden: Boolean, items: List<FileItem>) {
            entries[key(directory, includeHidden)] = Entry(System.currentTimeMillis(), items)
        }

        @Synchronized
        fun invalidate(directory: String) {
            val prefix = "$directory|"
            entries.keys.filter { it.startsWith(prefix) }.forEach { entries.remove(it) }
        }

        @Synchronized
        fun clear() = entries.clear()

        private fun key(directory: String, includeHidden: Boolean) = "$directory|$includeHidden"
    }

    private val listingCache = ListingCache(ttlMillis = 30_000, maxEntries = 24)

    /**
     * Folders first, always — the one ordering rule nobody wants to configure.
     * Everything else follows the chosen field.
     */
    fun sortItems(items: List<FileItem>, sort: SortOrder): List<FileItem> {
        val comparator = when (sort.field) {
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.SIZE -> compareBy { it.sizeBytes }
            SortField.MODIFIED -> compareBy { it.lastModified }
            SortField.TYPE -> compareBy<FileItem> { it.extension }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val directed = if (sort.direction == SortDirection.ASCENDING) comparator
        else comparator.reversed()
        return items.sortedWith(
            compareByDescending<FileItem> { it.isDirectory }.then(directed)
        )
    }

    /**
     * What changed on this device, newest first.
     *
     * Two sources, because neither is complete on its own. The Room index knows
     * every file the device had at its last build — including the formats the
     * media scanner never reports, like a zip or a code file — but it can be a
     * few hours stale. MediaStore is always current for the media it covers and
     * knows nothing about the rest. Merged by path, newest wins, and anything
     * that no longer exists is dropped rather than offered as a broken row.
     */
    suspend fun recentFiles(limit: Int = 30): List<FileItem> = withContext(Dispatchers.IO) {
        val roots = volumeRepository.volumes().map { it.rootPath }
        val merged = LinkedHashMap<String, FileItem>()

        runCatching { indexRepository.mostRecentFiles(limit * 2) }
            .getOrDefault(emptyList())
            .forEach { candidate ->
                if (!MediaStoreItems.isUserFile(candidate.path)) return@forEach
                if (!File(candidate.path).isFile) return@forEach
                merged[candidate.path] = candidate.toFileItem()
            }

        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                MediaStoreItems.PROJECTION,
                "${MediaStore.Files.FileColumns.SIZE} > 0",
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                // The cursor is ordered newest first, so the older rows can only
                // ever lose to what is already in hand. Reading all of them on a
                // device with sixty thousand photos would be a full scan for a
                // list that shows a few dozen.
                var examined = 0
                while (cursor.moveToNext() && examined < limit * 2) {
                    examined++
                    val item = MediaStoreItems.from(cursor, roots) ?: continue
                    // The index also knows about thumbnail caches, the app's own
                    // trash and the Android/data trees. Showing those as "recent
                    // files" put entries on the home screen that opened folders
                    // the user had never been in.
                    if (!MediaStoreItems.isUserFile(item.path)) continue
                    merged[item.path] = item
                }
            }
        }.onFailure {
            // An empty feed used to be indistinguishable from a device with no
            // recent files. This is the line that says otherwise.
            android.util.Log.w("FileFlow", "recentFiles query failed", it)
        }

        merged.values
            .sortedByDescending { it.lastModified }
            .take(limit)
    }

    fun observeShortcuts(): Flow<List<ShortcutEntity>> = shortcutDao.observeAll()

    suspend fun addShortcut(path: String) = withContext(Dispatchers.IO) {
        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
        shortcutDao.upsert(
            ShortcutEntity(
                path = path,
                name = name,
                position = shortcutDao.count(),
                addedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun removeShortcut(path: String) = withContext(Dispatchers.IO) {
        shortcutDao.delete(path)
    }

    fun volumes() = volumeRepository.volumes()

    fun primaryRoot(): String = volumeRepository.primaryRoot()

    fun hasAccess(): Boolean = storageAccess.hasAccess()

    fun isRestricted(path: String): Boolean = storageAccess.isRestrictedPath(path)

    /**
     * Well-known folders that exist on virtually every device; showing them on
     * the home screen removes the most common navigation entirely.
     */
    suspend fun defaultPlaces(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val root = volumeRepository.primaryRoot()
        listOf(
            "Download",
            "Documents",
            "Pictures",
            "DCIM/Camera",
            "Movies",
            "Music",
            "Android/media/com.whatsapp/WhatsApp/Media",
            "Telegram",
            "Bluetooth",
        ).map { relative ->
            relative.substringAfterLast('/') to File(root, relative).absolutePath
        }.filter { File(it.second).isDirectory }
    }

    fun toCandidate(path: String): Candidate? {
        val file = File(path)
        if (!file.exists()) return null
        return Candidates.of(file)
    }
}
