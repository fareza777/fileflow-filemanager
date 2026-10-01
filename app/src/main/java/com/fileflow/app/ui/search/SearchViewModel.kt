package com.fileflow.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationReport
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.ContentHit
import com.fileflow.app.data.repo.ContentIndexRepository
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.MetadataCleanup
import com.fileflow.app.data.repo.FileIndexRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.search.FileSearchEngine
import com.fileflow.app.domain.search.SearchFilters
import com.fileflow.app.domain.toFileItem
import com.fileflow.app.ui.util.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchSort { RELEVANCE, NAME, SIZE, NEWEST }

/**
 * What the query is matched against.
 *
 * Names are instant and cheap. Contents answer the question a name never can —
 * "which of my files talks about this?" — and the vector mode answers it for
 * documents that do not use the same words. Each is a different index and a
 * different cost, so the user picks rather than the app guessing.
 */
enum class SearchMode { NAME, CONTENT, SIMILAR }

data class SearchState(
    val query: String = "",
    val rootPath: String = "",
    val deep: Boolean = true,
    val filters: SearchFilters = SearchFilters(),
    val results: List<Candidate> = emptyList(),
    val searching: Boolean = false,
    val scanned: Int = 0,
    val selection: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val showFilters: Boolean = true,
    val sort: SearchSort = SearchSort.RELEVANCE,
    val mode: SearchMode = SearchMode.NAME,
    /** Hits from the content index, when the mode is not [SearchMode.NAME]. */
    val hits: List<ContentHit> = emptyList(),
    val contentIndexed: Int = 0,
    val contentIndexing: Boolean = false,
    val contentProgress: String? = null,
    /** True when the results came from the index rather than a live scan. */
    val indexed: Boolean = false,
    /**
     * When the index was last built, if the answer came from it. An index is a
     * snapshot; a search that quietly answers from a three-week-old one is
     * worse than a slow search, because it is confidently wrong. Surfacing the
     * age turns a silent failure into the user's decision.
     */
    val indexAge: Long? = null,
    val reindexing: Boolean = false,
) {
    val selected: List<Candidate> get() = results.filter { it.path in selection }
    val selectedBytes: Long get() = selected.sumOf { it.sizeBytes }
    val resultBytes: Long get() = results.sumOf { it.sizeBytes }
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchEngine: FileSearchEngine,
    private val browseRepository: BrowseRepository,
    private val actions: FileActionsRepository,
    private val indexRepository: FileIndexRepository,
    private val contentIndex: ContentIndexRepository,
    private val operationRunner: OperationRunner,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var searchJob: Job? = null
    private var operationJob: Job? = null
    private var reindexJob: Job? = null

    /** Where the folder picker starts when the search is device-wide. */
    private val _deviceRoot = MutableStateFlow("")
    val deviceRoot: StateFlow<String> = _deviceRoot.asStateFlow()

    init {
        // An empty root means the whole device — every volume, not just the
        // primary one. It used to default to the primary root, so searches
        // opened from the browser silently skipped the SD card, and there was
        // no way to tell "scoped" from "everywhere" in the state. The root is
        // read off the main thread: enumerating volumes touches the disk.
        viewModelScope.launch {
            _deviceRoot.value = withContext(Dispatchers.IO) { browseRepository.primaryRoot() }
        }
        viewModelScope.launch {
            contentIndex.observeCount().collect { count ->
                _state.update { it.copy(contentIndexed = count) }
            }
        }
    }

    fun setRoot(path: String) {
        _state.update { it.copy(rootPath = path) }
        runSearch()
    }

    /** Back to device-wide search from inside a folder. */
    fun clearRoot() {
        _state.update { it.copy(rootPath = "") }
        runSearch()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        if (_state.value.mode == SearchMode.NAME) scheduleSearch() else scheduleContentSearch()
    }

    // ------------------------------------------------------ content search

    fun setMode(mode: SearchMode) {
        if (_state.value.mode == mode) return
        _state.update { it.copy(mode = mode, hits = emptyList()) }
        if (mode == SearchMode.NAME) runSearch() else runContentSearch()
    }

    private fun scheduleContentSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            runContentSearchNow()
        }
    }

    fun runContentSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runContentSearchNow() }
    }

    private suspend fun runContentSearchNow() {
        val snapshot = _state.value
        val needle = snapshot.query.trim()
        if (needle.isEmpty()) {
            _state.update { it.copy(hits = emptyList(), searching = false) }
            return
        }
        _state.update { it.copy(searching = true, hits = emptyList()) }
        val hits = runCatching {
            when (snapshot.mode) {
                SearchMode.SIMILAR -> contentIndex.searchSimilar(needle)
                else -> contentIndex.search(needle)
            }
        }.getOrDefault(emptyList())
        _state.update { it.copy(searching = false, hits = hits, scanned = hits.size) }
    }

    /**
     * Reads every readable document into the index. Run by hand because it is
     * the one operation here that touches every file on the device — a search
     * that starts that on its own is a search that heats the phone.
     */
    fun buildContentIndex() {
        if (_state.value.contentIndexing) return
        viewModelScope.launch {
            _state.update { it.copy(contentIndexing = true, contentProgress = null) }
            val total = runCatching {
                contentIndex.rebuild { indexed, name ->
                    _state.update { it.copy(contentProgress = "$indexed · $name") }
                }
            }.getOrDefault(0)
            _state.update {
                it.copy(contentIndexing = false, contentProgress = null, contentIndexed = total)
            }
            _messages.send(UiMessage.Info("Indexed $total documents"))
        }
    }

    fun toggleDeep() {
        _state.update { it.copy(deep = !it.deep) }
        runSearch()
    }

    fun toggleFilters() {
        _state.update { it.copy(showFilters = !it.showFilters) }
    }

    fun toggleCategory(category: FileCategory) {
        _state.update { current ->
            val next = if (category in current.filters.categories) {
                current.filters.categories - category
            } else {
                current.filters.categories + category
            }
            current.copy(filters = current.filters.copy(categories = next))
        }
        runSearch()
    }

    fun setSizeFilter(minBytes: Long?, maxBytes: Long?) {
        _state.update { it.copy(filters = it.filters.copy(minBytes = minBytes, maxBytes = maxBytes)) }
        runSearch()
    }

    fun setModifiedAfter(millis: Long?) {
        _state.update { it.copy(filters = it.filters.copy(modifiedAfter = millis)) }
        runSearch()
    }

    fun clearFilters() {
        _state.update { it.copy(filters = SearchFilters()) }
        runSearch()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            runSearchInternal()
        }
    }

    fun runSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearchInternal() }
    }

    fun stopSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searching = false) }
    }

    private suspend fun runSearchInternal() {
        val snapshot = _state.value
        val needle = snapshot.query.trim()
        if (needle.isEmpty() && !snapshot.filters.isActive) {
            _state.update { it.copy(results = emptyList(), searching = false, scanned = 0) }
            return
        }

        _state.update {
            it.copy(
                searching = true,
                results = emptyList(),
                scanned = 0,
                selection = emptySet(),
                selectionMode = false,
                indexed = false,
                indexAge = null,
            )
        }

        // The index answers in milliseconds; walking the filesystem can take a
        // minute. Falling through to the walk keeps search working before the
        // first index build has finished. Root and depth now reach the SQL as
        // well: searching from inside a folder used to return the whole device.
        val scoped = snapshot.rootPath.isNotBlank()
        val indexed = runCatching {
            indexRepository.search(
                needle,
                snapshot.filters,
                root = snapshot.rootPath.takeIf { scoped },
                deep = snapshot.deep,
            )
        }.getOrNull()

        if (indexed != null) {
            val indexedAt = runCatching { indexRepository.indexedAt() }.getOrNull()
            _state.update {
                it.copy(
                    results = indexed.sortedFor(it.sort),
                    searching = false,
                    scanned = indexed.size,
                    indexed = true,
                    indexAge = indexedAt,
                )
            }
            return
        }

        runLiveScan(needle, snapshot)
    }

    /**
     * The pre-index path: stream matches while walking, so results appear as
     * they are found rather than after the whole device has been read.
     */
    private suspend fun runLiveScan(needle: String, snapshot: SearchState) {
        var scanned = 0
        val collected = mutableListOf<Candidate>()
        // No explicit root means every volume, not just the primary one.
        val roots = if (snapshot.rootPath.isNotBlank()) {
            listOf(snapshot.rootPath)
        } else {
            withContext(Dispatchers.IO) { browseRepository.volumes().map { it.rootPath } }
        }
        try {
            searchEngine.search(
                query = needle,
                filters = snapshot.filters,
                roots = roots,
                deep = snapshot.deep,
            ).collect { candidate ->
                scanned++
                if (collected.size < MAX_RESULTS) {
                    collected += candidate
                } else {
                    throw SearchLimitReached()
                }
                if (collected.size % 20 == 0 || scanned % 200 == 0) {
                    _state.update { it.copy(results = collected.toList(), scanned = scanned) }
                }
            }
            _state.update {
                it.copy(
                    results = collected.sortedFor(it.sort),
                    searching = false,
                    scanned = scanned,
                )
            }
        } catch (_: SearchLimitReached) {
            _state.update {
                it.copy(
                    results = collected.sortedFor(it.sort),
                    searching = false,
                    scanned = scanned,
                )
            }
        } catch (_: CancellationException) {
            _state.update { it.copy(searching = false) }
        }
    }

    fun setSort(sort: SearchSort) {
        _state.update {
            it.copy(sort = sort, results = it.results.sortedFor(sort))
        }
    }

    /**
     * Rebuilds the index, then re-runs the current search against it. The
     * stale-index notice offers this instead of leaving the user with results
     * they have learned not to trust and no way to fix.
     */
    fun reindex() {
        if (_state.value.reindexing) return
        reindexJob?.cancel()
        reindexJob = viewModelScope.launch {
            _state.update { it.copy(reindexing = true) }
            try {
                indexRepository.rebuild()
            } finally {
                _state.update { it.copy(reindexing = false) }
            }
            runSearch()
        }
    }

    private fun List<Candidate>.sortedFor(sort: SearchSort): List<Candidate> = when (sort) {
        SearchSort.RELEVANCE -> this
        SearchSort.NAME -> sortedBy { it.name.lowercase() }
        SearchSort.SIZE -> sortedByDescending { it.sizeBytes }
        SearchSort.NEWEST -> sortedByDescending { it.lastModified }
    }

    fun toggleSelection(path: String) {
        _state.update { current ->
            val selection = if (path in current.selection) {
                current.selection - path
            } else {
                current.selection + path
            }
            current.copy(selection = selection, selectionMode = selection.isNotEmpty())
        }
    }

    fun selectAll() {
        _state.update { it.copy(selection = it.results.map { c -> c.path }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet(), selectionMode = false) }
    }

    fun deleteSelected(permanent: Boolean) {
        val paths = _state.value.selected.map { it.path }
        if (paths.isEmpty()) return
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            try {
                // Through the shared runner rather than this ViewModel's scope:
                // leaving the screen used to cancel the delete mid-flight, and
                // the root-level progress bar only shows operations the runner
                // owns.
                var report: OperationReport? = null
                operationRunner.run(
                    if (permanent) OperationKind.DELETE else OperationKind.TRASH
                ) { progress ->
                    report = actions.delete(paths, permanent, progress)
                }
                _state.update {
                    it.copy(
                        results = it.results.filterNot { r -> r.path in paths },
                        selection = emptySet(),
                        selectionMode = false,
                    )
                }
                val done = report
                if (done != null) {
                    _messages.send(
                        if (done.failed > 0) {
                            UiMessage.Info("${done.succeeded} done, ${done.failed} failed")
                        } else {
                            UiMessage.Done(
                                text = "${done.succeeded} processed",
                                undoGroup = done.groupId.takeIf { !permanent && done.succeeded > 0 },
                            )
                        }
                    )
                }
            } catch (_: CancellationException) {
                // Stopped from the global bar, or superseded by a newer batch;
                // the runner owns the indicator, so nothing to clean up here.
            }
        }
    }

    fun moveSelectedTo(destination: String) {
        val paths = _state.value.selected.map { it.path }
        if (paths.isEmpty()) return
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            try {
                var report: OperationReport? = null
                operationRunner.run(OperationKind.MOVE) { progress ->
                    report = actions.move(
                        paths,
                        destination,
                        com.fileflow.app.core.storage.ConflictPolicy.RENAME,
                        progress,
                    )
                }
                _state.update {
                    it.copy(
                        results = it.results.filterNot { r -> r.path in paths },
                        selection = emptySet(),
                        selectionMode = false,
                    )
                }
                val done = report
                if (done != null) {
                    _messages.send(
                        UiMessage.Done(
                            "${done.succeeded} moved",
                            done.groupId.takeIf { done.succeeded > 0 },
                        )
                    )
                }
            } catch (_: CancellationException) {
                // See deleteSelected: the runner owns the indicator.
            }
        }
    }

    /** Reverses exactly the batch the snackbar just offered to undo. */
    fun undo(groupId: String) {
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            val result = actions.undoGroup(groupId)
            _messages.send(UiMessage.Info("${result.restored} changes reverted"))
            runSearch()
        }
    }

    /** The same batch, left where it is. */
    fun copySelectedTo(destination: String) {
        val paths = _state.value.selected.map { it.path }
        if (paths.isEmpty()) return
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            var report: OperationReport? = null
            try {
                operationRunner.run(OperationKind.COPY) { progress ->
                    report = actions.copy(
                        paths,
                        destination,
                        com.fileflow.app.core.storage.ConflictPolicy.RENAME,
                        progress,
                    )
                }
            } catch (_: CancellationException) {
                // The runner owns the indicator; nothing to undo here.
            }
            _state.update { it.copy(selection = emptySet(), selectionMode = false) }
            report?.let { done ->
                _messages.send(UiMessage.Info("${done.succeeded} copied"))
            }
        }
    }

    /** Strips metadata from each selected image, to a copy beside it. */
    fun cleanMetadata() {
        val paths = _state.value.selected.map { it.path }
        if (paths.isEmpty()) return
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            var cleanup: MetadataCleanup? = null
            try {
                operationRunner.run(OperationKind.CLEAN) { progress ->
                    cleanup = actions.cleanMetadata(paths, progress)
                }
            } catch (_: CancellationException) {
                return@launch
            }
            _state.update { it.copy(selection = emptySet(), selectionMode = false) }
            cleanup?.let { result ->
                _messages.send(
                    UiMessage.Info(
                        if (result.cleaned == 0 && result.failed == 0) {
                            "Nothing to remove"
                        } else {
                            "Cleaned ${result.cleaned} files · " +
                                "${Formatters.bytes(result.savedBytes)} removed"
                        }
                    )
                )
            }
        }
    }

    fun starSelection() {
        val items = _state.value.selected
        if (items.isEmpty()) return
        viewModelScope.launch {
            val allStarred = items.all { actions.isStarred(it.path) }
            items.forEach { candidate ->
                actions.setStar(candidate.toFileItem(), starred = !allStarred)
            }
            _state.update { it.copy(selection = emptySet(), selectionMode = false) }
            _messages.send(UiMessage.Info(if (allStarred) "Star removed" else "Starred ${items.size}"))
        }
    }

    fun toFileItems(candidates: List<Candidate>): List<FileItem> =
        candidates.map { it.toFileItem() }

    companion object {
        private const val MAX_RESULTS = 5000
    }
}

/** Thrown internally to stop a walk that has already filled the result list. */
private class SearchLimitReached : RuntimeException()
