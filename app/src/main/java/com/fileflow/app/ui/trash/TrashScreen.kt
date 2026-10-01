package com.fileflow.app.ui.trash

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.RestoreFromTrash
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationProgress
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.MimeTypes
import com.fileflow.app.core.util.OutcomeMessages
import com.fileflow.app.data.db.TrashEntity
import com.fileflow.app.data.repo.DeleteSummary
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.data.repo.RestoreSummary
import com.fileflow.app.data.repo.TrashRepository
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrashState(
    val items: List<TrashEntity> = emptyList(),
    val selection: Set<Long> = emptySet(),
    val loading: Boolean = true,
) {
    val selectedItems: List<TrashEntity> get() = items.filter { it.id in selection }
    val totalBytes: Long get() = items.sumOf { it.sizeBytes }
}

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repository: TrashRepository,
    private val outcome: OutcomeMessages,
    private val operationRunner: OperationRunner,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashState())
    val state: StateFlow<TrashState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.observeItems().collect { items ->
                _state.update { it.copy(items = items, loading = false) }
            }
        }
    }

    fun toggle(id: Long) {
        _state.update { current ->
            val selection = if (id in current.selection) current.selection - id
            else current.selection + id
            current.copy(selection = selection)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    /**
     * Restore and delete-forever can each touch hundreds of files. Running them
     * through [OperationRunner] means the global bar shows progress and offers a
     * Stop button from any screen, and navigating away no longer kills the work.
     */
    fun restoreSelected() {
        val ids = _state.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var summary: RestoreSummary? = null
            runCatching {
                operationRunner.run(OperationKind.RESTORE) { progress ->
                    summary = repository.restore(ids) { done, total, name ->
                        progress(
                            OperationProgress(
                                current = done,
                                total = total,
                                currentName = name,
                                bytesDone = 0L,
                                bytesTotal = 0L,
                            )
                        )
                    }
                }
            }
            val result = summary
            if (result != null) {
                _state.update { it.copy(selection = emptySet()) }
                _messages.send(outcome.restored(result.restored, result.failed))
            }
        }
    }

    fun deleteForeverSelected() {
        val ids = _state.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var summary: DeleteSummary? = null
            runCatching {
                operationRunner.run(OperationKind.DELETE) { progress ->
                    summary = repository.deleteForever(ids) { done, total, name ->
                        progress(
                            OperationProgress(
                                current = done,
                                total = total,
                                currentName = name,
                                bytesDone = 0L,
                                bytesTotal = 0L,
                            )
                        )
                    }
                }
            }
            val result = summary
            if (result != null) {
                _state.update { it.copy(selection = emptySet()) }
                _messages.send(
                    outcome.deleted(result.deleted, result.failed, toTrash = false)
                )
            }
        }
    }

    fun emptyAll() {
        viewModelScope.launch {
            var summary: DeleteSummary? = null
            runCatching {
                operationRunner.run(OperationKind.DELETE) { progress ->
                    summary = repository.emptyAll { done, total, name ->
                        progress(
                            OperationProgress(
                                current = done,
                                total = total,
                                currentName = name,
                                bytesDone = 0L,
                                bytesTotal = 0L,
                            )
                        )
                    }
                }
            }
            val result = summary
            if (result != null) {
                _state.update { it.copy(selection = emptySet()) }
                _messages.send(outcome.emptied(result.deleted, result.failed))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(onClick = { confirmEmpty = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.cd_delete))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.selection.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        // The most destructive control in the app had no label
                        // for a screen reader at all.
                        Text(stringResource(R.string.trash_delete_forever))
                    }
                    Button(
                        onClick = viewModel::restoreSelected,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.RestoreFromTrash, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("${state.selection.size}")
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.items.isEmpty() && !state.loading) {
                EmptyState(
                    icon = Icons.Filled.RestoreFromTrash,
                    title = stringResource(R.string.trash_empty),
                    body = stringResource(R.string.trash_empty_hint),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        Text(
                            // This was a stringResource() call against a plurals
                            // id, which resolves the bag rather than a quantity
                            // and blew up on the resource lookup.
                            text = pluralStringResource(
                                R.plurals.items_and_size,
                                state.items.size,
                                state.items.size,
                                Formatters.bytes(state.totalBytes),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    items(state.items, key = { it.id }) { entity ->
                        FileRow(
                            item = entity.toFileItem(),
                            selected = entity.id in state.selection,
                            selectionMode = true,
                            subtitle = "${Formatters.bytes(entity.sizeBytes)} · ${
                                Formatters.relative(entity.deletedAt)
                            }",
                            onClick = { viewModel.toggle(entity.id) },
                            onLongClick = { viewModel.toggle(entity.id) },
                        )
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        ConfirmDialog(
            title = stringResource(R.string.trash_empty_now),
            body = stringResource(R.string.trash_empty_confirm, state.items.size),
            confirmLabel = stringResource(R.string.delete_forever),
            onConfirm = {
                viewModel.emptyAll()
                confirmEmpty = false
            },
            onDismiss = { confirmEmpty = false },
            destructive = true,
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.trash_delete_forever),
            body = stringResource(R.string.delete_body_permanent),
            confirmLabel = stringResource(R.string.delete_forever),
            onConfirm = {
                viewModel.deleteForeverSelected()
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

private fun TrashEntity.toFileItem(): FileItem {
    val extension = MimeTypes.extensionOf(name)
    return FileItem(
        path = trashPath,
        name = name,
        isDirectory = isDirectory,
        sizeBytes = sizeBytes,
        lastModified = deletedAt,
        extension = extension,
        mimeType = MimeTypes.mimeFor(extension),
        category = MimeTypes.categoryOf(extension),
        isHidden = false,
    )
}