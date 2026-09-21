package com.fileflow.app.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.Paths
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.domain.compare.DiffRow
import com.fileflow.app.domain.compare.DirectoryComparer
import com.fileflow.app.domain.sync.SyncDirection
import com.fileflow.app.domain.sync.SyncMode
import com.fileflow.app.domain.sync.SyncPlan
import com.fileflow.app.domain.sync.SyncPlanner
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.SectionHeader
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

@androidx.compose.runtime.Immutable
data class SyncState(
    val left: String = "",
    val right: String = "",
    val direction: SyncDirection = SyncDirection.LEFT_TO_RIGHT,
    val mode: SyncMode = SyncMode.INCREMENTAL,
    val compareContent: Boolean = false,
    val plan: SyncPlan? = null,
    val working: Boolean = false,
    val progress: String? = null,
    val scanned: Int = 0,
) {
    val canPreview: Boolean get() = left.isNotBlank() && right.isNotBlank() && left != right
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val comparer: DirectoryComparer,
    private val browseRepository: BrowseRepository,
    private val actions: FileActionsRepository,
    private val operationRunner: OperationRunner,
) : ViewModel() {

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            val root = browseRepository.primaryRoot()
            _state.update { it.copy(left = root, right = root) }
        }
    }

    fun setLeft(path: String) = _state.update { it.copy(left = path, plan = null) }
    fun setRight(path: String) = _state.update { it.copy(right = path, plan = null) }

    fun setDirection(direction: SyncDirection) {
        _state.update { it.copy(direction = direction, plan = null) }
    }

    fun setMode(mode: SyncMode) {
        _state.update { it.copy(mode = mode, plan = null) }
    }

    fun setCompareContent(enabled: Boolean) {
        _state.update { it.copy(compareContent = enabled, plan = null) }
    }

    /** Compares and builds the plan, without touching anything. */
    fun preview() {
        val current = _state.value
        if (!current.canPreview || current.working) return
        viewModelScope.launch {
            _state.update { it.copy(working = true, plan = null, progress = null) }
            val result = runCatching {
                comparer.compare(current.left, current.right, current.compareContent) { scanned ->
                    _state.update { it.copy(scanned = scanned) }
                }
            }
            result.fold(
                onSuccess = { comparison ->
                    _state.update {
                        it.copy(
                            working = false,
                            scanned = comparison.scanned,
                            plan = SyncPlanner.plan(comparison.rows, current.direction, current.mode),
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(working = false) }
                    _messages.send(UiMessage.Info(error.message ?: "Could not compare"))
                },
            )
        }
    }

    /**
     * Runs the plan: copies first, deletions last.
     *
     * In that order on purpose. A sync that stops half-way — the phone is
     * locked, the process is killed — should leave the target with extra files
     * rather than missing ones, and the copy is the part the user asked for.
     */
    fun run() {
        val plan = _state.value.plan ?: return
        val current = _state.value
        if (current.working || plan.isEmpty) return

        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            var copied = 0
            var deleted = 0
            var failed = 0
            try {
                val copies = buildList {
                    if (plan.copyToRight.isNotEmpty()) {
                        add(plan.copyToRight.mapNotNull { it.leftPath } to current.right)
                    }
                    if (plan.copyToLeft.isNotEmpty()) {
                        add(plan.copyToLeft.mapNotNull { it.rightPath } to current.left)
                    }
                }
                copies.forEach { (paths, destination) ->
                    if (paths.isEmpty()) return@forEach
                    operationRunner.run(OperationKind.COPY) { progress ->
                        val report = actions.copy(paths, destination, ConflictPolicy.RENAME, progress)
                        copied += report.succeeded
                        failed += report.failed
                    }
                }

                val deletions = buildList {
                    if (plan.deleteOnRight.isNotEmpty()) {
                        add(plan.deleteOnRight.mapNotNull { it.rightPath })
                    }
                    if (plan.deleteOnLeft.isNotEmpty()) {
                        add(plan.deleteOnLeft.mapNotNull { it.leftPath })
                    }
                }
                deletions.forEach { paths ->
                    if (paths.isEmpty()) return@forEach
                    operationRunner.run(OperationKind.TRASH) { progress ->
                        val report = actions.delete(paths, permanent = false, progress)
                        deleted += report.succeeded
                        failed += report.failed
                    }
                }

                browseRepository.invalidateListing(current.left)
                browseRepository.invalidateListing(current.right)
                _state.update { it.copy(working = false) }
                _messages.send(
                    UiMessage.Info(
                        buildString {
                            append("$copied copied")
                            if (deleted > 0) append(", $deleted moved to trash")
                            if (failed > 0) append(", $failed failed")
                            if (plan.conflictsCount > 0) append(" · ${plan.conflictsCount} kept as conflicts")
                        }
                    )
                )
                preview()
            } catch (_: CancellationException) {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun clearPlan() {
        _state.update { it.copy(plan = null) }
    }
}

/**
 * Folder sync, with the plan on screen before anything moves.
 *
 * The one thing this screen insists on is that the user sees what will happen:
 * a sync is a batch of copies and deletions decided by rules, and a batch of
 * deletions the user did not read is how a sync tool loses somebody's folder.
 * Files that exist on both sides and differ are never resolved automatically —
 * without a record of which side changed last, the only honest answer is to
 * keep both and say so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<Boolean?>(null) }
    var confirmMirror by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbar.showMessage(context, message) { } }
    }

    val plan = state.plan

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (state.working) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SideRow(
                label = stringResource(R.string.compare_left),
                path = state.left,
                onChange = { picking = true },
            )
            SideRow(
                label = stringResource(R.string.compare_right),
                path = state.right,
                onChange = { picking = false },
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SyncDirection.entries.forEach { direction ->
                    FilterChip(
                        selected = state.direction == direction,
                        onClick = { viewModel.setDirection(direction) },
                        label = { Text(labelFor(direction)) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (state.mode == SyncMode.MIRROR) {
                                R.string.sync_mode_mirror
                            } else {
                                R.string.sync_mode_incremental
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.sync_mirror_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.mode == SyncMode.MIRROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(
                    checked = state.mode == SyncMode.MIRROR,
                    onCheckedChange = { viewModel.setMode(if (it) SyncMode.MIRROR else SyncMode.INCREMENTAL) },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = viewModel::preview,
                    enabled = state.canPreview && !state.working,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.sync_preview))
                }
                Button(
                    onClick = {
                        if (plan?.deletions ?: 0 > 0) confirmMirror = true else viewModel.run()
                    },
                    enabled = plan != null && !plan.isEmpty && !state.working,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_run))
                }
            }

            if (plan == null) {
                EmptyState(
                    icon = Icons.Filled.Sync,
                    title = if (state.working) {
                        stringResource(R.string.sync_comparing, state.scanned)
                    } else {
                        stringResource(R.string.sync_prompt)
                    },
                    body = stringResource(R.string.sync_prompt_hint),
                )
            } else {
                PlanCard(plan)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (plan.copyToRight.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.sync_to_right, plan.copyToRight.size)) }
                        items(plan.copyToRight, key = { "r-${it.relativePath}" }) { row ->
                            PlanRow(row, Icons.AutoMirrored.Filled.ArrowForward)
                        }
                    }
                    if (plan.copyToLeft.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.sync_to_left, plan.copyToLeft.size)) }
                        items(plan.copyToLeft, key = { "l-${it.relativePath}" }) { row ->
                            PlanRow(row, Icons.AutoMirrored.Filled.ArrowBack)
                        }
                    }
                    if (plan.deletions > 0) {
                        item { SectionHeader(stringResource(R.string.sync_deletions, plan.deletions)) }
                        items(plan.deleteOnRight + plan.deleteOnLeft, key = { "d-${it.relativePath}" }) { row ->
                            PlanRow(row, Icons.Filled.Delete, destructive = true)
                        }
                    }
                    if (plan.conflicts.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.sync_conflicts, plan.conflicts.size)) }
                        items(plan.conflicts, key = { "c-${it.relativePath}" }) { row ->
                            PlanRow(row, Icons.Filled.Sync)
                        }
                    }
                }
            }
        }
    }

    if (confirmMirror) {
        AlertDialog(
            onDismissRequest = { confirmMirror = false },
            title = { Text(stringResource(R.string.sync_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.sync_confirm_body,
                        plan?.deletions ?: 0,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmMirror = false
                    viewModel.run()
                }) {
                    Text(
                        text = stringResource(R.string.sync_run),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMirror = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    picking?.let { isLeft ->
        FolderPickerSheet(
            startPath = if (isLeft) state.left else state.right,
            title = stringResource(
                if (isLeft) R.string.compare_pick_left else R.string.compare_pick_right
            ),
            onDismiss = { picking = null },
            onPick = { path ->
                if (isLeft) viewModel.setLeft(path) else viewModel.setRight(path)
                picking = null
            },
        )
    }
}

@Composable
private fun PlanCard(plan: SyncPlan) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.sync_plan),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = plan.summary(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (plan.copiesBytes > 0) {
            Text(
                text = stringResource(R.string.sync_plan_bytes, Formatters.bytes(plan.copiesBytes)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (plan.conflictsCount > 0) {
            Text(
                text = stringResource(R.string.sync_conflict_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun SideRow(label: String, path: String, onChange: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onChange)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = Paths.display(path),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onChange) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = stringResource(R.string.compress_change_folder),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PlanRow(
    row: DiffRow,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    destructive: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = row.relativePath,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = Formatters.bytes(maxOf(row.leftSize, row.rightSize)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun labelFor(direction: SyncDirection): String = stringResource(
    when (direction) {
        SyncDirection.LEFT_TO_RIGHT -> R.string.sync_dir_right
        SyncDirection.RIGHT_TO_LEFT -> R.string.sync_dir_left
        SyncDirection.TWO_WAY -> R.string.sync_dir_both
    }
)
