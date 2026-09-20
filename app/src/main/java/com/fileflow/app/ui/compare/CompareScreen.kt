package com.fileflow.app.ui.compare

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.fileflow.app.core.storage.OperationReport
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.Paths
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.domain.compare.CompareResult
import com.fileflow.app.domain.compare.DiffRow
import com.fileflow.app.domain.compare.DiffState
import com.fileflow.app.domain.compare.DirectoryComparer
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

/** Which side of the comparison a filter is showing. */
enum class DiffFilter { ALL, DIFFERENCES, ONLY_LEFT, ONLY_RIGHT }

@androidx.compose.runtime.Immutable
data class CompareUiState(
    val left: String = "",
    val right: String = "",
    val result: CompareResult? = null,
    val comparing: Boolean = false,
    val compareContent: Boolean = false,
    val filter: DiffFilter = DiffFilter.ALL,
    val working: Boolean = false,
) {
    val rows: List<DiffRow>
        get() {
            val all = result?.rows.orEmpty()
            return when (filter) {
                DiffFilter.ALL -> all
                DiffFilter.DIFFERENCES -> all.filter { it.state != DiffState.SAME }
                DiffFilter.ONLY_LEFT -> all.filter { it.state == DiffState.LEFT_ONLY }
                DiffFilter.ONLY_RIGHT -> all.filter { it.state == DiffState.RIGHT_ONLY }
            }
        }

    val canCompare: Boolean get() = left.isNotBlank() && right.isNotBlank() && left != right
}

@HiltViewModel
class CompareViewModel @Inject constructor(
    private val comparer: DirectoryComparer,
    private val actions: FileActionsRepository,
    private val operationRunner: OperationRunner,
    private val browseRepository: BrowseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CompareUiState())
    val state: StateFlow<CompareUiState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        // Enumerating volumes touches the disk, so it happens off the main
        // thread even though it is only the default both sides start at.
        viewModelScope.launch {
            val root = browseRepository.primaryRoot()
            _state.update { it.copy(left = root, right = root) }
        }
    }

    fun setLeft(path: String) = _state.update { it.copy(left = path, result = null) }
    fun setRight(path: String) = _state.update { it.copy(right = path, result = null) }
    fun setFilter(filter: DiffFilter) = _state.update { it.copy(filter = filter) }
    fun setCompareContent(enabled: Boolean) {
        _state.update { it.copy(compareContent = enabled, result = null) }
    }

    fun compare() {
        val current = _state.value
        if (!current.canCompare || current.comparing) return
        viewModelScope.launch {
            _state.update { it.copy(comparing = true, result = null) }
            val result = runCatching {
                comparer.compare(current.left, current.right, current.compareContent)
            }
            _state.update { it.copy(comparing = false, result = result.getOrNull()) }
            result.exceptionOrNull()?.let { error ->
                _messages.send(UiMessage.Info(error.message ?: "Could not compare"))
            }
        }
    }

    /** Copies one row, or every row the current view is showing, to one side. */
    fun copy(rows: List<DiffRow>, toRight: Boolean) {
        val paths = rows.mapNotNull { row ->
            when {
                toRight -> row.leftPath.takeIf { row.state != DiffState.SAME }
                else -> row.rightPath.takeIf { row.state != DiffState.SAME }
            }
        }.filterNotNull()
        if (paths.isEmpty()) return

        val destination = if (toRight) _state.value.right else _state.value.left
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                var report: OperationReport? = null
                operationRunner.run(OperationKind.COPY) { progress ->
                    report = actions.copy(paths, destination, ConflictPolicy.RENAME, progress)
                }
                val done = report
                _messages.send(
                    UiMessage.Done(
                        text = "${done?.succeeded ?: 0} copied to ${Paths.nameOf(destination)}",
                        undoGroup = done?.groupId?.takeIf { (done?.succeeded ?: 0) > 0 },
                    )
                )
                compare()
            } catch (_: CancellationException) {
                _state.update { it.copy(working = false) }
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun undo(groupId: String) {
        viewModelScope.launch {
            actions.undoGroup(groupId)
            compare()
        }
    }
}

