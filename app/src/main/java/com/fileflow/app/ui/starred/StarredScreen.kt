package com.fileflow.app.ui.starred

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.util.MimeTypes
import com.fileflow.app.core.util.OutcomeMessages
import com.fileflow.app.data.db.StarredFileEntity
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationReport
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileRow
import com.fileflow.app.ui.components.LoadingState
import com.fileflow.app.ui.util.FileIntents
import com.fileflow.app.ui.util.LocalUserSettings
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
data class StarredState(
    val items: List<StarredFileEntity> = emptyList(),
    val loading: Boolean = true,
) {
    /** Rows whose file has been moved or deleted since it was starred. */
    val missing: List<StarredFileEntity> get() = items.filter { !File(it.path).exists() }
}

@HiltViewModel
class StarredViewModel @Inject constructor(
    private val actions: FileActionsRepository,
    private val outcome: OutcomeMessages,
    private val operationRunner: OperationRunner,
) : ViewModel() {

    private val _state = MutableStateFlow(StarredState())
    val state: StateFlow<StarredState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            actions.observeStarred().collect { items ->
                _state.update { it.copy(items = items, loading = false) }
            }
        }
    }

    fun unstar(item: StarredFileEntity) {
        viewModelScope.launch { actions.toggleStar(item.toFileItem()) }
    }

    fun delete(item: StarredFileEntity) {
        viewModelScope.launch {
            try {
                var report: OperationReport? = null
                operationRunner.run(OperationKind.TRASH) { progress ->
                    report = actions.delete(listOf(item.path), permanent = false, progress)
                }
                val done = report
                if (done != null) {
                    _messages.send(
                        UiMessage.Done(
                            outcome.deleted(done.succeeded, done.failed, toTrash = true),
                            done.groupId.takeIf { done.succeeded > 0 },
                        )
                    )
                }
            } catch (_: CancellationException) {
                // Left mid-delete: the runner keeps going, the root bar shows it.
            }
        }
    }

    fun undo(groupId: String) {
        viewModelScope.launch {
            val result = actions.undoGroup(groupId)
            _messages.send(
                UiMessage.Info(outcome.reversed(result.restored, result.failed, result.skipped))
            )
        }
    }

    fun clearMissing() {
        viewModelScope.launch {
            val gone = state.value.missing
            gone.forEach { actions.toggleStar(it.toFileItem()) }
            _messages.send(UiMessage.Info(outcome.removed(gone.size, 0)))
        }
    }
}

/**
 * Stars are deliberately their own list rather than a flag on a folder: the
 * point of starring a file is that it stays findable after you move it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    viewModel: StarredViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val settings = LocalUserSettings.current
    val trashDays = settings.trashRetentionDays
    var confirmDelete by remember { mutableStateOf<StarredFileEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { group -> viewModel.undo(group) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.starred_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                // Loading used to fall through to the list, which rendered an
                // empty LazyColumn: a blank screen with no sign anything was
                // happening.
                state.loading && state.items.isEmpty() -> LoadingState(
                    label = stringResource(R.string.state_loading),
                )

                state.items.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Star,
                    title = stringResource(R.string.starred_empty),
                    body = stringResource(R.string.starred_empty_hint),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (state.missing.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.starred_missing,
                                        state.missing.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stringResource(R.string.starred_clear_missing),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { viewModel.clearMissing() }
                                        .padding(8.dp),
                                )
                            }
                        }
                    }
                    items(state.items, key = { it.path }) { entity ->
                        val item = entity.toFileItem()
                        FileRow(
                            modifier = Modifier.animateItem(),
                            item = item,
                            subtitle = entity.path.substringBeforeLast('/'),
                            onClick = { FileIntents.open(context, item) },
                            onLongClick = { onOpenFolder(entity.path.substringBeforeLast('/')) },
                            trailing = {
                                IconButton(onClick = { viewModel.unstar(entity) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Star,
                                        contentDescription = stringResource(R.string.cd_unstar),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                IconButton(onClick = {
                                    if (settings.confirmDelete) confirmDelete = entity
                                    else viewModel.delete(entity)
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.cd_delete),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { entity ->
        ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_title, 1, 1),
            body = stringResource(R.string.delete_body, trashDays),
            confirmLabel = stringResource(R.string.delete_move_to_trash),
            onConfirm = {
                viewModel.delete(entity)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
            destructive = true,
        )
    }
}

private fun StarredFileEntity.toFileItem(): FileItem {
    val extension = MimeTypes.extensionOf(name)
    return FileItem(
        path = path,
        name = name,
        isDirectory = false,
        sizeBytes = sizeBytes,
        lastModified = modified,
        extension = extension,
        mimeType = MimeTypes.mimeFor(extension),
        category = MimeTypes.categoryOf(extension),
        isHidden = name.startsWith("."),
    )
}