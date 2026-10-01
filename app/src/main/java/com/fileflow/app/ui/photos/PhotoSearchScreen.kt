package com.fileflow.app.ui.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AssistChip
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
import com.fileflow.app.core.semantic.SemanticStatus
import com.fileflow.app.service.ModelDownloadService
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
    val modelTotalMb: Long get() = SemanticAsset.entries.sumOf { it.megabytes }
}

@HiltViewModel
class PhotoSearchViewModel @Inject constructor(
    private val indexer: PhotoIndexer,
    private val semantic: SemanticEngine,
    private val downloads: ModelDownloadManager,
    private val models: SemanticModels,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(PhotoSearchState())
    val state: StateFlow<PhotoSearchState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var searchJob: Job? = null
    private var indexJob: Job? = null
    private var embedJob: Job? = null

    init {
        viewModelScope.launch {
            indexer.observeCount().collect { count ->
                _state.update { it.copy(indexed = count, neverIndexed = count == 0) }
            }
        }
        viewModelScope.launch {
            semantic.observeCount().collect { count -> _state.update { it.copy(embedded = count) } }
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
                )
            }
            if (installed) {
                val ready = semantic.ready()
                _state.update {
                    it.copy(
                        modelReady = ready,
                        modelDownload = if (ready) null else SemanticStatus.Failed("the model did not load"),
                    )
                }
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
                                modelDownload = if (ready) null else SemanticStatus.Failed("the model did not load"),
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

                    is DownloadState.Failed -> _state.update {
                        it.copy(modelDownload = SemanticStatus.Failed(download.reason))
                    }

                    DownloadState.Idle -> Unit
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

    fun deleteModel() {
        viewModelScope.launch {
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

    /** Reads the photos the model can then search by meaning. */
    fun embedPhotos(limit: Int = 500) {
        if (_state.value.embedding) return
        embedJob = viewModelScope.launch {
            _state.update { it.copy(embedding = true, progressDone = 0, progressTotal = 0) }
            val done = runCatching {
                semantic.embed(limit = limit) { count, total, name ->
                    _state.update {
                        it.copy(progressDone = count, progressTotal = total, progressName = name)
                    }
                }
            }.getOrNull() ?: 0
            _state.update { it.copy(embedding = false, progressName = "") }
            _messages.send(
                UiMessage.Info(if (done == 0) "No photo left to read" else "Read $done photos by meaning")
            )
            if (_state.value.query.isNotBlank()) setQuery(_state.value.query)
        }
    }

    fun stopEmbedding() {
        embedJob?.cancel()
        _state.update { it.copy(embedding = false, progressName = "") }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
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
            val hits = runCatching { indexer.search(needle) }.getOrDefault(emptyList())
            // The third mechanism, and the only one that can answer a phrase
            // which is not written anywhere in the photo and is not one of the
            // model's categories. It costs a model download, so it only runs
            // when that download is present.
            val byMeaning = if (_state.value.modelReady) {
                runCatching { semantic.search(needle) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            _state.update {
                it.copy(
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
            _state.update { it.copy(results = recent) }
        }
    }

    /**
     * Reads the photos that are new or changed.
     *
     * The first run is the one that matters: it is when the models are fetched
     * from Play Services and every picture is opened for the first time, so it
     * is also the run that has to be visible and interruptible rather than a
     * background surprise.
     */
    fun buildIndex(limit: Int = 800) {
        if (_state.value.indexing) return
        indexJob = viewModelScope.launch {
            _state.update { it.copy(indexing = true, progressDone = 0, progressTotal = 0, progressName = "") }
            val counted = runCatching {
                indexer.index(limit = limit) { done, total, name ->
                    _state.update {
                        it.copy(progressDone = done, progressTotal = total, progressName = name)
                    }
                }
            }.getOrElse { error ->
                _state.update { it.copy(indexing = false) }
                _messages.send(UiMessage.Info(error.message ?: "Could not read the photos"))
                return@launch
            }
            _state.update { it.copy(indexing = false, progressName = "") }
            _messages.send(
                UiMessage.Info(
                    if (counted == 0) "Nothing new to read" else "Read $counted photos"
                )
            )
            if (_state.value.query.isBlank()) showRecent() else setQuery(_state.value.query)
        }
    }

    fun stopIndexing() {
        indexJob?.cancel()
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SearchField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = stringResource(R.string.photo_search_hint),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The examples are the feature: nobody guesses that receipts
                // are searchable, and "kerjabakti" is not a word anyone types
                // into a file manager unless it is suggested.
                listOf(
                    R.string.photo_example_transfer,
                    R.string.photo_example_kerja,
                    R.string.photo_example_food,
                ).forEach { example ->
                    val text = stringResource(example)
                    AssistChip(
                        onClick = { viewModel.setQuery(text) },
                        label = { Text(text) },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
                if (state.query.isNotBlank()) {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_deselect_all))
                    }
                }
            }

            when {
                state.indexing || state.embedding -> ProgressCard(
                    state = state,
                    onStop = {
                        if (state.embedding) viewModel.stopEmbedding() else viewModel.stopIndexing()
                    },
                )

                state.indexed == 0 -> EmptyState(
                    icon = Icons.Filled.Image,
                    title = stringResource(R.string.photo_search_no_index),
                    body = stringResource(R.string.photo_search_no_index_hint),
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(
                                onClick = { viewModel.buildIndex() },
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(stringResource(R.string.photo_index_build))
                            }
                            Text(
                                text = stringResource(R.string.photo_search_model_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    },
                )

                else -> {
                    ModelCard(
                        state = state,
                        onDownload = viewModel::downloadModel,
                        onStop = viewModel::cancelDownload,
                        onDelete = viewModel::deleteModel,
                    )

                    if (state.results.isEmpty() && state.semantic.isEmpty() && !state.searching) {
                        EmptyState(
                            icon = Icons.Filled.Search,
                            title = stringResource(R.string.photo_search_empty),
                            body = stringResource(R.string.photo_search_empty_hint),
                        )
                    } else {
                        Text(
                            text = if (state.showingRecent) {
                                stringResource(R.string.photo_search_indexed, state.indexed)
                            } else {
                                stringResource(R.string.photo_search_found, state.results.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 150.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Meaning first when there is any: it is the tier the
                            // user paid hundreds of megabytes for, and the one
                            // that can answer a phrase nothing is printed on.
                            if (state.semantic.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Column {
                                        SectionHeader(stringResource(R.string.photo_section_meaning))
                                        // Why an Indonesian phrase works at all:
                                        // the model reads English, so the query is
                                        // translated into prompts before it is
                                        // embedded. Saying so turns a mystery into
                                        // a mechanism.
                                        if (state.queryCurated) {
                                            Text(
                                                text = stringResource(R.string.photo_prompt_translated),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 4.dp),
                                            )
                                        }
                                    }
                                }
                                items(state.semantic, key = { "s-${it.path}" }) { hit ->
                                    PhotoCard(
                                        name = hit.name,
                                        path = hit.path,
                                        modified = hit.modified,
                                        note = "≈ ${(hit.score * 100).toInt()}% " +
                                            stringResource(R.string.photo_match_meaning),
                                        noteColor = MaterialTheme.colorScheme.tertiary,
                                        onClick = { onOpenPhoto(hit.path) },
                                    )
                                }
                            }
                            if (state.results.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    SectionHeader(stringResource(R.string.photo_section_exact))
                                }
                                items(state.results, key = { it.path }) { hit ->
                                    PhotoCard(
                                        name = hit.name,
                                        path = hit.path,
                                        modified = hit.modified,
                                        note = hit.snippet
                                            ?: hit.themes.take(3).joinToString(" · ")
                                            .takeIf { it.isNotBlank() }
                                            ?: hit.labels.joinToString(" · "),
                                        noteColor = if (hit.snippet != null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        onClick = { onOpenPhoto(hit.path) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
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
) {
    val downloading = state.modelDownload as? SemanticStatus.Downloading
    val failed = state.modelDownload as? SemanticStatus.Failed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.photo_model_title),
            style = MaterialTheme.typography.titleSmall,
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
        } else {
            Text(
                text = stringResource(R.string.photo_model_offer, state.modelTotalMb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (failed != null) {
                Text(
                    text = failed.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.photo_indexing),
            style = MaterialTheme.typography.titleSmall,
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
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(10.dp),
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
