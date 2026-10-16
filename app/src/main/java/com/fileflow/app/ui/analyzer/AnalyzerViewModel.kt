package com.fileflow.app.ui.analyzer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.model.VolumeInfo
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationReport
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.apps.AppBackup
import com.fileflow.app.domain.scan.AnalysisResult
import com.fileflow.app.domain.scan.Breadcrumb
import com.fileflow.app.domain.scan.StorageAnalyzer
import com.fileflow.app.domain.scan.UsageEntry
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

@androidx.compose.runtime.Immutable
data class AnalyzerState(
    val running: Boolean = true,
    val scanned: Int = 0,
    val result: AnalysisResult? = null,
    /** Empty until the first scan lands; the drill-down needs measured sizes. */
    val root: String = "",
    val focusPath: String = "",
    val crumbs: List<Breadcrumb> = emptyList(),
    val entries: List<UsageEntry> = emptyList(),
    val largest: List<Candidate> = emptyList(),
    val loadingEntries: Boolean = false,
    val category: FileCategory? = null,
    /** Every volume the phone has: internal storage, and a card if there is one. */
    val volumes: List<VolumeInfo> = emptyList(),
    val volumePath: String = "",
    /** Installed packages, counted separately from the file walk. */
    val apps: AppSummary? = null,
    /** Paths picked up by a long-press, for bulk share / copy / delete. */
    val selection: Set<String> = emptySet(),
    /** Derived when the selection changes, never summed during a frame. */
    val selectedBytes: Long = 0L,
    /** A bulk operation is running; the action bar stands down while it does. */
    val working: Boolean = false,
) {
    val atRoot: Boolean get() = focusPath.isEmpty() || focusPath == root

    /** Rows are tickable while a selection is open. */
    val selecting: Boolean get() = selection.isNotEmpty()

    fun withSelection(next: Set<String>): AnalyzerState {
        if (next.isEmpty()) return copy(selection = emptySet(), selectedBytes = 0L)
        return copy(
            selection = next,
            selectedBytes = next.sumOf { path ->
                entries.firstOrNull { it.path == path }?.bytes
                    ?: largest.firstOrNull { it.path == path }?.sizeBytes
                    ?: 0L
            },
        )
    }

    val focusName: String
        get() = crumbs.lastOrNull()?.name ?: ""

    /** What the header shows for the level being looked at. */
    val focusBytes: Long
        get() = if (atRoot) result?.totalBytes ?: 0L else entries.sumOf { it.bytes }

    val showVolumePicker: Boolean get() = volumes.size > 1
}

/** Installed apps, as the storage screen reports them. */
@androidx.compose.runtime.Immutable
data class AppSummary(val count: Int, val bytes: Long)

