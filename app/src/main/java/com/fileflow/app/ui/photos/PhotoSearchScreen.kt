package com.fileflow.app.ui.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.LinearProgressIndicator
import com.fileflow.app.ui.components.SectionHeader
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.saveable.rememberSaveable
import com.fileflow.app.core.vision.PhotoMatch
import com.fileflow.app.core.vision.ThemeVocabulary
import com.fileflow.app.ui.components.ConfirmDialog
import kotlinx.coroutines.ensureActive
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import coil.compose.AsyncImage
import com.fileflow.app.R
import android.content.Context
import com.fileflow.app.core.semantic.DownloadState
import com.fileflow.app.core.semantic.ModelDownloadManager
import com.fileflow.app.core.semantic.SemanticAsset
import com.fileflow.app.core.semantic.SemanticEngine
import com.fileflow.app.core.semantic.SemanticHit
import com.fileflow.app.core.semantic.SemanticModels
import com.fileflow.app.core.semantic.PhotoWorkManager
import com.fileflow.app.core.semantic.PhotoWorkState
import com.fileflow.app.core.semantic.SemanticStatus
import com.fileflow.app.service.ModelDownloadService
import com.fileflow.app.service.PhotoWorkService
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.vision.PhotoHit
import com.fileflow.app.core.vision.PhotoIndexer
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.SearchField
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage
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
data class PhotoSearchState(
    val query: String = "",
    val results: List<PhotoHit> = emptyList(),
    val indexed: Int = 0,
    /** How many photos the library holds in total, for "read X of Y". */
    val knownTotal: Int = 0,
    val indexing: Boolean = false,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val progressName: String = "",
    val searching: Boolean = false,
    /** True before the first index run, when there is nothing to search yet. */
    val neverIndexed: Boolean = true,
    /** The downloaded model tier: not installed, being fetched, or working. */
    val modelInstalled: Boolean = false,
    val modelReady: Boolean = false,
    val modelBytes: Long = 0L,
    val modelDownload: SemanticStatus? = null,
    val embedded: Int = 0,
    val embedding: Boolean = false,
    /** Photos found by meaning rather than by text or label. */
    val semantic: List<SemanticHit> = emptyList(),
    /** True when the query was recognised and translated into English prompts. */
    val queryCurated: Boolean = false,
) {
    val showingRecent: Boolean get() = query.isBlank()
    val foundCount: Int get() = (results.map { it.path } + semantic.map { it.path }).distinct().size
    val modelTotalMb: Long get() = SemanticAsset.entries.sumOf { it.megabytes }
}

