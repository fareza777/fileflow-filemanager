package com.fileflow.app.ui.dualpane

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.model.SortOrder
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationProgress
import com.fileflow.app.core.storage.OperationReport
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.Paths
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which half of the screen an action is about. */
enum class PaneSide { LEFT, RIGHT }

@androidx.compose.runtime.Immutable
data class PaneState(
    val path: String = "",
    val items: List<FileItem> = emptyList(),
    val selection: Set<String> = emptySet(),
    val loading: Boolean = true,
) {
    val selectedItems: List<FileItem> get() = items.filter { it.path in selection }
    val selectedBytes: Long get() = selectedItems.sumOf { it.sizeBytes }
    val title: String get() = Paths.nameOf(path).ifEmpty { path }
}

@androidx.compose.runtime.Immutable
data class DualState(
    val left: PaneState = PaneState(),
    val right: PaneState = PaneState(),
    val busy: Boolean = false,
) {
    fun pane(side: PaneSide) = if (side == PaneSide.LEFT) left else right
    fun other(side: PaneSide) = if (side == PaneSide.LEFT) right else left

    /** The pane holding a selection; both is not a state the UI can produce. */
    val activeSide: PaneSide?
        get() = when {
            left.selection.isNotEmpty() -> PaneSide.LEFT
            right.selection.isNotEmpty() -> PaneSide.RIGHT
            else -> null
        }
}