@HiltViewModel
class AnalyzerViewModel @Inject constructor(
    private val analyzer: StorageAnalyzer,
    private val browseRepository: BrowseRepository,
    private val appBackup: AppBackup,
    private val actions: FileActionsRepository,
    private val operationRunner: OperationRunner,
    private val volumes: com.fileflow.app.core.storage.VolumeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AnalyzerState())
    val state: StateFlow<AnalyzerState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var job: Job? = null
    private var entriesJob: Job? = null

    init {
        start()
        viewModelScope.launch {
            // Volumes so the storage figure can be about the card as well as
            // about internal storage — a phone with an SD card has two, and
            // showing only the first is half an answer.
            val volumes = browseRepository.volumes()
            val apps = runCatching {
                val installed = appBackup.installed()
                AppSummary(installed.size, installed.sumOf { it.sizeBytes })
            }.getOrNull()
            _state.update { it.copy(volumes = volumes, apps = apps) }
        }
    }

    fun setVolume(path: String) {
        if (path == _state.value.volumePath) return
        analyzer.invalidate()
        _state.update { it.copy(volumePath = path) }
        start(force = true)
    }

    fun start(force: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            val root = _state.value.volumePath.ifBlank { browseRepository.primaryRoot() }
            _state.update {
                it.copy(
                    running = true,
                    scanned = 0,
                    result = null,
                    root = root,
                    focusPath = "",
                    crumbs = emptyList(),
                    entries = emptyList(),
                    largest = emptyList(),
                )
            }
            try {
                // Partial snapshots carry the totals and the category bars, so
                // the breakdown fills in live rather than sitting behind a
                // spinner for the length of a full walk.
                val result = analyzer.analyze(
                    force = force,
                    onProgress = { seen -> _state.update { it.copy(scanned = seen) } },
                    onPartial = { partial ->
                        _state.update { current ->
                            if (current.result?.dirBytes?.isNotEmpty() == true) current
                            else current.copy(result = partial)
                        }
                    },
                )
                _state.update { it.copy(running = false, result = result) }
                show(result, root)
            } catch (_: CancellationException) {
                _state.update { it.copy(running = false) }
            }
        }
    }

    /** The refresh button always re-walks; nothing else should. */
    fun refresh() {
        analyzer.invalidate()
        start(force = true)
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(running = false) }
    }

    /** Opens a folder inside the analyzer instead of jumping to another tab. */
    fun open(entry: UsageEntry) {
        if (!entry.isDirectory) return
        val result = _state.value.result ?: return
        _state.update {
            it.copy(focusPath = entry.path).let { next ->
                next.copy(entries = emptyList(), loadingEntries = true)
            }
        }
        loadEntries(result, entry.path)
    }

    /** Up one level. Returns false when already at the top, so Back can leave. */
    fun up(): Boolean {
        val current = _state.value
        val result = current.result ?: return false
        if (current.atRoot) return false
        val parent = current.crumbs.dropLast(1).lastOrNull()?.path ?: current.root
        _state.update { it.copy(focusPath = parent, loadingEntries = true) }
        loadEntries(result, parent)
        return true
    }

    /** Jumps to a level from the breadcrumb. */
    fun jumpTo(path: String) {
        val result = _state.value.result ?: return
        _state.update { it.copy(focusPath = path, loadingEntries = true) }
        loadEntries(result, path)
    }

    fun setCategory(category: FileCategory?) {
        val result = _state.value.result ?: return
        _state.update {
            it.copy(category = if (it.category == category) null else category)
        }
        loadEntries(result, _state.value.focusPath)
    }

    /** Jumps straight to the folder holding a large file. */
    fun revealFile(candidate: Candidate) {
        val result = _state.value.result ?: return
        val parent = candidate.path.substringBeforeLast('/')
        _state.update { it.copy(focusPath = parent, loadingEntries = true) }
        loadEntries(result, parent)
    }

    private fun show(result: AnalysisResult, root: String) {
        _state.update { it.copy(root = root, focusPath = root) }
        loadEntries(result, root)
    }

    private fun loadEntries(result: AnalysisResult, path: String) {
        entriesJob?.cancel()
        entriesJob = viewModelScope.launch {
            val category = _state.value.category
            val entries = analyzer.entriesFor(path, result, category)
            val crumbs = analyzer.breadcrumbFor(path, result.root)
            _state.update {
                it.copy(
                    entries = entries,
                    crumbs = crumbs,
                    loadingEntries = false,
                    largest = result.largestFiles.take(25),
                    // A selection is about the rows it was made on; changing
                    // level (or filter) replaces those rows, so it goes too.
                ).withSelection(emptySet())
            }
        }
    }

    // ------------------------------------------------------------- selection

    /** Long-press: opens the selection and ticks the row that was pressed. */
    fun startSelection(path: String) {
        _state.update { current ->
            current.withSelection(current.selection + path)
        }
    }

    /** Tap while a selection is open: ticks or unticks one row. */
    fun toggleSelected(path: String) {
        _state.update { current ->
            current.withSelection(
                if (path in current.selection) current.selection - path
                else current.selection + path,
            )
        }
    }

    fun selectAll() {
        _state.update { current ->
            current.withSelection(
                (current.entries.map { it.path } + current.largest.map { it.path }).toSet(),
            )
        }
    }

    fun clearSelection() {
        _state.update { it.withSelection(emptySet()) }
    }

    /** The standard folders of the volume the selection lives on, for copy/move. */
    fun quickDestinations(): List<FolderOption> {
        val anchor = _state.value.selection.firstOrNull() ?: _state.value.root
        val root = volumes.volumeRootOf(anchor)
        val rootLabel = volumes.volumes().firstOrNull { it.rootPath == root }?.label
            ?: root.substringAfterLast('/')
        val options = mutableListOf(FolderOption(rootLabel, root))
        listOf("Download", "Documents", "Pictures", "DCIM", "Music", "Movies").forEach { name ->
            val dir = File(root, name)
            if (dir.isDirectory) options += FolderOption(name, dir.absolutePath)
        }
        return options
    }

    // ---------------------------------------------------------- bulk actions

    /** Share is handed to the system sheet; nothing here changes on disk. */
    fun selectedPaths(): List<String> = _state.value.selection.toList()

    fun copySelection(destination: String) = transfer(OperationKind.COPY, destination)

    fun moveSelection(destination: String) = transfer(OperationKind.MOVE, destination)

    private fun transfer(kind: OperationKind, destination: String) {
        val paths = selectedPaths()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                var report: OperationReport? = null
                operationRunner.run(kind) { progress ->
                    report = if (kind == OperationKind.COPY) {
                        // RENAME rather than overwrite: bulk actions on a
                        // checked list must never silently eat a same-named file
                        // the user never saw in that list.
                        actions.copy(paths, destination, ConflictPolicy.RENAME, progress)
                    } else {
                        actions.move(paths, destination, ConflictPolicy.RENAME, progress)
                    }
                }
                _state.update { it.copy(working = false) }
                val done = report
                if (done != null) {
                    _messages.send(
                        UiMessage.Done(
                            if (kind == OperationKind.COPY) "${done.succeeded} items copied"
                            else "${done.succeeded} items moved",
                            done.groupId.takeIf { kind == OperationKind.MOVE && done.succeeded > 0 },
                        ),
                    )
                }
                clearSelection()
                refresh()
            } catch (_: CancellationException) {
                _state.update { it.copy(working = false) }
            }
        }
    }

    /** To the app's trash, with the journal entry that makes undo real. */
    fun deleteSelection() {
        val paths = selectedPaths()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                var report: OperationReport? = null
                operationRunner.run(OperationKind.TRASH) { progress ->
                    report = actions.delete(paths, permanent = false, progress)
                }
                _state.update { it.copy(working = false) }
                val done = report
                if (done != null) {
                    _messages.send(
                        UiMessage.Done(
                            "${done.succeeded} items deleted",
                            done.groupId.takeIf { done.succeeded > 0 },
                        ),
                    )
                }
                clearSelection()
                refresh()
            } catch (_: CancellationException) {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun undo(groupId: String) {
        viewModelScope.launch {
            val result = actions.undoGroup(groupId)
            _messages.send(UiMessage.Info("${result.restored} changes reverted"))
            refresh()
        }
    }

    fun primaryRoot(): String = browseRepository.primaryRoot()
}

/** A destination the bulk copy/move dialog offers. */
@androidx.compose.runtime.Immutable
data class FolderOption(val name: String, val path: String)