@HiltViewModel
class PhotoSearchViewModel @Inject constructor(
    private val indexer: PhotoIndexer,
    private val semantic: SemanticEngine,
    private val downloads: ModelDownloadManager,
    private val models: SemanticModels,
    private val work: PhotoWorkManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(PhotoSearchState())
    val state: StateFlow<PhotoSearchState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            indexer.observeCount().collect { count ->
                _state.update { it.copy(indexed = count, neverIndexed = count == 0) }
            }
        }
        viewModelScope.launch {
            // What the library weighs in at, so "210 photos read" can answer the
            // question it provokes: 210 of how many?
            val known = runCatching { indexer.knownCount() }.getOrDefault(0)
            _state.update { it.copy(knownTotal = known) }
        }
        viewModelScope.launch {
            semantic.observeCount().collect { count -> _state.update { it.copy(embedded = count) } }
        }
        // The two reading passes belong to the service now, so leaving the
        // screen — or the app — does not stop them. This only mirrors the
        // service's progress, the same way the download is mirrored.
        viewModelScope.launch {
            work.state.collect { progress ->
                when (progress) {
                    is PhotoWorkState.Running -> _state.update {
                        it.copy(
                            indexing = progress.kind == PhotoWorkState.Kind.INDEXING,
                            embedding = progress.kind == PhotoWorkState.Kind.EMBEDDING,
                            progressDone = progress.done,
                            progressTotal = progress.total,
                            progressName = progress.name,
                        )
                    }

                    is PhotoWorkState.Finished -> {
                        _state.update {
                            it.copy(
                                indexing = false,
                                embedding = false,
                                progressDone = 0,
                                progressTotal = 0,
                                progressName = "",
                            )
                        }
                        when (progress.kind) {
                            PhotoWorkState.Kind.INDEXING -> {
                                val known = runCatching { indexer.knownCount() }.getOrDefault(0)
                                _state.update { it.copy(knownTotal = known) }
                                _messages.send(
                                    UiMessage.Info(
                                        if (progress.counted == 0) {
                                            "Nothing new to read"
                                        } else {
                                            "Read ${progress.counted} photos"
                                        },
                                    ),
                                )
                            }
                            PhotoWorkState.Kind.EMBEDDING -> _messages.send(
                                UiMessage.Info(
                                    if (progress.counted == 0) {
                                        "No photo left to read by meaning"
                                    } else {
                                        "Read ${progress.counted} photos by meaning"
                                    },
                                ),
                            )
                        }
                        if (_state.value.query.isNotBlank()) setQuery(_state.value.query) else showRecent()
                    }

                    is PhotoWorkState.Failed -> {
                        _state.update {
                            it.copy(indexing = false, embedding = false, progressName = "")
                        }
                        _messages.send(UiMessage.Info(progress.reason))
                    }

                    PhotoWorkState.Idle -> _state.update {
                        it.copy(indexing = false, embedding = false, progressName = "")
                    }
                }
            }
        }
        viewModelScope.launch {
            // What a previous run left behind, read before anything else: the
            // screen should open showing the real number, not zero.
            downloads.restoreState()
            val installed = models.ready()
            _state.update {
                it.copy(
                    modelInstalled = installed,
                    modelReady = false,
                    modelBytes = semantic.sizeOnDisk(),
                    // Opening the model is neither "ready" nor "failed": say
                    // so, rather than letting the card claim it could not open
                    // while it is still opening.
                    modelDownload = if (installed) SemanticStatus.Loading else null,
                )
            }
            if (installed) {
                val ready = semantic.ready()
                _state.update {
                    it.copy(
                        modelReady = ready,
                        // The engine says why, in the model's own terms: which
                        // file is missing, or which tensor shapes it did not
                        // recognise. "The model did not load" is not an answer.
                        modelDownload = if (ready) {
                            null
                        } else {
                            SemanticStatus.Failed(semantic.loadFailure ?: "the model did not load")
                        },
                    )
                }
                // A download that finished while nobody was watching left the
                // files here but no vectors. The pass is worth starting only
                // when there is something left to read.
                if (ready && semantic.pending() > 0) embedPhotos()
            }
        }
        // The service owns the download; this only mirrors it, so leaving and
        // reopening the screen shows the same progress rather than a restarted
        // download.
        viewModelScope.launch {
            downloads.state.collect { download ->
                when (download) {
                    is DownloadState.Running -> _state.update { current ->
                        current.copy(
                            modelDownload = SemanticStatus.Downloading(
                                asset = download.asset,
                                done = download.done,
                                total = download.total,
                            )
                        )
                    }

                    is DownloadState.Finished -> {
                        val ready = semantic.ready()
                        _state.update {
                            it.copy(
                                modelDownload = if (ready) {
                                    null
                                } else {
                                    SemanticStatus.Failed(
                                        semantic.loadFailure ?: "the model did not load"
                                    )
                                },
                                modelInstalled = models.ready(),
                                modelReady = ready,
                                modelBytes = semantic.sizeOnDisk(),
                            )
                        }
                        if (ready) {
                            _messages.send(UiMessage.Info("Model ready. Reading the photos it can search."))
                            embedPhotos()
                        }
                    }

                    DownloadState.Idle -> Unit

                    is DownloadState.Failed -> _state.update {
                        it.copy(modelDownload = SemanticStatus.Failed(download.reason))
                    }
                }
            }
        }
        showRecent()
    }

    /**
     * Starts the download, and lets go of it.
     *
     * The work belongs to a foreground service from here on: this screen can be
     * left, the app can be closed, and the file keeps arriving — and when the
     * screen comes back it reads the progress back off disk rather than
     * pretending the download is wherever it left it.
     */
    fun downloadModel() {
        ModelDownloadService.start(context)
    }

    /** Stops it, keeping what has arrived so the next start resumes. */
    fun cancelDownload() {
        viewModelScope.launch {
            downloads.cancel()
            ModelDownloadService.stop(context)
            val state = downloads.state.value
            _state.update {
                it.copy(
                    modelDownload = if (state is DownloadState.Failed) SemanticStatus.Failed(state.reason) else null,
                    modelInstalled = models.ready(),
                    modelBytes = semantic.sizeOnDisk(),
                )
            }
        }
    }

    /** Re-reads the model already on disk, without downloading it again. */
    fun retryLoad() {
        viewModelScope.launch {
            _state.update { it.copy(modelDownload = SemanticStatus.Loading) }
            semantic.close()
            val ready = semantic.ready()
            _state.update {
                it.copy(
                    modelReady = ready,
                    modelInstalled = models.ready(),
                    modelBytes = semantic.sizeOnDisk(),
                    modelDownload = if (ready) {
                        null
                    } else {
                        SemanticStatus.Failed(semantic.loadFailure ?: "the model did not load")
                    },
                )
            }
            if (ready) {
                _messages.send(UiMessage.Info("Model ready. Reading the photos it can search."))
                embedPhotos()
            }
        }
    }

    fun deleteModel() {
        viewModelScope.launch {
            PhotoWorkService.stop(context)
            semantic.close()
            runCatching { semantic.clear() }
            models.delete()
            _state.update {
                it.copy(
                    modelInstalled = false,
                    modelReady = false,
                    modelBytes = 0L,
                    modelDownload = null,
                    embedded = 0,
                    semantic = emptyList(),
                )
            }
            _messages.send(UiMessage.Info("Model removed"))
        }
    }

    /**
     * Reads the photos the model can then search by meaning — in the service,
     * so the pass survives leaving the screen and closing the app.
     */
    fun embedPhotos() {
        PhotoWorkService.startEmbedding(context)
    }

    fun stopEmbedding() {
        PhotoWorkService.stop(context)
        work.reset()
        _state.update { it.copy(embedding = false, progressName = "") }
    }

    fun setQuery(query: String) {
        _state.update {
            it.copy(query = query, searching = query.isNotBlank(), results = emptyList(),
                semantic = emptyList(), queryCurated = false)
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            val needle = _state.value.query.trim()
            if (needle.isEmpty()) {
                _state.update { it.copy(semantic = emptyList()) }
                showRecent()
                return@launch
            }
            _state.update { it.copy(searching = true) }
            val hits = try {
                indexer.search(needle)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _messages.send(UiMessage.Info(error.message ?: "Photo text search failed"))
                emptyList()
            }
            coroutineContext.ensureActive()
            _state.update { if (it.query.trim() == needle) it.copy(results = hits) else it }
            // The third mechanism, and the only one that can answer a phrase
            // which is not written anywhere in the photo and is not one of the
            // model's categories. It costs a model download, so it only runs
            // when that download is present.
            val byMeaning = if (_state.value.modelReady) {
                try {
                    semantic.search(needle)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _messages.send(UiMessage.Info(error.message ?: "Photo meaning search failed"))
                    emptyList()
                }
            } else {
                emptyList()
            }
            coroutineContext.ensureActive()
            _state.update {
                if (it.query.trim() != needle) it else it.copy(
                    searching = false,
                    results = hits,
                    semantic = byMeaning,
                    queryCurated = byMeaning.isNotEmpty() && semantic.isCurated(needle),
                )
            }
        }
    }

    private fun showRecent() {
        viewModelScope.launch {
            val recent = runCatching { indexer.recent() }.getOrDefault(emptyList())
            _state.update {
                if (it.query.isNotBlank()) it else it.copy(results = recent, searching = false)
            }
        }
    }

    /**
     * Reads the photos that are new or changed.
     *
     * The first run is the one that matters: it is when the models are fetched
     * from Play Services and every picture is opened for the first time — so it
     * runs in the service like everything else here, and keeps going whether or
     * not anyone is watching.
     */
    fun buildIndex() {
        PhotoWorkService.startIndexing(context)
    }

    fun stopIndexing() {
        PhotoWorkService.stop(context)
        work.reset()
        _state.update { it.copy(indexing = false, progressName = "") }
    }
}