@HiltViewModel
class DualPaneViewModel @Inject constructor(
    private val browseRepository: BrowseRepository,
    private val actions: FileActionsRepository,
    private val operationRunner: OperationRunner,
) : ViewModel() {

    private val _state = MutableStateFlow(DualState())
    val state: StateFlow<DualState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            val root = browseRepository.primaryRoot()
            open(PaneSide.LEFT, root)
            open(PaneSide.RIGHT, root)
        }
    }

    fun open(side: PaneSide, path: String) {
        update(side) { it.copy(path = path, loading = true, selection = emptySet()) }
        viewModelScope.launch { reload(side) }
    }

    /** Walks into a folder, or toggles a file. */
    fun activate(side: PaneSide, item: FileItem) {
        if (item.isDirectory) {
            open(side, item.path)
            return
        }
        toggle(side, item.path)
    }

    fun toggle(side: PaneSide, path: String) {
        update(side) { pane ->
            val selection = if (path in pane.selection) pane.selection - path
            else pane.selection + path
            // A selection in one pane is the source for the other; two at once
            // would make "copy to the other side" ambiguous.
            pane.copy(selection = selection)
        }
        val active = _state.value.activeSide
        if (active != null) {
            val other = if (side == PaneSide.LEFT) PaneSide.RIGHT else PaneSide.LEFT
            update(other) { it.copy(selection = emptySet()) }
        }
    }

    fun selectAll(side: PaneSide) {
        update(side) { it.copy(selection = it.items.map { item -> item.path }.toSet()) }
    }

    fun clearSelection() {
        update(PaneSide.LEFT) { it.copy(selection = emptySet()) }
        update(PaneSide.RIGHT) { it.copy(selection = emptySet()) }
    }

    fun up(side: PaneSide) {
        val pane = _state.value.pane(side)
        val parent = Paths.parentOf(pane.path) ?: return
        if (browseRepository.isRestricted(parent)) return
        open(side, parent)
    }

    fun swap() {
        _state.update { it.copy(left = it.right, right = it.left) }
    }

    /** Copies or moves the active pane's selection into the other pane. */
    fun transfer(cut: Boolean) {
        val current = _state.value
        val side = current.activeSide ?: return
        val source = current.pane(side)
        val destination = current.other(side)
        val paths = source.selectedItems.map { it.path }
        if (paths.isEmpty() || destination.path.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                var report: OperationReport? = null
                operationRunner.run(if (cut) OperationKind.MOVE else OperationKind.COPY) { progress ->
                    report = if (cut) {
                        actions.move(paths, destination.path, ConflictPolicy.RENAME, progress)
                    } else {
                        actions.copy(paths, destination.path, ConflictPolicy.RENAME, progress)
                    }
                }
                clearSelection()
                val done = report
                if (done != null) {
                    _messages.send(
                        UiMessage.Done(
                            text = "${done.succeeded} ${if (cut) "moved" else "copied"}" +
                                if (done.failed > 0) ", ${done.failed} failed" else "",
                            undoGroup = done.groupId.takeIf { done.succeeded > 0 },
                        )
                    )
                }
                reload(side)
                reload(if (side == PaneSide.LEFT) PaneSide.RIGHT else PaneSide.LEFT)
            } catch (_: CancellationException) {
                // Cancelled from the global progress bar; the panes are reloaded
                // anyway so a half-finished batch is still shown honestly.
                reload(side)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun undo(groupId: String) {
        viewModelScope.launch {
            actions.undoGroup(groupId)
            reload(PaneSide.LEFT)
            reload(PaneSide.RIGHT)
        }
    }

    private suspend fun reload(side: PaneSide) {
        val pane = _state.value.pane(side)
        if (pane.path.isBlank()) return
        val items = runCatching {
            browseRepository.listItems(pane.path, includeHidden = false, sort = SortOrder())
        }.getOrDefault(emptyList())
        update(side) { current ->
            if (current.path != pane.path) {
                current
            } else {
                val present = items.mapTo(HashSet(items.size)) { it.path }
                current.copy(
                    items = items,
                    loading = false,
                    selection = current.selection.filterTo(HashSet()) { it in present },
                )
            }
        }
    }

    private fun update(side: PaneSide, block: (PaneState) -> PaneState) {
        _state.update { current ->
            if (side == PaneSide.LEFT) current.copy(left = block(current.left))
            else current.copy(right = block(current.right))
        }
    }
}

/**
 * Two folders, side by side, with the copy going between them.
 *
 * The shape every serious file manager converges on, and the one thing a phone
 * can do that a laptop cannot: the destination is always visible, so "where did
 * that go" never has to be asked. Stacked in portrait — half a screen each is
 * still more than a dialog — and side by side in landscape.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualPaneScreen(
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    viewModel: DualPaneViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<PaneSide?>(null) }
    val activeSide = state.activeSide

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
                        text = if (activeSide == null) {
                            stringResource(R.string.dual_title)
                        } else {
                            pluralStringResource(
                                R.plurals.item_count,
                                state.pane(activeSide).selection.size,
                                state.pane(activeSide).selection.size,
                            ) + " · " + Formatters.bytes(state.pane(activeSide).selectedBytes)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (activeSide != null) viewModel.clearSelection() else onBack() },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (activeSide != null) {
                        IconButton(onClick = { viewModel.selectAll(activeSide) }) {
                            Icon(
                                imageVector = Icons.Filled.SelectAll,
                                contentDescription = stringResource(R.string.cd_select_all),
                            )
                        }
                    } else {
                        IconButton(onClick = viewModel::swap) {
                            Icon(
                                imageVector = Icons.Filled.SwapHoriz,
                                contentDescription = stringResource(R.string.dual_swap),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (activeSide != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(
                                imageVector = Icons.Filled.Deselect,
                                contentDescription = stringResource(R.string.action_deselect_all),
                            )
                        }
                        val forward = activeSide == PaneSide.LEFT
                        OutlinedButton(
                            onClick = { viewModel.transfer(cut = false) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.dual_copy))
                        }
                        Button(
                            onClick = { viewModel.transfer(cut = true) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.dual_move))
                        }
                        Icon(
                            imageVector = if (forward) {
                                Icons.AutoMirrored.Filled.ArrowForward
                            } else {
                                Icons.AutoMirrored.Filled.ArrowBack
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val sideBySide = maxWidth > maxHeight
            val panes: @Composable (PaneSide, Modifier) -> Unit = { side, paneModifier ->
                Pane(
                    side = side,
                    pane = state.pane(side),
                    active = activeSide == side,
                    onEnter = { viewModel.activate(side, it) },
                    onToggle = { viewModel.toggle(side, it) },
                    onUp = { viewModel.up(side) },
                    onChangeFolder = { picking = side },
                    modifier = paneModifier,
                )
            }
            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        panes(PaneSide.LEFT, Modifier.fillMaxSize())
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        panes(PaneSide.RIGHT, Modifier.fillMaxSize())
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    panes(PaneSide.LEFT, Modifier.fillMaxWidth().weight(1f))
                    panes(PaneSide.RIGHT, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }

    picking?.let { side ->
        val pane = state.pane(side)
        FolderPickerSheet(
            startPath = pane.path.ifBlank { "/" },
            title = stringResource(
                if (side == PaneSide.LEFT) R.string.dual_pick_left else R.string.dual_pick_right
            ),
            onDismiss = { picking = null },
            onPick = { path ->
                viewModel.open(side, path)
                picking = null
            },
        )
    }
}

@Composable
private fun Pane(
    side: PaneSide,
    pane: PaneState,
    active: Boolean,
    onEnter: (FileItem) -> Unit,
    onToggle: (String) -> Unit,
    onUp: () -> Unit,
    onChangeFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            width = if (active) 1.5.dp else 1.dp,
            color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onChangeFolder),
                ) {
                    Text(
                        text = pane.title.ifEmpty { "—" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = pane.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onUp) {
                    Icon(
                        imageVector = Icons.Filled.HorizontalRule,
                        contentDescription = stringResource(R.string.remote_up_one_level),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onChangeFolder) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = stringResource(R.string.dual_change_folder),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (pane.loading) {
                Text(
                    text = stringResource(R.string.state_loading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            if (pane.items.isEmpty() && !pane.loading) {
                // Half a screen of nothing looks like a bug; a folder that is
                // genuinely empty should say so.
                EmptyState(
                    icon = Icons.Filled.Folder,
                    title = stringResource(R.string.browser_empty),
                    body = stringResource(R.string.browser_empty_hint),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(pane.items, key = { it.path }) { item ->
                    PaneRow(
                        item = item,
                        selected = item.path in pane.selection,
                        onClick = { onEnter(item) },
                        onLongClick = { onToggle(item.path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PaneRow(
    item: FileItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (item.isDirectory) Icons.Filled.Folder else Icons.Filled.HorizontalRule,
            contentDescription = null,
            tint = if (item.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!item.isDirectory) {
            Text(
                text = Formatters.bytes(item.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
