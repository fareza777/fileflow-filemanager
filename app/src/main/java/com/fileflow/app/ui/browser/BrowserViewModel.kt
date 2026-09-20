package com.fileflow.app.ui.browser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.model.SortField
import com.fileflow.app.core.model.SortOrder
import com.fileflow.app.core.storage.ArchiveBrowser
import com.fileflow.app.core.storage.ArchiveFormat
import com.fileflow.app.core.storage.CollisionResolver
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.MediaScanner
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationProgress
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.MimeTypes
import com.fileflow.app.core.util.OutcomeMessages
import com.fileflow.app.core.util.Paths
import com.fileflow.app.data.prefs.SettingsRepository
import com.fileflow.app.data.repo.ActiveOperation
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.FileIndexRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.ui.util.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Clipboard(val paths: List<String>, val isCut: Boolean)

/** A copy or move that stopped to ask what to do about existing names. */
data class PendingConflict(
    val sources: List<String>,
    val destination: String,
    val isMove: Boolean,
    val conflictingNames: List<String>,
)

data class RunningOperation(val kind: OperationKind, val progress: OperationProgress?)

/**
 * An immutable snapshot, so Compose can skip screens and rows whose slice of
 * the state has not changed.
 */
@androidx.compose.runtime.Immutable
data class BrowserState(
    val path: String = "",
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = true,
    val selection: Set<String> = emptySet(),
    /** Derived once when the selection changes, never filtered during a frame. */
    val selectedItems: List<FileItem> = emptyList(),
    val selectedBytes: Long = 0,
    val selectionMode: Boolean = false,
    val sort: SortOrder = SortOrder(),
    val showHidden: Boolean = false,
    val thumbnails: Boolean = true,
    val grid: Boolean = false,
    val clipboard: Clipboard? = null,
    val restricted: Boolean = false,
    /** A quick "show me only the APKs in here" filter; directories always stay. */
    val filterCategory: FileCategory? = null,
    val pendingConflict: PendingConflict? = null,
    /** Paths the user has starred, so rows can show their state. */
    val starredPaths: Set<String> = emptySet(),
    /** Where each mounted volume begins, so navigation can stop there. */
    val volumeRoots: List<String> = emptyList(),
) {
    /** True inside a volume, false at its top — the browser never goes above. */
    val atVolumeRoot: Boolean
        get() = volumeRoots.isEmpty() ||
            volumeRoots.any { it.trimEnd('/') == path.trimEnd('/') }

    /** Whether "up" is a place the user is allowed to go. */
    val canNavigateUp: Boolean get() = !atVolumeRoot && Paths.parentOf(path) != null
    val canPaste: Boolean get() = clipboard != null && clipboard.paths.isNotEmpty()

    fun withSelection(next: Set<String>): BrowserState {
        val chosen = if (next.isEmpty()) emptyList() else items.filter { it.path in next }
        return copy(
            selection = next,
            selectedItems = chosen,
            selectedBytes = chosen.sumOf { it.sizeBytes },
            selectionMode = next.isNotEmpty(),
        )
    }
}

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val browseRepository: BrowseRepository,
    private val actions: FileActionsRepository,
    private val operationRunner: OperationRunner,
    private val settingsRepository: SettingsRepository,
    private val volumes: VolumeRepository,
    private val outcome: OutcomeMessages,
    private val archiveBrowser: ArchiveBrowser,
    private val indexRepository: FileIndexRepository,
    private val mediaScanner: MediaScanner,
    @ApplicationContext private val context: android.content.Context,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    /** Progress lives with the operation, not with this screen. */
    val activeOperation: StateFlow<ActiveOperation?> get() = operationRunner.active

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var operationJob: Job? = null

    init {
        _state.update { it.copy(volumeRoots = runCatching { volumes.roots() }.getOrDefault(emptyList())) }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val previous = _state.value
                _state.update {
                    it.copy(
                        sort = settings.sortOrder,
                        showHidden = settings.showHidden,
                        thumbnails = settings.thumbnails,
                        grid = settings.gridView,
                    )
                }
                if (previous.path.isNotBlank() &&
                    (previous.sort != settings.sortOrder || previous.showHidden != settings.showHidden)
                ) {
                    load(_state.value.path)
                }
            }
        }
        val saved = savedStateHandle.get<String>(KEY_PATH)
        openFolder(saved?.takeIf { it.isNotBlank() } ?: browseRepository.primaryRoot())
        viewModelScope.launch {
            actions.observeStarredPaths().collect { paths ->
                _state.update { it.copy(starredPaths = paths.toSet()) }
            }
        }
    }

    // ------------------------------------------------------------ navigation

    fun openFolder(path: String) {
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path,
                loading = true,
                filterCategory = null,
            ).withSelection(emptySet())
        }
        savedStateHandle[KEY_PATH] = path
        load(path)
    }

    fun navigateUp() {
        // Above a volume root lies /storage/emulated, where the user has no
        // business: `Paths.parentOf` only stops at "/", so the climb had to be
        // stopped here instead.
        val current = _state.value.path
        if (current.contains(ARCHIVE_SEPARATOR)) {
            // Out of the archive one level at a time: the entry's parent, then
            // the folder the archive itself lives in.
            val archive = current.substringBefore(ARCHIVE_SEPARATOR)
            val inner = current.substringAfter(ARCHIVE_SEPARATOR).trim('/')
            val parent = inner.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) {
                openFolder("$archive$ARCHIVE_SEPARATOR$parent")
            } else {
                openFolder(archive.substringBeforeLast('/', "/"))
            }
            return
        }
        if (_state.value.atVolumeRoot) return
        Paths.parentOf(current)?.let { openFolder(it) }
    }

    // ------------------------------------------------------------- archives

    /** Walks into an archive as if it were a folder. */
    fun openArchive(archivePath: String) {
        openFolder("$archivePath$ARCHIVE_SEPARATOR")
    }

    fun isInsideArchive(path: String = _state.value.path): Boolean =
        path.contains(ARCHIVE_SEPARATOR)

    /** The archive a path belongs to, for the title and the extract target. */
    fun archiveFileFor(path: String = _state.value.path): File? =
        path.substringBefore(ARCHIVE_SEPARATOR, "").takeIf { it.isNotEmpty() }?.let { File(it) }

    fun innerPathOf(path: String): String = path.substringAfter(ARCHIVE_SEPARATOR, "").trim('/')

    /**
     * Writes one entry out to the cache and returns the copy, so a file inside
     * an archive opens in the app's own viewers like any other file — the
     * alternative is telling the user to extract it first, which is the thing
     * archive browsing exists to avoid.
     */
    fun materialize(item: FileItem, onReady: (FileItem) -> Unit) {
        val archive = archiveFileFor(item.path) ?: return
        val inner = innerPathOf(item.path)
        viewModelScope.launch {
            // The cache name carries the entry's own path, so two archives with
            // a file of the same name do not overwrite each other's preview.
            val cacheName = "${inner.hashCode().toUInt().toString(16)}-${item.name}"
            val target = File(File(context.cacheDir, ARCHIVE_CACHE_DIR), cacheName)
            val ok = runCatching {
                archiveBrowser.extract(archive, inner, target)
            }.isSuccess
            if (ok) {
                onReady(item.copy(path = target.absolutePath, name = item.name, extension = item.extension))
            } else {
                _messages.send(UiMessage.Info("Could not open ${item.name}"))
            }
        }
    }

    /** Extracts an entry next to the archive it came from. */
    fun extractEntry(item: FileItem) {
        val archive = archiveFileFor(item.path) ?: return
        val inner = innerPathOf(item.path)
        val destination = archive.parentFile ?: return
        runOperation(OperationKind.ARCHIVE) { progress ->
            val result = runCatching {
                val target = CollisionResolver.unique(destination, item.name)
                val output = File(destination, target)
                val bytes = archiveBrowser.extract(archive, inner, output) { done, total ->
                    progress(OperationProgress(0, 1, item.name, done, total))
                }
                output.absolutePath to bytes
            }
            result.fold(
                onSuccess = { (path, bytes) ->
                    browseRepository.invalidateListing(destination.absolutePath)
                    // The new file has to be visible to search and to the gallery
                    // straight away; it did not exist when the index was built.
                    indexRepository.upsertPaths(listOf(path))
                    mediaScanner.announce(listOf(path))
                    _messages.send(UiMessage.Info("${Formatters.bytes(bytes)} extracted"))
                },
                onFailure = { _messages.send(UiMessage.Info(it.message ?: "Could not extract")) },
            )
        }
    }

    fun refresh() {
        browseRepository.invalidateListing(_state.value.path)
        load(_state.value.path)
    }

    private fun load(path: String) {
        viewModelScope.launch {
            if (path.contains(ARCHIVE_SEPARATOR)) {
                loadArchive(path)
                return@launch
            }
            val snapshot = _state.value
            val restricted = browseRepository.isRestricted(path)
            val items = if (restricted) {
                emptyList()
            } else {
                browseRepository.listItems(path, snapshot.showHidden, snapshot.sort)
            }
            _state.update { current ->
                if (current.path != path) {
                    current
                } else {
                    // A reload can remove files that were selected; dropping
                    // them keeps the counts honest instead of showing ghosts.
                    val present = items.mapTo(HashSet(items.size)) { it.path }
                    current.copy(items = items, loading = false, restricted = restricted)
                        .withSelection(current.selection.filterTo(HashSet()) { it in present })
                }
            }
        }
    }

    /**
     * A listing inside an archive, mapped into the same rows the rest of the
     * screen uses. The path keeps the archive and the entry together
     * ("…/photos.zip!/2024/one.jpg"), which is what makes every other action —
     * up, breadcrumbs, selection — work without knowing about archives.
     */
    private suspend fun loadArchive(path: String) {
        val archive = File(path.substringBefore(ARCHIVE_SEPARATOR))
        val inner = path.substringAfter(ARCHIVE_SEPARATOR).trim('/')
        val entries = runCatching { archiveBrowser.list(archive, inner) }
            .getOrElse {
                _state.update { current -> current.copy(items = emptyList(), loading = false) }
                return
            }
        val items = entries.map { entry ->
            val extension = if (entry.isDirectory) "" else MimeTypes.extensionOf(entry.name)
            FileItem(
                path = "${archive.absolutePath}$ARCHIVE_SEPARATOR${entry.path}",
                name = entry.name,
                isDirectory = entry.isDirectory,
                sizeBytes = entry.sizeBytes,
                lastModified = entry.lastModified,
                extension = extension,
                mimeType = if (entry.isDirectory) "resource/folder" else MimeTypes.mimeFor(extension),
                category = MimeTypes.categoryOf(extension),
                isHidden = entry.name.startsWith("."),
            )
        }.let { list ->
            when (_state.value.sort.field) {
                SortField.NAME -> list.sortedWith(
                    compareByDescending<FileItem> { it.isDirectory }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )
                SortField.SIZE -> list.sortedByDescending { it.sizeBytes }
                SortField.MODIFIED -> list.sortedByDescending { it.lastModified }
                SortField.TYPE -> list.sortedBy { it.extension }
            }
        }
        _state.update { current ->
            if (current.path != path) {
                current
            } else {
                current.copy(items = items, loading = false, restricted = false)
                    .withSelection(emptySet())
            }
        }
    }

    // ------------------------------------------------------------- selection

    fun toggleSelection(path: String) {
        _state.update { current ->
            val next = if (path in current.selection) {
                current.selection - path
            } else {
                current.selection + path
            }
            current.withSelection(next)
        }
    }

    fun enterSelection(path: String) {
        _state.update { it.withSelection(setOf(path)) }
    }

    fun clearSelection() {
        _state.update { it.withSelection(emptySet()) }
    }

    fun selectAll() {
        _state.update { it.withSelection(it.items.map { item -> item.path }.toSet()) }
    }

    fun setSort(field: SortField) {
        val next = _state.value.sort.toggled(field)
        _state.update { it.copy(sort = next) }
        viewModelScope.launch { settingsRepository.setSort(next.field, next.direction) }
        load(_state.value.path)
    }

    fun toggleHidden() {
        val next = !_state.value.showHidden
        _state.update { it.copy(showHidden = next) }
        viewModelScope.launch { settingsRepository.setShowHidden(next) }
        load(_state.value.path)
    }

    fun toggleViewMode() {
        val next = !_state.value.grid
        _state.update { it.copy(grid = next) }
        viewModelScope.launch { settingsRepository.setGridView(next) }
    }

    fun setFilter(category: FileCategory?) {
        _state.update { it.copy(filterCategory = category).withSelection(emptySet()) }
    }

    // ------------------------------------------------------------ mutations

    fun createFolder(name: String) {
        val parent = _state.value.path
        viewModelScope.launch {
            actions.createFolder(parent, name).fold(
                onSuccess = {
                    _messages.send(UiMessage.Info("Folder created"))
                    load(parent)
                },
                onFailure = {
                    _messages.send(
                        UiMessage.Info(it.message ?: "Could not create the folder")
                    )
                },
            )
        }
    }

    fun rename(path: String, newName: String) {
        viewModelScope.launch {
            actions.rename(path, newName).fold(
                onSuccess = {
                    _messages.send(UiMessage.Info("Renamed"))
                    load(_state.value.path)
                },
                onFailure = {
                    _messages.send(UiMessage.Info(it.message ?: "Could not rename"))
                },
            )
        }
    }

    fun deleteSelection(permanent: Boolean) {
        deletePaths(_state.value.selectedItems.map { it.path }, permanent)
    }

    fun deletePaths(paths: List<String>, permanent: Boolean) {
        if (paths.isEmpty()) return
        runOperation(if (permanent) OperationKind.DELETE else OperationKind.TRASH) { onProgress ->
            val report = actions.delete(paths, permanent, onProgress)
            _messages.send(
                UiMessage.Done(
                    text = outcome.deleted(report.succeeded, report.failed, toTrash = !permanent),
                    // Permanent deletes have nothing to walk back.
                    undoGroup = report.groupId.takeIf { !permanent && report.succeeded > 0 },
                )
            )
        }
    }

    fun copySelectionTo(destination: String) = transferSelection(destination, move = false)

    fun moveSelectionTo(destination: String) = transferSelection(destination, move = true)

    /**
     * A name clash is the user's decision, not ours. Checking before the move
     * starts means they can keep both, replace, or skip — instead of finding out
     * afterwards that every file quietly gained a "(2)".
     */
    private fun transferSelection(destination: String, move: Boolean) {
        val paths = _state.value.selectedItems.map { it.path }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val conflicts = actions.findConflicts(paths, destination)
            if (conflicts.isEmpty()) {
                transfer(paths, destination, move, ConflictPolicy.RENAME)
            } else {
                _state.update {
                    it.copy(
                        pendingConflict = PendingConflict(
                            sources = paths,
                            destination = destination,
                            isMove = move,
                            conflictingNames = conflicts.take(8),
                        )
                    )
                }
            }
        }
    }

    fun resolveConflict(policy: ConflictPolicy) {
        val pending = _state.value.pendingConflict ?: return
        _state.update { it.copy(pendingConflict = null) }
        transfer(pending.sources, pending.destination, pending.isMove, policy)
    }

    fun dismissConflict() {
        _state.update { it.copy(pendingConflict = null) }
    }

    private fun transfer(
        paths: List<String>,
        destination: String,
        move: Boolean,
        policy: ConflictPolicy,
    ) {
        runOperation(if (move) OperationKind.MOVE else OperationKind.COPY) { onProgress ->
            val report = if (move) {
                actions.move(paths, destination, policy, onProgress)
            } else {
                actions.copy(paths, destination, policy, onProgress)
            }
            _messages.send(
                UiMessage.Done(
                    "${report.succeeded} ${if (move) "moved" else "copied"}",
                    report.groupId.takeIf { report.succeeded > 0 },
                )
            )
        }
    }

    fun copyToClipboard(cut: Boolean) {
        val paths = _state.value.selectedItems.map { it.path }
        if (paths.isEmpty()) return
        _state.update { it.copy(clipboard = Clipboard(paths, cut)).withSelection(emptySet()) }
        viewModelScope.launch {
            _messages.send(
                UiMessage.Info(
                    if (cut) "${paths.size} ready to move" else "${paths.size} ready to copy"
                )
            )
        }
    }

    fun pasteIntoCurrentFolder() {
        val clipboard = _state.value.clipboard ?: return
        val destination = _state.value.path
        runOperation(if (clipboard.isCut) OperationKind.MOVE else OperationKind.COPY) { onProgress ->
            val report = if (clipboard.isCut) {
                actions.move(clipboard.paths, destination, ConflictPolicy.RENAME, onProgress)
            } else {
                actions.copy(clipboard.paths, destination, ConflictPolicy.RENAME, onProgress)
            }
            if (clipboard.isCut) _state.update { it.copy(clipboard = null) }
            _messages.send(
                UiMessage.Done(
                    "${report.succeeded} ${if (clipboard.isCut) "moved" else "copied"}",
                    report.groupId.takeIf { report.succeeded > 0 },
                )
            )
        }
    }

    fun clearClipboard() {
        _state.update { it.copy(clipboard = null) }
    }

    fun addShortcut(path: String) {
        viewModelScope.launch {
            browseRepository.addShortcut(path)
            _messages.send(UiMessage.Info("Added to places"))
        }
    }

    fun toggleStar(item: FileItem) {
        viewModelScope.launch {
            val starred = actions.toggleStar(item)
            _messages.send(
                UiMessage.Info(if (starred) "Starred" else "Star removed")
            )
        }
    }

    /**
     * Compresses the selection into a zip beside it, named after the folder or
     * the first file — asking the user to type a name for something they did
     * not plan is friction for no reason.
     */
    fun compressSelection() {
        val items = _state.value.selectedItems
        if (items.isEmpty()) return
        val folder = _state.value.path
        val baseName = when {
            items.size == 1 -> items.first().name.substringBeforeLast('.')
            else -> Paths.nameOf(folder).ifEmpty { "archive" }
        }

        runOperation(OperationKind.ARCHIVE) { onProgress ->
            val destination = actions.suggestArchivePath(folder, baseName)
            val result = actions.compress(items.map { it.path }, destination) { progress ->
                onProgress(
                    OperationProgress(
                        progress.done,
                        progress.total,
                        progress.name,
                        progress.bytesDone,
                        progress.bytesTotal,
                    )
                )
            }
            result.fold(
                onSuccess = {
                    _messages.send(UiMessage.Info("${it.entries} items compressed"))
                },
                onFailure = {
                    _messages.send(UiMessage.Info(it.message ?: "Could not compress"))
                },
            )
        }
    }

    fun extract(item: FileItem) {
        val folder = _state.value.path
        runOperation(OperationKind.ARCHIVE) { onProgress ->
            val result = actions.extract(item.path, folder) { progress ->
                onProgress(
                    OperationProgress(
                        progress.done,
                        progress.total,
                        progress.name,
                        progress.bytesDone,
                        progress.bytesTotal,
                    )
                )
            }
            result.fold(
                onSuccess = {
                    _messages.send(UiMessage.Info("${it.entries} items extracted"))
                },
                onFailure = {
                    _messages.send(UiMessage.Info(it.message ?: "Could not extract"))
                },
            )
        }
    }

    /**
     * Judged on the whole name rather than the extension: a `.tar.gz` ends in
     * "gz", and a plain gzipped file is not an archive of files.
     */
    fun isArchive(item: FileItem): Boolean = ArchiveFormat.of(item.name) != null

    fun cancelOperation() {
        operationJob?.cancel()
        operationJob = null
        operationRunner.cancel()
    }

    /** Reverses exactly the group the user was just told about. */
    fun undo(groupId: String) {
        viewModelScope.launch {
            val result = actions.undoGroup(groupId)
            load(_state.value.path)
            _messages.send(
                UiMessage.Info(
                    if (result.failed > 0) {
                        "${result.restored} restored, ${result.failed} failed"
                    } else {
                        "${result.restored} changes reverted"
                    }
                )
            )
        }
    }

    /**
     * Every mutation goes through one wrapper so progress, cancellation and the
     * undo handle behave identically no matter which action started it.
     *
     * The work itself runs on the application scope, so navigating away no
     * longer cancels a copy in flight and leaves a half-written file behind.
     * The group id comes from the repository rather than being invented here:
     * a locally generated id would never match the journal, and an undo that
     * silently does nothing is worse than no undo button at all.
     */
    private fun runOperation(
        kind: OperationKind,
        block: suspend (onProgress: (OperationProgress) -> Unit) -> Unit,
    ) {
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            try {
                operationRunner.run(kind) { onProgress -> block(onProgress) }
            } catch (_: CancellationException) {
                _messages.send(UiMessage.Info("Stopped"))
            } finally {
                _state.update { it.withSelection(emptySet()) }
                // The app just changed this folder; the cache must not serve the
                // listing from before the change.
                browseRepository.invalidateListing(_state.value.path)
                load(_state.value.path)
            }
        }
    }

    fun isRestricted(path: String): Boolean = browseRepository.isRestricted(path)

    fun exists(path: String): Boolean = File(path).exists()

    companion object {
        const val KEY_PATH = "browser_path"

        /**
         * Marks the boundary between an archive and a path inside it. Chosen
         * because no filename can contain it: "!" and "/" cannot both appear in
         * one path segment on any filesystem this app can see.
         */
        const val ARCHIVE_SEPARATOR = "!/"
        const val ARCHIVE_CACHE_DIR = "archive-preview"
    }
}