/**
 * Finding a photo by what is in it.
 *
 * Two mechanisms, and the screen says which one found what, because they do not
 * deserve equal trust. The text a photo contains is read exactly — a transfer
 * receipt says "transfer" on it, and searching for that finds it and nothing
 * else. The labels are the model's categories, so "kerja bakti" reaches photos
 * of teams, crowds and construction, which will include the office outing. Both
 * are useful; only one is exact, and a search that pretended otherwise would be
 * the kind of feature that gets used once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSearchScreen(
    onBack: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    viewModel: PhotoSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf("all") }
    var showModel by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteModel by remember { mutableStateOf(false) }
    val exact = state.results.filter { it.match != PhotoMatch.THEME }
    val themed = state.results.filter { it.match == PhotoMatch.THEME }
    val exactPaths = exact.map { it.path }.toSet()
    val meaning = state.semantic.filterNot { it.path in exactPaths }
    val strongerPaths = exactPaths + meaning.map { it.path }
    val themeOnly = themed.filterNot { it.path in strongerPaths }
    LaunchedEffect(state.query) {
        filter = if (ThemeVocabulary.labelsFor(state.query).isNotEmpty()) "theme" else "all"
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbar.showMessage(context, message) { } }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.photo_search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (state.indexed > 0 && !state.indexing) {
                        IconButton(onClick = { viewModel.buildIndex() }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.photo_index_update),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SearchField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = stringResource(R.string.photo_search_hint),
                trailing = {
                    if (state.query.isNotBlank()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.photo_clear_query))
                        }
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    R.string.photo_example_kerja to Icons.Filled.Groups,
                    R.string.photo_example_food to Icons.Filled.Restaurant,
                    R.string.photo_example_transfer to Icons.AutoMirrored.Filled.ReceiptLong,
                    R.string.photo_example_nature to Icons.Filled.Landscape,
                    R.string.photo_example_pets to Icons.Filled.Pets,
                ).forEach { (label, icon) ->
                    val text = stringResource(label)
                    FilterChip(
                        selected = state.query.equals(text, ignoreCase = true),
                        onClick = { viewModel.setQuery(text) },
                        label = { Text(text, maxLines = 1) },
                        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
                    )
                }
            }
            if (!state.showingRecent) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "all" to R.string.photo_filter_all,
                        "meaning" to R.string.photo_section_meaning,
                        "theme" to R.string.photo_section_theme,
                        "text" to R.string.photo_section_text,
                    ).forEach { (key, label) ->
                        FilterChip(selected = filter == key, onClick = { filter = key },
                            label = { Text(stringResource(label)) })
                    }
                }
            }
            if (state.searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            // Status, setup and results share one scroll surface. Large fonts,
            // a keyboard or a model error must never squeeze the gallery out.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.showingRecent) {
                    item(key = "intro", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.photo_discover_title), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.photo_discover_desc), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item(key = "coverage", span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (state.knownTotal > state.indexed) stringResource(R.string.photo_search_indexed_of, state.indexed, state.knownTotal)
                                else stringResource(R.string.photo_search_indexed, state.indexed),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                if (state.modelReady) stringResource(R.string.photo_meaning_coverage, state.embedded, maxOf(state.indexed, state.knownTotal))
                                else stringResource(R.string.photo_meaning_optional),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showModel = !showModel }) {
                            Text(stringResource(if (showModel) R.string.photo_model_hide else R.string.photo_model_manage))
                        }
                    }
                }
                if (showModel || state.modelDownload is SemanticStatus.Downloading ||
                    state.modelDownload is SemanticStatus.Failed || state.modelDownload is SemanticStatus.Loading) {
                    item(key = "model", span = { GridItemSpan(maxLineSpan) }) {
                        ModelCard(state, viewModel::downloadModel, viewModel::cancelDownload,
                            { confirmDeleteModel = true }, viewModel::retryLoad)
                    }
                }
                if (state.indexing || state.embedding) {
                    item(key = "progress", span = { GridItemSpan(maxLineSpan) }) {
                        ProgressCard(state) {
                            if (state.embedding) viewModel.stopEmbedding() else viewModel.stopIndexing()
                        }
                    }
                }
                if (state.indexed == 0 && !state.indexing && !state.embedding) {
                    item(key = "setup", span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            icon = Icons.Filled.Image,
                            title = stringResource(R.string.photo_search_no_index),
                            body = stringResource(R.string.photo_search_no_index_hint),
                            action = {
                                Button(onClick = viewModel::buildIndex) { Text(stringResource(R.string.photo_index_build)) }
                            },
                        )
                    }
                } else if (!state.searching || state.results.isNotEmpty()) {
                    item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (state.showingRecent) stringResource(R.string.photo_section_recent)
                            else stringResource(R.string.photo_search_found, state.foundCount),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    val visibleExact = if (state.showingRecent || filter == "all" || filter == "text") exact else emptyList()
                    val visibleMeaning = if (filter == "meaning") state.semantic else if (filter == "all") meaning else emptyList()
                    val visibleTheme = if (filter == "theme") themed else if (filter == "all") themeOnly else emptyList()
                    if (visibleExact.isEmpty() && visibleMeaning.isEmpty() && visibleTheme.isEmpty() && !state.searching) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(Icons.Filled.Search,
                                stringResource(R.string.photo_search_empty),
                                body = stringResource(R.string.photo_search_empty_hint))
                        }
                    }
                    if (visibleExact.isNotEmpty() && !state.showingRecent) {
                        item(key = "text-heading", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(stringResource(R.string.photo_section_text))
                        }
                    }
                    items(visibleExact, key = { "text-${it.path}" }) { hit ->
                        PhotoCard(hit.name, hit.path, hit.modified,
                            if (state.showingRecent) hit.labels.joinToString(" · ")
                            else if (hit.match == PhotoMatch.NAME) stringResource(R.string.photo_match_name)
                            else hit.snippet.orEmpty(),
                            MaterialTheme.colorScheme.primary, { onOpenPhoto(hit.path) })
                    }
                    if (visibleMeaning.isNotEmpty()) {
                        item(key = "meaning-heading", span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SectionHeader(stringResource(R.string.photo_section_meaning))
                                Text(stringResource(R.string.photo_meaning_note), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(visibleMeaning, key = { "meaning-${it.path}" }) { hit ->
                        PhotoCard(hit.name, hit.path, hit.modified, stringResource(R.string.photo_match_visual),
                            MaterialTheme.colorScheme.tertiary, { onOpenPhoto(hit.path) })
                    }
                    if (visibleTheme.isNotEmpty()) {
                        item(key = "theme-heading", span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SectionHeader(stringResource(R.string.photo_section_theme))
                                Text(stringResource(R.string.photo_theme_note), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(visibleTheme, key = { "theme-${it.path}" }) { hit ->
                        PhotoCard(hit.name, hit.path, hit.modified, hit.labels.joinToString(" · "),
                            MaterialTheme.colorScheme.onSurfaceVariant, { onOpenPhoto(hit.path) })
                    }
                }
            }
        }
    }
    if (confirmDeleteModel) {
        ConfirmDialog(
            title = stringResource(R.string.photo_model_delete),
            body = stringResource(R.string.photo_model_delete_confirm),
            confirmLabel = stringResource(R.string.photo_model_delete),
            onConfirm = { viewModel.deleteModel(); confirmDeleteModel = false },
            onDismiss = { confirmDeleteModel = false },
            destructive = true,
        )
    }
}

/**
 * The model, and what it costs.
 *
 * It is a deliberate half gigabyte, so it is offered rather than taken: the
 * card names the size before anything starts, says which file is arriving while
 * it arrives, and can be deleted afterwards — deleting it also drops the
 * vectors, because vectors from a model that is no longer here mean nothing.
 */
