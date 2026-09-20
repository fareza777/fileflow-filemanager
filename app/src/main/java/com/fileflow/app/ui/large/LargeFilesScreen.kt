package com.fileflow.app.ui.large

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.fileflow.app.ui.util.LocalUserSettings
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationReport
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.prefs.SettingsRepository
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.scan.InsightsScanner
import com.fileflow.app.domain.toFileItem
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileRow
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.ProgressPanel
import com.fileflow.app.ui.components.SelectionAction
import com.fileflow.app.ui.components.SelectionActionBar
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage
import dagger.hilt.android.lifecycle.HiltViewModel
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

data class LargeState(
    val scanning: Boolean = true,
    val scanned: Int = 0,
    val files: List<Candidate> = emptyList(),
    val selection: Set<String> = emptySet(),
    val threshold: Long = 100L * 1024 * 1024,
    val working: Boolean = false,
)

@HiltViewModel
class LargeFilesViewModel @Inject constructor(
    private val scanner: InsightsScanner,
    private val actions: FileActionsRepository,
    private val settings: SettingsRepository,
    private val operationRunner: OperationRunner,
) : ViewModel() {

    private val _state = MutableStateFlow(LargeState())
    val state: StateFlow<LargeState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            settings.settings.collect { prefs ->
                _state.update { it.copy(threshold = prefs.largeFileThreshold) }
            }
        }
        scan()
    }

    fun setThreshold(bytes: Long) {
        _state.update { it.copy(threshold = bytes) }
        viewModelScope.launch { settings.setLargeFileThreshold(bytes) }
        scan()
    }

    fun scan() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(scanning = true, scanned = 0, selection = emptySet()) }
            try {
                val result = scanner.scan(
                    largeThreshold = _state.value.threshold,
                    onProgress = { seen -> _state.update { it.copy(scanned = seen) } },
                )
                _state.update { it.copy(scanning = false, files = result.largeFiles) }
            } catch (_: CancellationException) {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(scanning = false) }
    }

    fun toggle(path: String) {
        _state.update { current ->
            val selection = if (path in current.selection) current.selection - path
            else current.selection + path
            current.copy(selection = selection)
        }
    }

    fun selectAll() {
        _state.update { it.copy(selection = it.files.map { f -> f.path }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    fun deleteSelected(permanent: Boolean) {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                var report: OperationReport? = null
                operationRunner.run(
                    if (permanent) OperationKind.DELETE else OperationKind.TRASH
                ) { progress ->
                    report = actions.delete(paths, permanent, progress)
                }
                _state.update {
                    it.copy(
                        working = false,
                        files = it.files.filterNot { f -> f.path in paths },
                        selection = emptySet(),
                    )
                }
                val done = report
                if (done != null) {
                    _messages.send(
                        UiMessage.Done(
                            "${done.succeeded} files removed",
                            done.groupId.takeIf { !permanent && done.succeeded > 0 },
                        )
                    )
                }
            } catch (_: CancellationException) {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun moveSelectedTo(destination: String) {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                var report: OperationReport? = null
                operationRunner.run(OperationKind.MOVE) { progress ->
                    report = actions.move(paths, destination, ConflictPolicy.RENAME, progress)
                }
                _state.update {
                    it.copy(
                        working = false,
                        files = it.files.filterNot { f -> f.path in paths },
                        selection = emptySet(),
                    )
                }
                val done = report
                if (done != null) {
                    _messages.send(
                        UiMessage.Done(
                            "${done.succeeded} files moved",
                            done.groupId.takeIf { done.succeeded > 0 },
                        )
                    )
                }
            } catch (_: CancellationException) {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun undo(groupId: String) {
        viewModelScope.launch {
            val result = actions.undoGroup(groupId)
            _messages.send(UiMessage.Info("${result.restored} changes reverted"))
            scan()
        }
    }

    fun selectedBytes(): Long =
        _state.value.files.filter { it.path in _state.value.selection }.sumOf { it.sizeBytes }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeFilesScreen(
    onBack: () -> Unit,
    viewModel: LargeFilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val settings = LocalUserSettings.current
    val trashDays = settings.trashRetentionDays
    val context = LocalContext.current
    var showDelete by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { group -> viewModel.undo(group) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.large_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::scan) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                    }
                },
            )
        },
        bottomBar = {
            if (state.selection.isNotEmpty()) {
                SelectionActionBar(
                    title = pluralStringResource(
                        R.plurals.item_count,
                        state.selection.size,
                        state.selection.size,
                    ),
                    subtitle = Formatters.bytes(viewModel.selectedBytes()),
                    actions = listOf(
                        SelectionAction(
                            icon = Icons.Filled.Deselect,
                            label = stringResource(R.string.action_deselect_all),
                            onClick = viewModel::clearSelection,
                        ),
                        SelectionAction(
                            icon = Icons.AutoMirrored.Filled.DriveFileMove,
                            label = stringResource(R.string.action_move),
                            onClick = { showPicker = true },
                        ),
                        SelectionAction(
                            icon = Icons.Filled.Delete,
                            label = stringResource(R.string.action_delete),
                            onClick = {
                                if (settings.confirmDelete) showDelete = true
                                else viewModel.deleteSelected(permanent = !settings.useTrash)
                            },
                            destructive = true,
                        ),
                    ),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.scanning -> ProgressPanel(
                    title = stringResource(R.string.large_scanning),
                    detail = stringResource(R.string.analyzer_files, state.scanned),
                    fraction = null,
                    onCancel = viewModel::stop,
                    modifier = Modifier.padding(16.dp),
                )

                state.files.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Memory,
                    title = stringResource(
                        R.string.large_none,
                        Formatters.bytes(state.threshold),
                    ),
                    body = stringResource(R.string.large_none_hint),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        ThresholdChips(
                            current = state.threshold,
                            onSelect = viewModel::setThreshold,
                        )
                        Text(
                            text = stringResource(
                                R.string.large_total,
                                state.files.size,
                                Formatters.bytes(state.files.sumOf { it.sizeBytes }),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    items(state.files, key = { it.path }) { file ->
                        FileRow(
                            modifier = Modifier.animateItem(),
                            item = remember(file.path) { file.toFileItem() },
                            selectionMode = true,
                            selected = file.path in state.selection,
                            subtitle = "${Formatters.bytes(file.sizeBytes)} · ${
                                file.path.substringBeforeLast('/')
                            }",
                            onClick = { viewModel.toggle(file.path) },
                            onLongClick = { viewModel.toggle(file.path) },
                        )
                    }
                }
            }
        }
    }

    if (showDelete) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_title, state.selection.size, state.selection.size),
            body = stringResource(R.string.delete_body, trashDays),
            confirmLabel = stringResource(R.string.delete_move_to_trash),
            onConfirm = {
                viewModel.deleteSelected(permanent = !settings.useTrash)
                showDelete = false
            },
            onDismiss = { showDelete = false },
            destructive = true,
        )
    }

    if (showPicker) {
        FolderPickerSheet(
            startPath = state.files.firstOrNull()?.path?.substringBeforeLast('/')
                ?: "/storage/emulated/0",
            title = stringResource(R.string.action_move_here),
            onDismiss = { showPicker = false },
            onPick = { destination ->
                viewModel.moveSelectedTo(destination)
                showPicker = false
            },
        )
    }
}

@Composable
private fun ThresholdChips(current: Long, onSelect: (Long) -> Unit) {
    val options = listOf(
        10L * 1024 * 1024 to "10 MB",
        50L * 1024 * 1024 to "50 MB",
        100L * 1024 * 1024 to "100 MB",
        500L * 1024 * 1024 to "500 MB",
        1024L * 1024 * 1024 to "1 GB",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (bytes, label) ->
            FilterChip(
                selected = current == bytes,
                onClick = { onSelect(bytes) },
                label = { Text(label) },
            )
        }
    }
}