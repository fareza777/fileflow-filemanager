package com.fileflow.app.ui.recent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.storage.CollisionResolver
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.MediaScanner
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationReport
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.Paths
import com.fileflow.app.data.prefs.SettingsRepository
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.FileIndexRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.domain.privacy.MetadataStripper
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileActionSheet
import com.fileflow.app.ui.components.FileRow
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.LoadingState
import com.fileflow.app.ui.components.SelectionAction
import com.fileflow.app.ui.components.SelectionActionBar
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
data class RecentState(
    val loading: Boolean = true,
    val items: List<FileItem> = emptyList(),
    val selection: Set<String> = emptySet(),
    val working: Boolean = false,
    /** Where "the PC folder" is; empty until the user picks one. */
    val transferFolder: String = "",
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()
    val selectedItems: List<FileItem> get() = items.filter { it.path in selection }
    val selectedBytes: Long get() = selectedItems.sumOf { it.sizeBytes }
}

@HiltViewModel
class RecentFilesViewModel @Inject constructor(
    private val browseRepository: BrowseRepository,
    private val actions: FileActionsRepository,
    private val operationRunner: OperationRunner,
    private val stripper: MetadataStripper,
    private val mediaScanner: MediaScanner,
    private val indexRepository: FileIndexRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RecentState())
    val state: StateFlow<RecentState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        load()
        viewModelScope.launch {
            settings.settings.collect { prefs ->
                _state.update { it.copy(transferFolder = prefs.transferFolder) }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            // The card on the home screen shows a handful; this is the answer to
            // "and what else arrived?", so it asks for everything the index will
            // hand over rather than the same twelve.
            val items = browseRepository.recentFiles(limit = RECENT_LIMIT)
            _state.update {
                it.copy(
                    loading = false,
                    items = items,
                    // A file that is gone cannot stay selected.
                    selection = it.selection.filter { path -> items.any { item -> item.path == path } }
                        .toSet(),
                )
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
        _state.update { it.copy(selection = it.items.map { item -> item.path }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    fun moveTo(destination: String) {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        run(OperationKind.MOVE, "moved") { progress ->
            actions.move(paths, destination, ConflictPolicy.RENAME, progress)
        }
    }

    /** The same batch, left where it is. */
    fun copyTo(destination: String) {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        run(OperationKind.COPY, "copied") { progress ->
            actions.copy(paths, destination, ConflictPolicy.RENAME, progress)
        }
    }

    /**
     * Strips metadata from every selected image, each one to a copy beside
     * itself. Nothing is overwritten: a cleaned photo is a new file, and the
     * original is still the user's.
     */
    fun cleanMetadata() {
        val items = _state.value.selectedItems.filter { !it.isDirectory }
        if (items.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            var cleaned = 0
            var saved = 0L
            var skipped = 0
            items.forEach { item ->
                val source = File(item.path)
                val parent = source.parentFile
                if (parent == null || !MetadataStripper.canStrip(source)) return@forEach
                runCatching {
                    val name = CollisionResolver.unique(
                        parent,
                        source.name.substringBeforeLast('.') + ".clean." +
                            source.name.substringAfterLast('.', "jpg"),
                    )
                    val target = File(parent, name)
                    target to stripper.strip(source, target)
                }.onSuccess { (target, report) ->
                    if (report.removedBytes <= 0) {
                        // A file with nothing in it is not a copy worth keeping.
                        target.delete()
                        skipped++
                    } else {
                        cleaned++
                        saved += report.removedBytes
                        browseRepository.invalidateListing(parent.absolutePath)
                        indexRepository.upsertPaths(listOf(target.absolutePath))
                        mediaScanner.announce(listOf(target.absolutePath))
                    }
                }
            }
            _state.update { it.copy(working = false, selection = emptySet()) }
            _messages.send(
                UiMessage.Info(
                    when {
                        cleaned == 0 -> "Nothing to remove"
                        else -> "Cleaned $cleaned files · ${Formatters.bytes(saved)} removed" +
                            if (skipped > 0) ", $skipped had nothing" else ""
                    }
                )
            )
            load()
        }
    }

    /** Stars every selected file, or clears the stars when they all have one. */
    fun toggleStarOnSelection() {
        val items = _state.value.selectedItems
        if (items.isEmpty()) return
        viewModelScope.launch {
            val allStarred = items.all { actions.isStarred(it.path) }
            items.forEach { item -> actions.setStar(item, starred = !allStarred) }
            _state.update { it.copy(selection = emptySet()) }
            _messages.send(
                UiMessage.Info(if (allStarred) "Star removed" else "Starred ${items.size}")
            )
        }
    }

    /**
     * The computer is watching one folder, so "send it to the PC" is a move
     * rather than a share sheet: the file is there for the browser to pick up.
     */
    fun moveToTransferFolder(): Boolean {
        val folder = _state.value.transferFolder
        if (folder.isBlank()) return false
        moveTo(folder)
        return true
    }

    fun deleteSelected(permanent: Boolean) {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        run(OperationKind.DELETE, "removed") { progress ->
            actions.delete(paths, permanent, progress)
        }
    }

    private fun run(
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
                load()
            } catch (_: CancellationException) {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun undo(groupId: String) {
        viewModelScope.launch {
            val result = actions.undoGroup(groupId)
            _messages.send(UiMessage.Info("${result.restored} changes reverted"))
            load()
        }
    }

    companion object {
        const val RECENT_LIMIT = 300
    }
}

/**
 * Every recent file, not just the ones the home card had room for.
 *
 * The home screen's "view all" used to open the search screen with an empty
 * query, which showed nothing at all — the one promise a list of recent files
 * must not break. This is that list, and it is also where a file that just
 * arrived is most likely to be triaged: long-press to select, then move the
 * whole batch into a folder, into the PC folder, or out of the way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentFilesScreen(
    onBack: () -> Unit,
    onPreview: (FileItem) -> Unit,
    onOpenFolder: (String) -> Unit,
    onConvert: (List<String>) -> Unit,
    onCompress: (List<String>) -> Unit,
    viewModel: RecentFilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val settings = LocalUserSettings.current
    val trashDays = settings.trashRetentionDays
    var actionTarget by remember { mutableStateOf<FileItem?>(null) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { group -> viewModel.undo(group) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.selectionMode) {
                            stringResource(R.string.browser_selected_count, state.selection.size)
                        } else {
                            stringResource(R.string.home_recent)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (state.selectionMode) viewModel.clearSelection() else onBack() },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
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
                    actions = listOfNotNull(
                        SelectionAction(
                            icon = Icons.Filled.Share,
                            label = stringResource(R.string.action_share),
                            onClick = {
                                FileIntents.share(context, state.selectedItems)
                                viewModel.clearSelection()
                            },
                        ),
                        SelectionAction(
                            icon = Icons.AutoMirrored.Filled.DriveFileMove,
                            label = stringResource(R.string.action_move),
                            onClick = { picker = Picker.MOVE },
                        ),
                        SelectionAction(
                            icon = Icons.Filled.SwapHoriz,
                            label = stringResource(R.string.action_convert),
                            onClick = { onConvert(state.selection.toList()) },
                        ),
                        // Everything that needs a destination or a second
                        // question waits here, so the bar stays five verbs the
                        // thumb can reach instead of a row of glyphs.
                        SelectionAction(
                            icon = Icons.Filled.MoreVert,
                            label = stringResource(R.string.action_more),
                            onClick = { showMore = true },
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
                state.loading -> LoadingState(label = stringResource(R.string.state_loading))

                state.items.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Schedule,
                    title = stringResource(R.string.recent_empty),
                    body = stringResource(R.string.home_recent_empty),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        Text(
                            text = pluralStringResource(
                                R.plurals.item_count,
                                state.items.size,
                                state.items.size,
                            ) + " · " + Formatters.bytes(state.items.sumOf { it.sizeBytes }),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    items(state.items, key = { it.path }) { item ->
                        FileRow(
                            modifier = Modifier.animateItem(),
                            item = item,
                            thumbnail = true,
                            selectionMode = state.selectionMode,
                            selected = item.path in state.selection,
                            subtitle = "${Formatters.bytes(item.sizeBytes)} · ${
                                Formatters.relative(item.lastModified)
                            } · ${Paths.display(item.path.substringBeforeLast('/'))}",
                            onClick = {
                                if (state.selectionMode) viewModel.toggle(item.path) else onPreview(item)
                            },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggle(item.path)
                            },
                            trailing = {
                                IconButton(onClick = { actionTarget = item }) {
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

    actionTarget?.let { item ->
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
                onOpenFolder(item.path.substringBeforeLast('/'))
                actionTarget = null
            },
            onShare = {
                FileIntents.share(context, listOf(item))
                actionTarget = null
            },
            onMove = {
                viewModel.toggle(item.path)
                picker = Picker.MOVE
                actionTarget = null
            },
            onMoveToTransfer = if (state.transferFolder.isNotBlank()) {
                {
                    viewModel.toggle(item.path)
                    viewModel.moveToTransferFolder()
                    actionTarget = null
                }
            } else {
                null
            },
            onSelect = {
                viewModel.toggle(item.path)
                actionTarget = null
            },
            onDelete = {
                viewModel.toggle(item.path)
                if (settings.confirmDelete) showDelete = true
                else viewModel.deleteSelected(permanent = !settings.useTrash)
                actionTarget = null
            },
        )
    }

    if (showDelete) {
        ConfirmDialog(
            title = pluralStringResource(
                R.plurals.delete_title,
                state.selection.size,
                state.selection.size,
            ),
            body = stringResource(R.string.delete_body, trashDays),
            confirmLabel = stringResource(R.string.delete_move_to_trash),
            onConfirm = {
                viewModel.deleteSelected(permanent = !settings.useTrash)
                showDelete = false
            },
            onDismiss = { showDelete = false },
            destructive = true,
            extra = {
                Text(
                    text = Formatters.bytes(state.selectedBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }

    picker?.let { mode ->
        FolderPickerSheet(
            startPath = state.items.firstOrNull()?.path?.substringBeforeLast('/')
                ?: "/storage/emulated/0",
            title = stringResource(
                if (mode == Picker.COPY) R.string.action_copy_here else R.string.action_move_here
            ),
            onDismiss = { picker = null },
            onPick = { destination ->
                if (mode == Picker.COPY) viewModel.copyTo(destination) else viewModel.moveTo(destination)
                picker = null
            },
        )
    }

    if (showMore) {
        BulkMoreSheet(
            state = state,
            onDismiss = { showMore = false },
            onCopy = {
                showMore = false
                picker = Picker.COPY
            },
            onMoveToTransfer = {
                showMore = false
                viewModel.moveToTransferFolder()
            },
            onCompress = {
                showMore = false
                onCompress(state.selection.toList())
            },
            onCleanMetadata = {
                showMore = false
                viewModel.cleanMetadata()
            },
            onStar = {
                showMore = false
                viewModel.toggleStarOnSelection()
            },
            onSelectAll = viewModel::selectAll,
            onDeselectAll = viewModel::clearSelection,
        )
    }
}

/** Which folder picker is open. Null means none. */
private enum class Picker { MOVE, COPY }

/**
 * The verbs that need a second question — where to copy, what to compress —
 * or that apply to the whole batch at once. Keeping them behind one sheet means
 * the bar above stays short enough to hit accurately with a thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkMoreSheet(
    state: RecentState,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMoveToTransfer: () -> Unit,
    onCompress: () -> Unit,
    onCleanMetadata: () -> Unit,
    onStar: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val strippable = state.selectedItems.count { item ->
        !item.isDirectory && MetadataStripper.canStrip(File(item.path))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.item_count,
                    state.selection.size,
                    state.selection.size,
                ) + " · " + Formatters.bytes(state.selectedBytes),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )

            MoreRow(Icons.Filled.ContentCopy, R.string.action_copy) { onCopy() }
            if (state.transferFolder.isNotBlank()) {
                MoreRow(Icons.Filled.Devices, R.string.action_to_pc) { onMoveToTransfer() }
            }
            MoreRow(Icons.Filled.FolderZip, R.string.action_compress) { onCompress() }
            if (strippable > 0) {
                MoreRow(Icons.Filled.PrivacyTip, R.string.action_clean_metadata) { onCleanMetadata() }
            }
            MoreRow(Icons.Outlined.StarBorder, R.string.action_star) { onStar() }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            MoreRow(Icons.Filled.SelectAll, R.string.action_select_all) { onSelectAll() }
            MoreRow(Icons.Filled.Deselect, R.string.action_deselect_all) { onDeselectAll() }
        }
    }
}

@Composable
private fun MoreRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}