@Composable
private fun ModelCard(
    state: PhotoSearchState,
    onDownload: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    val downloading = state.modelDownload as? SemanticStatus.Downloading
    val failed = state.modelDownload as? SemanticStatus.Failed
    val loading = state.modelDownload is SemanticStatus.Loading

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(16.dp),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.photo_model_title),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (state.modelReady) {
            Text(
                text = stringResource(
                    R.string.photo_model_ready,
                    state.embedded,
                    Formatters.bytes(state.modelBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onDelete() }, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.photo_model_delete))
                }
            }
        } else if (downloading != null) {
            Text(
                text = stringResource(
                    R.string.photo_model_downloading,
                    downloading.asset,
                    Formatters.bytes(downloading.done),
                    Formatters.bytes(downloading.total),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = {
                    if (downloading.total > 0) {
                        (downloading.done.toFloat() / downloading.total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.photo_model_keep_going),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onStop, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.photo_model_stop))
            }
        } else if (loading) {
            // Half a gigabyte does not open instantly. "Could not open" while
            // it is still opening is a claim the card used to make.
            Text(
                text = stringResource(R.string.photo_model_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (state.modelInstalled) {
            // The files are here but the model would not load. Downloading 795 MB
            // again would be the wrong answer to a wrong assumption in the code.
            Text(
                text = failed?.reason ?: stringResource(R.string.photo_model_not_loaded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.photo_model_retry_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry, shape = RoundedCornerShape(14.dp)) {
                    Text(stringResource(R.string.photo_model_retry))
                }
                OutlinedButton(onClick = onDelete, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.photo_model_delete))
                }
            }
        } else {
            Text(
                text = stringResource(R.string.photo_model_offer, state.modelTotalMb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (failed != null) {
                Text(
                    text = failed.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onDownload, shape = RoundedCornerShape(14.dp)) {
                Text(stringResource(R.string.photo_model_download, state.modelTotalMb))
            }
        }
    }
}

@Composable
private fun ProgressCard(state: PhotoSearchState, onStop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            // The pass says its own name: "reading photos" and "reading photos
            // by meaning" are different jobs, and a card that says the wrong one
            // is a card nobody trusts.
            text = stringResource(
                if (state.embedding) R.string.photo_embed_title else R.string.photo_indexing,
            ),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (state.progressTotal > 0) {
                "${state.progressDone}/${state.progressTotal} · ${state.progressName}"
            } else {
                stringResource(R.string.photo_indexing_scan)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.photo_indexing_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onStop, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.op_cancel))
            }
        }
    }
}

@Composable
private fun PhotoCard(
    name: String,
    path: String,
    modified: Long,
    note: String,
    noteColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Why this photo is here: the line of text that matched, the themes
            // it matched, or how close it is in meaning. A result that cannot
            // say why is a result the user has to open to believe.
            if (note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = noteColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = Formatters.relative(modified),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
