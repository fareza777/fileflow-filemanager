package com.fileflow.app.ui.compress

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.fileflow.app.core.storage.ArchiveFormat
import com.fileflow.app.core.storage.CompressionLevel
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationProgress
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.Paths
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileRow
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.LoadingState
import com.fileflow.app.ui.components.SectionHeader
import com.fileflow.app.ui.components.folderSubtitle
import com.fileflow.app.domain.toFileItem
import com.fileflow.app.ui.util.SelectionHandoff
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.compose.runtime.Immutable
data class CompressState(
    val folder: String = "",
    val items: List<FileItem> = emptyList(),
    val selection: Set<String> = emptySet(),
    val loading: Boolean = true,
    val format: ArchiveFormat = ArchiveFormat.ZIP,
    val level: CompressionLevel = CompressionLevel.NORMAL,
    val name: String = "",
    val working: Boolean = false,
) {
    val selectedItems: List<FileItem> get() = items.filter { it.path in selection }
    val selectedBytes: Long get() = selectedItems.sumOf { it.sizeBytes }
}

@HiltViewModel
class CompressViewModel @Inject constructor(
    private val browseRepository: BrowseRepository,
    private val actions: FileActionsRepository,
    private val operationRunner: OperationRunner,
    private val handoff: SelectionHandoff,
) : ViewModel() {

    private val _state = MutableStateFlow(CompressState())
    val state: StateFlow<CompressState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            // A selection handed over from another screen arrives already made:
            // opening a folder and asking the user to pick it all again would be
            // a step backwards in the middle of a bulk action.
            val handed = withContext(Dispatchers.IO) {
                handoff.take().mapNotNull { browseRepository.toCandidate(it)?.toFileItem() }
            }
            if (handed.isNotEmpty()) {
                _state.update {
                    it.copy(
                        folder = handed.first().path.substringBeforeLast('/'),
                        items = handed,
                        selection = handed.map { item -> item.path }.toSet(),
                        loading = false,
                    )
                }
            } else {
                openFolder(browseRepository.primaryRoot())
            }
        }
    }

    fun openFolder(path: String) {
        _state.update { it.copy(folder = path, loading = true, selection = emptySet()) }
        viewModelScope.launch {
            val items = browseRepository.listItems(path, includeHidden = false, sort = SortOrder())
            _state.update { it.copy(items = items, loading = false) }
        }
    }

    fun toggle(path: String) {
        _state.update { current ->
            val selection = if (path in current.selection) current.selection - path
            else current.selection + path
            current.copy(selection = selection)
        }
    }

    fun selectAll() {
        _state.update { it.copy(selection = it.items.map { item -> item.path }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    fun setName(name: String) {
        _state.update { it.copy(name = name) }
    }

    fun setFormat(format: ArchiveFormat) {
        // A tar holds files exactly as they are; leaving "Maximum" selected
        // would promise a level that format cannot deliver.
        _state.update {
            it.copy(
                format = format,
                level = if (format == ArchiveFormat.TAR) CompressionLevel.STORE else it.level,
            )
        }
    }

    fun setLevel(level: CompressionLevel) {
        _state.update { it.copy(level = level) }
    }

    /**
     * The name is offered, not demanded: something sensible is derived from the
     * selection, and the field only exists for the times the user cares.
     */
    fun suggestedName(): String {
        val current = _state.value
        if (current.name.isNotBlank()) return current.name.trim()
        val selected = current.selectedItems
        return when {
            selected.size == 1 -> selected.first().name.substringBeforeLast('.')
            else -> Paths.nameOf(current.folder).ifEmpty { "archive" }
        }
    }

    fun compress() {
        val current = _state.value
        val paths = current.selectedItems.map { it.path }
        if (paths.isEmpty() || current.working) return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                var destination: String? = null
                operationRunner.run(OperationKind.ARCHIVE) { onProgress ->
                    val target = actions.suggestArchivePath(
                        directory = current.folder,
                        baseName = suggestedName(),
                        format = current.format,
                    )
                    val result = actions.compress(
                        sources = paths,
                        destination = target,
                        format = current.format,
                        level = current.level,
                    ) { progress ->
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
                    result.getOrElse { throw it }
                    destination = target
                }
                _state.update { it.copy(working = false, selection = emptySet(), name = "") }
                _messages.send(
                    UiMessage.Info("Created ${Paths.nameOf(destination.orEmpty())}")
                )
                openFolder(current.folder)
            } catch (_: CancellationException) {
                _state.update { it.copy(working = false) }
            } catch (error: Throwable) {
                _state.update { it.copy(working = false) }
                _messages.send(UiMessage.Info(error.message ?: "Could not compress"))
            }
        }
    }
}

/**
 * Compress, with the choices a compression tool is expected to have.
 *
 * The browser's "compress" command stays what it was — one tap, zip beside the
 * selection — because that is the right answer most of the time. This screen is
 * for the other times: a different format, a different effort, or a name the
 * user actually wants to see later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressScreen(
    onBack: () -> Unit,
    viewModel: CompressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compress_title)) },
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
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.selection.isEmpty()) {
                                stringResource(R.string.compress_nothing)
                            } else {
                                pluralStringResource(
                                    R.plurals.item_count,
                                    state.selection.size,
                                    state.selection.size,
                                )
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = Formatters.bytes(state.selectedBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = viewModel::compress,
                        enabled = state.selection.isNotEmpty() && !state.working,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.Inventory2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.compress_action, state.selection.size)
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingState(label = stringResource(R.string.state_loading))

                state.items.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Inventory2,
                    title = stringResource(R.string.compress_empty),
                    body = stringResource(R.string.compress_empty_hint),
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
                        FolderBar(
                            folder = state.folder,
                            onChange = { showPicker = true },
                        )
                    }

                    items(state.items, key = { it.path }) { item ->
                        FileRow(
                            item = item,
                            thumbnail = true,
                            selectionMode = true,
                            selected = item.path in state.selection,
                            subtitle = if (item.isDirectory) folderSubtitle(item) else null,
                            onClick = { viewModel.toggle(item.path) },
                            onLongClick = { viewModel.toggle(item.path) },
                        )
                    }

                    item { SectionHeader(stringResource(R.string.compress_settings)) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = state.name,
                                onValueChange = viewModel::setName,
                                label = { Text(stringResource(R.string.compress_name)) },
                                placeholder = { Text(viewModel.suggestedName()) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.compress_format),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ArchiveFormat.entries.forEach { format ->
                                        FilterChip(
                                            selected = state.format == format,
                                            onClick = { viewModel.setFormat(format) },
                                            label = { Text(format.label) },
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.compress_format_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.compress_level),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CompressionLevel.entries.forEach { level ->
                                        FilterChip(
                                            selected = state.level == level,
                                            // A tar holds its files uncompressed, so
                                            // only "store" is true for it.
                                            enabled = state.format != ArchiveFormat.TAR ||
                                                level == CompressionLevel.STORE,
                                            onClick = { viewModel.setLevel(level) },
                                            label = { Text(levelLabel(level)) },
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
                text = folder,
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
private fun levelLabel(level: CompressionLevel): String = stringResource(
    when (level) {
        CompressionLevel.STORE -> R.string.level_store
        CompressionLevel.FAST -> R.string.level_fast
        CompressionLevel.NORMAL -> R.string.level_normal
        CompressionLevel.MAXIMUM -> R.string.level_maximum
    }
)
