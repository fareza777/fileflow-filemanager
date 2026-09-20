package com.fileflow.app.ui.convert

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.model.SortOrder
import com.fileflow.app.core.storage.CollisionResolver
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.Paths
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.FileIndexRepository
import com.fileflow.app.domain.convert.ConvertOptions
import com.fileflow.app.domain.convert.ConvertTarget
import com.fileflow.app.domain.convert.FileConverter
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileRow
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.LoadingState
import com.fileflow.app.ui.components.SectionHeader
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class ConvertState(
    val folder: String = "",
    val items: List<FileItem> = emptyList(),
    val selection: Set<String> = emptySet(),
    val loading: Boolean = true,
    val target: ConvertTarget? = null,
    val quality: Int = 90,
    val maxDimension: Int? = null,
    val working: Boolean = false,
    val progress: Float? = null,
    val progressDetail: String? = null,
) {
    val selectedItems: List<FileItem> get() = items.filter { it.path in selection }

    /**
     * Only the targets every selected file can produce. Offering a format that
     * would fail on half the selection is offering a broken button.
     */
    val availableTargets: List<ConvertTarget>
        get() {
            val selected = selectedItems
            if (selected.isEmpty()) return emptyList()
            return selected
                .map { ConvertTarget.availableFor(it.extension, it.category) }
                .reduceOrNull { common, next -> common.filter { it in next } }
                .orEmpty()
        }
}

@HiltViewModel
class ConvertViewModel @Inject constructor(
    private val browseRepository: BrowseRepository,
    private val converter: FileConverter,
    private val indexRepository: FileIndexRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ConvertState())
    val state: StateFlow<ConvertState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var convertJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch { openFolder(browseRepository.primaryRoot()) }
    }

    fun openFolder(path: String) {
        _state.update { it.copy(folder = path, loading = true, selection = emptySet(), target = null) }
        viewModelScope.launch {
            val items = browseRepository.listItems(path, includeHidden = false, sort = SortOrder())
            _state.update { it.copy(items = items.filter { !it.isDirectory }, loading = false) }
        }
    }

    fun toggle(path: String) {
        _state.update { current ->
            val selection = if (path in current.selection) current.selection - path
            else current.selection + path
            current.copy(selection = selection).withValidTarget()
        }
    }

    fun selectAll() {
        _state.update { current ->
            // Selecting a file that cannot be converted would empty the format
            // row and leave a button that can never be pressed.
            val convertible = current.items.filter {
                ConvertTarget.availableFor(it.extension, it.category).isNotEmpty()
            }
            current.copy(selection = convertible.map { it.path }.toSet()).withValidTarget()
        }
    }

    /**
     * The chosen format may not apply to the new selection, so it is dropped
     * rather than left selected and quietly wrong.
     */
    private fun ConvertState.withValidTarget(): ConvertState {
        val valid = availableTargets
        return copy(target = target?.takeIf { it in valid } ?: valid.firstOrNull())
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet(), target = null) }
    }

    fun setTarget(target: ConvertTarget) {
        _state.update { it.copy(target = target) }
    }

    fun setQuality(value: Int) {
        _state.update { it.copy(quality = value.coerceIn(10, 100)) }
    }

    fun setMaxDimension(value: Int?) {
        _state.update { it.copy(maxDimension = value) }
    }

    fun convert() {
        val current = _state.value
        val target = current.target ?: return
        val items = current.selectedItems
        if (items.isEmpty() || current.working) return

        convertJob?.cancel()
        convertJob = viewModelScope.launch {
            _state.update { it.copy(working = true, progress = null) }
            var done = 0
            var failed = 0
            var lastPath: String? = null
            try {
                items.forEachIndexed { index, item ->
                    _state.update {
                        it.copy(
                            progress = index.toFloat() / items.size,
                            progressDetail = item.name,
                        )
                    }
                    val source = File(item.path)
                    val base = source.name.substringBeforeLast('.')
                    val destinationName = CollisionResolver.unique(
                        File(current.folder),
                        "$base.${target.extension}",
                    )
                    val destination = File(current.folder, destinationName)
                    val result = converter.convert(
                        source = source,
                        target = target,
                        destination = destination,
                        options = ConvertOptions(
                            quality = current.quality,
                            maxDimension = current.maxDimension,
                        ),
                    ) { fraction, detail ->
                        _state.update {
                            it.copy(
                                progress = (index + fraction) / items.size,
                                progressDetail = detail,
                            )
                        }
                    }
                    result.fold(
                        onSuccess = {
                            done++
                            lastPath = it.path
                        },
                        onFailure = { failed++ },
                    )
                }
                lastPath?.let { indexRepository.upsertPaths(listOf(it)) }
                _state.update { it.copy(working = false, progress = null, selection = emptySet()) }
                _messages.send(
                    UiMessage.Info(
                        when {
                            failed == 0 -> "$done converted"
                            done == 0 -> "Conversion failed"
                            else -> "$done converted, $failed failed"
                        }
                    )
                )
                if (done > 0) openFolder(current.folder)
            } catch (_: CancellationException) {
                _state.update { it.copy(working = false, progress = null) }
            } catch (error: Throwable) {
                _state.update { it.copy(working = false, progress = null) }
                _messages.send(UiMessage.Info(error.message ?: "Conversion failed"))
            }
        }
    }

    fun cancel() {
        convertJob?.cancel()
        _state.update { it.copy(working = false, progress = null) }
    }
}

