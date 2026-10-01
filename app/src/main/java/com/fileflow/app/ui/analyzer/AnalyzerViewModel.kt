package com.fileflow.app.ui.analyzer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.model.VolumeInfo
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.apps.AppBackup
import com.fileflow.app.domain.scan.AnalysisResult
import com.fileflow.app.domain.scan.Breadcrumb
import com.fileflow.app.domain.scan.StorageAnalyzer
import com.fileflow.app.domain.scan.UsageEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    val atRoot: Boolean get() = focusPath.isEmpty() || focusPath == root

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
) : ViewModel() {

    private val _state = MutableStateFlow(AnalyzerState())
    val state: StateFlow<AnalyzerState> = _state.asStateFlow()

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
                )
            }
        }
    }

    fun primaryRoot(): String = browseRepository.primaryRoot()
}
