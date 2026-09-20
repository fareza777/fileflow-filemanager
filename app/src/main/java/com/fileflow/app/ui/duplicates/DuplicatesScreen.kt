package com.fileflow.app.ui.duplicates

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
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
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationReport
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.scan.DuplicateFinder
import com.fileflow.app.domain.scan.DuplicateGroup
import com.fileflow.app.domain.toFileItem
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileRow
import com.fileflow.app.ui.components.ProgressPanel
import com.fileflow.app.ui.components.SectionCard
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

@androidx.compose.runtime.Immutable
data class DuplicatesState(
    val scanning: Boolean = true,
    val scanned: Int = 0,
    val groups: List<DuplicateGroup> = emptyList(),
    val selection: Set<String> = emptySet(),
    /** Derived when the selection changes, never filtered during a frame. */
    val selectedBytes: Long = 0,
    val working: Boolean = false,
) {
    fun withSelection(next: Set<String>): DuplicatesState = copy(
        selection = next,
        selectedBytes = if (next.isEmpty()) {
            0L
        } else {
            groups.flatMap { it.files }.filter { it.path in next }.sumOf { it.sizeBytes }
        },
    )
}

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val finder: DuplicateFinder,
    private val actions: FileActionsRepository,
    private val operationRunner: OperationRunner,
) : ViewModel() {

    private val _state = MutableStateFlow(DuplicatesState())
    val state: StateFlow<DuplicatesState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var scanJob: Job? = null

    init {
        scan()
    }

    fun scan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update {
                it.copy(scanning = true, scanned = 0, groups = emptyList()).withSelection(emptySet())
            }
            try {
                val groups = finder.find { seen -> _state.update { it.copy(scanned = seen) } }
                _state.update { it.copy(scanning = false, groups = groups) }
            } catch (_: CancellationException) {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    fun stop() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = false) }
    }

    /**
     * Keeps the oldest file in each group — the original, most likely — and
     * selects every later copy.
     */
    fun autoSelectCopies() {
        _state.update { current ->
            current.withSelection(
                current.groups.flatMap { group -> group.files.drop(1) }.map { it.path }.toSet()
            )
        }
    }

    fun toggle(path: String) {
        _state.update { current ->
            val next = if (path in current.selection) current.selection - path
            else current.selection + path
            current.withSelection(next)
        }
    }

    fun clearSelection() {
        _state.update { it.withSelection(emptySet()) }
    }

    fun deleteSelected() {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                var report: OperationReport? = null
                operationRunner.run(OperationKind.TRASH) { progress ->
                    report = actions.delete(paths, permanent = false, progress)
                }
                _state.update {
                    it.copy(
                        working = false,
                        groups = it.groups.mapNotNull { group ->
                            val remaining = group.files.filterNot { file -> file.path in paths }
                            if (remaining.size > 1) group.copy(files = remaining) else null
                        },
                    ).withSelection(emptySet())
                }
                val done = report
                if (done != null) {
                    _messages.send(
                        UiMessage.Done(
                            "${done.succeeded} files removed",
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    viewModel: DuplicatesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val settings = LocalUserSettings.current
    val trashDays = settings.trashRetentionDays
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { group -> viewModel.undo(group) }
        }
    }

    val recoverable = remember(state.groups) { state.groups.sumOf { it.wastedBytes } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dup_title)) },
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
                    subtitle = Formatters.bytes(state.selectedBytes),
                    actions = listOf(
                        SelectionAction(
                            icon = Icons.Filled.Deselect,
                            label = stringResource(R.string.action_deselect_all),
                            onClick = viewModel::clearSelection,
                        ),
                        SelectionAction(
                            icon = Icons.Filled.Delete,
                            label = stringResource(R.string.dup_delete),
                            onClick = { confirmDelete = true },
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
                    title = stringResource(R.string.dup_scanning, state.scanned),
                    detail = null,
                    fraction = null,
                    onCancel = viewModel::stop,
                    modifier = Modifier.padding(16.dp),
                )

                state.groups.isEmpty() -> EmptyState(
                    icon = Icons.Filled.ContentCopy,
                    title = stringResource(R.string.dup_none),
                    body = stringResource(R.string.dup_none_hint),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        SectionCard {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = stringResource(
                                        R.string.dup_found,
                                        state.groups.size,
                                        Formatters.bytes(recoverable),
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(R.string.dup_keep_best_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(
                                    onClick = viewModel::autoSelectCopies,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.dup_auto_select))
                                }
                            }
                        }
                    }

                    items(state.groups, key = { it.files.first().path }) { group ->
                        SectionCard(modifier = Modifier.animateItem()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.tertiary),
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        text = "${group.files.size} copies · ${
                                            Formatters.bytes(group.sizeBytes)
                                        } each",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                group.files.forEach { file ->
                                    DuplicateRow(
                                        file = file,
                                        isKeeper = file == group.files.first(),
                                        selected = file.path in state.selection,
                                        onToggle = { viewModel.toggle(file.path) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_title, state.selection.size, state.selection.size),
            body = stringResource(R.string.delete_body, trashDays),
            confirmLabel = stringResource(R.string.delete_move_to_trash),
            onConfirm = {
                viewModel.deleteSelected()
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

@Composable
private fun DuplicateRow(
    file: Candidate,
    isKeeper: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    FileRow(
        item = remember(file.path) { file.toFileItem() },
        selected = selected,
        selectionMode = true,
        subtitle = "${Formatters.bytes(file.sizeBytes)} · ${file.path.substringBeforeLast('/')}",
        accent = if (isKeeper) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.tertiary
        },
        onClick = { if (!isKeeper) onToggle() },
        onLongClick = { if (!isKeeper) onToggle() },
        trailing = {
            if (isKeeper) {
                Text(
                    text = stringResource(R.string.dup_keep),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}