/**
 * Folder against folder, with the differences on top.
 *
 * Built for the question a sync tool has to answer before it moves anything:
 * what is only here, what is only there, and what is in both but not the same.
 * Copying runs in whichever direction the user points at, and every copy is a
 * copy — never a delete — so the worst case of a mistake here is a duplicate,
 * which is a state the app already has tools for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    onBack: () -> Unit,
    viewModel: CompareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { group -> viewModel.undo(group) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compare_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (state.comparing || state.working) {
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SideCard(
                label = stringResource(R.string.compare_left),
                path = state.left,
                onChange = { picking = true },
            )
            SideCard(
                label = stringResource(R.string.compare_right),
                path = state.right,
                onChange = { picking = false },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.compare_content),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.compare_content_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.compareContent,
                    onCheckedChange = viewModel::setCompareContent,
                )
            }

            Button(
                onClick = viewModel::compare,
                enabled = state.canCompare && !state.comparing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.Compare, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.compare_run))
            }

            val result = state.result
            if (result != null) {
                if (!result.hasWork) {
                    EmptyState(
                        icon = Icons.Filled.Compare,
                        title = stringResource(R.string.compare_identical),
                        body = stringResource(R.string.compare_identical_hint, result.rows.size),
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.filter == DiffFilter.ALL,
                            onClick = { viewModel.setFilter(DiffFilter.ALL) },
                            label = { Text(stringResource(R.string.compare_all, result.rows.size)) },
                        )
                        FilterChip(
                            selected = state.filter == DiffFilter.DIFFERENCES,
                            onClick = { viewModel.setFilter(DiffFilter.DIFFERENCES) },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.compare_differences,
                                        result.onlyLeft + result.onlyRight + result.different,
                                    )
                                )
                            },
                        )
                        FilterChip(
                            selected = state.filter == DiffFilter.ONLY_LEFT,
                            onClick = { viewModel.setFilter(DiffFilter.ONLY_LEFT) },
                            label = { Text(stringResource(R.string.compare_only_left, result.onlyLeft)) },
                        )
                        FilterChip(
                            selected = state.filter == DiffFilter.ONLY_RIGHT,
                            onClick = { viewModel.setFilter(DiffFilter.ONLY_RIGHT) },
                            label = { Text(stringResource(R.string.compare_only_right, result.onlyRight)) },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.copy(state.rows, toRight = false) },
                            enabled = !state.working,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.compare_to_left))
                        }
                        OutlinedButton(
                            onClick = { viewModel.copy(state.rows, toRight = true) },
                            enabled = !state.working,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(stringResource(R.string.compare_to_right))
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.rows, key = { it.relativePath }) { row ->
                    DiffRowItem(
                        row = row,
                        onCopyToRight = { viewModel.copy(listOf(row), toRight = true) },
                        onCopyToLeft = { viewModel.copy(listOf(row), toRight = false) },
                        enabled = !state.working,
                    )
                }
            }
        }
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
private fun SideCard(label: String, path: String, onChange: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onChange),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = path,
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
}

@Composable
private fun DiffRowItem(
    row: DiffRow,
    onCopyToRight: () -> Unit,
    onCopyToLeft: () -> Unit,
    enabled: Boolean,
) {
    val (label, tint) = stateBadge(row.state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (row.state == DiffState.SAME) {
                    MaterialTheme.colorScheme.surface
                } else {
                    tint.copy(alpha = 0.08f)
                }
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(tint.copy(alpha = 0.16f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val folderLabel = stringResource(R.string.common_folder)
            val parent = row.relativePath.substringBeforeLast('/', "")
            Text(
                text = buildString {
                    if (parent.isNotEmpty()) {
                        append(parent)
                        append(" · ")
                    }
                    if (row.isDirectory) {
                        append(folderLabel)
                    } else {
                        append(Formatters.bytes(maxOf(row.leftSize, row.rightSize)))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.state != DiffState.SAME) {
            IconButton(onClick = onCopyToLeft, enabled = enabled) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.compare_to_left),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onCopyToRight, enabled = enabled) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.compare_to_right),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun stateBadge(state: DiffState) = when (state) {
    DiffState.SAME -> stringResource(R.string.compare_state_same) to MaterialTheme.colorScheme.primary
    DiffState.LEFT_ONLY -> stringResource(R.string.compare_state_left) to MaterialTheme.colorScheme.tertiary
    DiffState.RIGHT_ONLY -> stringResource(R.string.compare_state_right) to MaterialTheme.colorScheme.secondary
    DiffState.DIFFERENT -> stringResource(R.string.compare_state_different) to MaterialTheme.colorScheme.error
    DiffState.TYPE_MISMATCH -> stringResource(R.string.compare_state_type) to MaterialTheme.colorScheme.error
}
