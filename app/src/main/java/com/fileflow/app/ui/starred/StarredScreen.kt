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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.MimeTypes
import com.fileflow.app.core.util.OutcomeMessages
import com.fileflow.app.data.db.StarredFileEntity
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationReport
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.MetadataCleanup
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.ui.components.InlineAction
import com.fileflow.app.ui.components.BulkHandlers
import com.fileflow.app.ui.components.BulkMoreSheet
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileActionSheet
import com.fileflow.app.ui.components.FileRow
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.LoadingState
import com.fileflow.app.ui.components.SelectionActionBar
import com.fileflow.app.ui.components.bulkBarActions
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
    val selection: Set<String> = emptySet(),
    val starredPaths: Set<String> = emptySet(),
    val working: Boolean = false,
) {
    /** Rows whose file has been moved or deleted since it was starred. */
    val missing: List<StarredFileEntity> get() = items.filter { !File(it.path).exists() }

    val selectionMode: Boolean get() = selection.isNotEmpty()
    val selected: List<StarredFileEntity> get() = items.filter { it.path in selection }
    val selectedItems: List<FileItem> get() = selected.map { it.toFileItem() }
    val selectedBytes: Long get() = selected.sumOf { it.sizeBytes }
    val allStarred: Boolean get() = selection.isNotEmpty() && selection.all { it in starredPaths }
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
                _state.update {
                    it.copy(
                        items = items,
                        loading = false,
                        // A file that was unstared is no longer selectable here.
                        selection = it.selection.filter { path -> items.any { e -> e.path == path } }
                            .toSet(),
                    )
                }
            }
        }
        viewModelScope.launch {
            actions.observeStarredPaths().collect { paths ->
                _state.update { it.copy(starredPaths = paths.toSet()) }
            }
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
        _state.update { it.copy(selection = it.items.map { entity -> entity.path }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    fun unstar(item: StarredFileEntity) {
        viewModelScope.launch { actions.toggleStar(item.toFileItem()) }
    }

    /** Unstars the whole selection; the files themselves are untouched. */
    fun unstarSelection() {
        val items = _state.value.selected
        if (items.isEmpty()) return
        viewModelScope.launch {
            items.forEach { actions.setStar(it.toFileItem(), starred = false) }
            _state.update { it.copy(selection = emptySet()) }
            _messages.send(UiMessage.Info(outcome.removed(items.size, 0)))
        }
    }

    fun moveTo(destination: String) {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        runBatch(OperationKind.MOVE, "moved") { progress ->
            actions.move(paths, destination, ConflictPolicy.RENAME, progress)
        }
    }

    fun copyTo(destination: String) {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        runBatch(OperationKind.COPY, "copied") { progress ->
            actions.copy(paths, destination, ConflictPolicy.RENAME, progress)
        }
    }

    fun deleteSelected() {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        runBatch(OperationKind.TRASH, "removed") { progress ->
            actions.delete(paths, permanent = false, progress)
        }
    }

    /** Same wording and same result as every other list's clean-metadata verb. */
    fun cleanMetadata() {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            var cleanup: MetadataCleanup? = null
            try {
                operationRunner.run(OperationKind.CLEAN) { progress ->
                    cleanup = actions.cleanMetadata(paths, progress)
                }
            } catch (_: CancellationException) {
                // Stopped from the root bar.
            }
            _state.update { it.copy(working = false, selection = emptySet()) }
            cleanup?.let { result ->
                _messages.send(
                    UiMessage.Info(
                        when {
                            result.cleaned == 0 && result.failed == 0 -> "Nothing to remove"
                            else -> "Cleaned ${result.cleaned} files · " +
                                "${Formatters.bytes(result.savedBytes)} removed" +
                                if (result.skipped > 0) ", ${result.skipped} had nothing" else ""
                        }
                    )
                )
            }
        }
    }

    private fun runBatch(
        kind: OperationKind,
        verb: String,
        body: suspend ((com.fileflow.app.core.storage.OperationProgress) -> Unit) -> OperationReport,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                var report: OperationReport? = null
                operationRunner.run(kind) { progress -> report = body(progress) }
                _state.update { it.copy(working = false, selection = emptySet()) }
                val done = report
                if (done != null) {
                    _messages.send(
                        UiMessage.Done(
                            text = "${done.succeeded} $verb" + if (done.failed > 0) {
                                ", ${done.failed} failed"
                            } else {
                                ""
                            },
                            undoGroup = done.groupId.takeIf { done.succeeded > 0 },
                        )
                    )
                }
            } catch (_: CancellationException) {
                _state.update { it.copy(working = false) }
            }
        }
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
 *
 * Selection works here like it does everywhere else — long press to start,
 * then the same bar of verbs as the browser and the recent list — because a
 * list of files the user hand-picked is exactly where a batch action is most
 * likely to be wanted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onPreview: (FileItem) -> Unit,
    onConvert: (List<String>) -> Unit,
    onBatchRename: (List<String>) -> Unit,
    onCompress: (List<String>) -> Unit,
    viewModel: StarredViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val settings = LocalUserSettings.current
    val trashDays = settings.trashRetentionDays
    var confirmDelete by remember { mutableStateOf<StarredFileEntity?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<BulkPicker?>(null) }
    var showMore by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<StarredFileEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { group -> viewModel.undo(group) }
        }
    }

    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }

    val handlers = BulkHandlers(
        onShare = {
            FileIntents.share(context, state.selectedItems)
            viewModel.clearSelection()
        },
        onMove = { picker = BulkPicker.MOVE },
        onCopy = {
            showMore = false
            picker = BulkPicker.COPY
        },
        onConvert = { onConvert(state.selection.toList()) },
        onBatchRename = { onBatchRename(state.selection.toList()) },
        onCompress = { onCompress(state.selection.toList()) },
        onCleanMetadata = viewModel::cleanMetadata,
        onStar = viewModel::unstarSelection,
        onDelete = {
            if (settings.confirmDelete) confirmBatchDelete = true else viewModel.deleteSelected()
        },
        onMore = { showMore = true },
        allStarred = state.allStarred,
        transferFolder = settings.transferFolder,
        onMoveToTransfer = if (settings.transferFolder.isNotBlank()) {
            { viewModel.moveTo(settings.transferFolder) }
        } else {
            null
        },
        onSelectAll = viewModel::selectAll,
        onDeselectAll = viewModel::clearSelection,
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.selectionMode) {
                            pluralStringResource(
                                R.plurals.item_count,
                                state.selection.size,
                                state.selection.size,
                            )
                        } else {
                            stringResource(R.string.starred_title)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (state.selectionMode) viewModel.clearSelection() else onBack() },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (state.selectionMode) {
                        IconButton(onClick = viewModel::selectAll) {
                            Icon(
                                imageVector = Icons.Filled.SelectAll,
                                contentDescription = stringResource(R.string.cd_select_all),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.selectionMode) {
                SelectionActionBar(
                    title = pluralStringResource(
                        R.plurals.item_count,
                        state.selection.size,
                        state.selection.size,
                    ),
                    subtitle = Formatters.bytes(state.selectedBytes),
                    actions = bulkBarActions(handlers),
                )
            }
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
                                InlineAction(
                                    text = stringResource(R.string.starred_clear_missing),
                                    onClick = viewModel::clearMissing,
                                    style = MaterialTheme.typography.labelMedium,
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
                            selectionMode = state.selectionMode,
                            selected = entity.path in state.selection,
                            onClick = {
                                if (state.selectionMode) viewModel.toggle(entity.path) else onPreview(item)
                            },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggle(entity.path)
                            },
                            trailing = {
                                IconButton(onClick = { actionTarget = entity }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = stringResource(R.string.cd_more),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    actionTarget?.let { entity ->
        val item = entity.toFileItem()
        FileActionSheet(
            item = item,
            onDismiss = { actionTarget = null },
            onOpen = {
                onPreview(item)
                actionTarget = null
            },
            onOpenWith = {
                FileIntents.openWith(context, item)
                actionTarget = null
            },
            onShowInFolder = {
                onOpenFolder(entity.path.substringBeforeLast('/'))
                actionTarget = null
            },
            onShare = {
                FileIntents.share(context, listOf(item))
                actionTarget = null
            },
            onCopy = {
                viewModel.toggle(entity.path)
                picker = BulkPicker.COPY
                actionTarget = null
            },
            onMove = {
                viewModel.toggle(entity.path)
                picker = BulkPicker.MOVE
                actionTarget = null
            },
            onMoveToTransfer = if (settings.transferFolder.isNotBlank()) {
                {
                    viewModel.toggle(entity.path)
                    viewModel.moveTo(settings.transferFolder)
                    actionTarget = null
                }
            } else {
                null
            },
            onSelect = {
                viewModel.toggle(entity.path)
                actionTarget = null
            },
            onStar = {
                viewModel.unstar(entity)
                actionTarget = null
            },
            isStarred = true,
            onDelete = {
                viewModel.toggle(entity.path)
                if (settings.confirmDelete) confirmBatchDelete = true else viewModel.deleteSelected()
                actionTarget = null
            },
        )
    }

    if (showMore) {
        BulkMoreSheet(
            items = state.selectedItems,
            bytes = state.selectedBytes,
            handlers = handlers,
            onDismiss = { showMore = false },
        )
    }

    picker?.let { mode ->
        FolderPickerSheet(
            startPath = state.items.firstOrNull()?.path?.substringBeforeLast('/')
                ?: "/storage/emulated/0",
            title = stringResource(
                if (mode == BulkPicker.COPY) R.string.action_copy_here else R.string.action_move_here
            ),
            onDismiss = { picker = null },
            onPick = { destination ->
                if (mode == BulkPicker.COPY) viewModel.copyTo(destination)
                else viewModel.moveTo(destination)
                picker = null
            },
        )
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

    if (confirmBatchDelete) {
        ConfirmDialog(
            title = pluralStringResource(
                R.plurals.delete_title,
                state.selection.size,
                state.selection.size,
            ),
            body = stringResource(R.string.delete_body, trashDays),
            confirmLabel = stringResource(R.string.delete_move_to_trash),
            onConfirm = {
                viewModel.deleteSelected()
                confirmBatchDelete = false
            },
            onDismiss = { confirmBatchDelete = false },
            destructive = true,
        )
    }
}

/** Which folder picker is open in the bulk bar. Null means none. */
private enum class BulkPicker { MOVE, COPY }

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