/**
 * Convert, with only the conversions that can actually be produced.
 *
 * The list of formats is filtered per selection — an image offers PNG, JPEG,
 * WEBP and PDF, a video offers its audio track, a text file offers a PDF — and
 * a format that would fail is never offered at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(
    onBack: () -> Unit,
    viewModel: ConvertViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showPicker by remember { mutableStateOf(false) }
    val targets = state.availableTargets

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.convert_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (state.selection.isNotEmpty()) {
                        IconButton(onClick = viewModel::selectAll) {
                            Icon(
                                imageVector = Icons.Filled.SelectAll,
                                contentDescription = stringResource(R.string.cd_select_all),
                            )
                        }
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(
                                imageVector = Icons.Filled.Deselect,
                                contentDescription = stringResource(R.string.action_deselect_all),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    if (state.working) {
                        LinearProgressIndicator(
                            progress = { (state.progress ?: 0f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    state.working -> state.progressDetail
                                        ?: stringResource(R.string.convert_working)
                                    state.selection.isEmpty() -> stringResource(R.string.convert_nothing)
                                    else -> pluralStringResource(
                                        R.plurals.item_count,
                                        state.selection.size,
                                        state.selection.size,
                                    )
                                },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = state.target?.let { target ->
                                    stringResource(R.string.convert_to, target.labelFor())
                                } ?: Formatters.bytes(state.selectedItems.sumOf { it.sizeBytes }),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.working) {
                            OutlinedButton(
                                onClick = viewModel::cancel,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(stringResource(R.string.op_cancel))
                            }
                        } else {
                            Button(
                                onClick = viewModel::convert,
                                enabled = state.selection.isNotEmpty() && state.target != null,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SwapHoriz,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.convert_action))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingState(label = stringResource(R.string.state_loading))

                state.items.isEmpty() -> EmptyState(
                    icon = Icons.Filled.SwapHoriz,
                    title = stringResource(R.string.convert_empty),
                    body = stringResource(R.string.convert_empty_hint),
                    action = {
                        TextButton(onClick = { showPicker = true }) {
                            Text(stringResource(R.string.compress_change_folder))
                        }
                    },
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        FolderBar(folder = state.folder, onChange = { showPicker = true })
                    }

                    if (state.selection.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.convert_output)) }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    targets.forEach { target ->
                                        FilterChip(
                                            selected = state.target == target,
                                            onClick = { viewModel.setTarget(target) },
                                            label = { Text(target.labelFor()) },
                                        )
                                    }
                                }
                                if (targets.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.convert_no_target),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                val lossy = state.target == ConvertTarget.JPEG ||
                                    state.target == ConvertTarget.WEBP
                                if (lossy) {
                                    Column {
                                        Text(
                                            text = stringResource(R.string.convert_quality, state.quality),
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        Slider(
                                            value = state.quality.toFloat(),
                                            onValueChange = { viewModel.setQuality(it.toInt()) },
                                            valueRange = 20f..100f,
                                            steps = 15,
                                        )
                                    }
                                }

                                val scalable = state.target == ConvertTarget.JPEG ||
                                    state.target == ConvertTarget.PNG ||
                                    state.target == ConvertTarget.WEBP ||
                                    state.target == ConvertTarget.PDF
                                if (scalable) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = stringResource(R.string.convert_size),
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            SIZE_OPTIONS.forEach { option ->
                                                FilterChip(
                                                    selected = state.maxDimension == option,
                                                    onClick = { viewModel.setMaxDimension(option) },
                                                    label = {
                                                        Text(
                                                            option?.let { "$it px" }
                                                                ?: stringResource(R.string.convert_size_original)
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    items(state.items, key = { it.path }) { item ->
                        val canConvert = ConvertTarget.availableFor(item.extension, item.category).isNotEmpty()
                        FileRow(
                            item = item,
                            thumbnail = true,
                            selectionMode = true,
                            selected = item.path in state.selection,
                            subtitle = if (canConvert) {
                                null
                            } else {
                                stringResource(R.string.convert_unsupported)
                            },
                            onClick = { if (canConvert) viewModel.toggle(item.path) },
                            onLongClick = { if (canConvert) viewModel.toggle(item.path) },
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        FolderPickerSheet(
            startPath = state.folder,
            title = stringResource(R.string.compress_pick_folder),
            onDismiss = { showPicker = false },
            onPick = { path ->
                viewModel.openFolder(path)
                showPicker = false
            },
        )
    }
}

@Composable
private fun FolderBar(folder: String, onChange: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = Paths.display(folder),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChange) {
                Text(stringResource(R.string.compress_change_folder))
            }
        }
    }
}

@Composable
private fun ConvertTarget.labelFor(): String = when (this) {
    ConvertTarget.PNG -> "PNG"
    ConvertTarget.JPEG -> "JPEG"
    ConvertTarget.WEBP -> "WEBP"
    ConvertTarget.PDF -> "PDF"
    ConvertTarget.AUDIO -> stringResource(R.string.convert_audio_only)
}

private val SIZE_OPTIONS = listOf(null, 640, 1080, 1600, 2048)
