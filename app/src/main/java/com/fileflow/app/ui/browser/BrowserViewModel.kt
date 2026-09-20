package com.fileflow.app.ui.browser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.model.SortField
import com.fileflow.app.core.model.SortOrder
import com.fileflow.app.core.storage.ArchiveFormat
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationProgress
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.OutcomeMessages
import com.fileflow.app.core.util.Paths
import com.fileflow.app.data.prefs.SettingsRepository
import com.fileflow.app.data.repo.ActiveOperation
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.ui.util.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
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
        if (_state.value.atVolumeRoot) return
        Paths.parentOf(_state.value.path)?.let { openFolder(it) }
    }

    fun refresh() {
        browseRepository.invalidateListing(_state.value.path)
        load(_state.value.path)
    }

    private fun load(path: String) {
        viewModelScope.launch {
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
    }
}
