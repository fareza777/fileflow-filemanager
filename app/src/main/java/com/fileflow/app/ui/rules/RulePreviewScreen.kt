package com.fileflow.app.ui.rules

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Undo
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.repo.RulesRepository
import com.fileflow.app.data.repo.UndoSummary
import com.fileflow.app.domain.rules.ExecutionProgress
import com.fileflow.app.domain.rules.PlannedOperation
import com.fileflow.app.domain.rules.Rule
import com.fileflow.app.domain.rules.RulePreview
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.ProgressPanel
import com.fileflow.app.ui.components.SectionCard
import com.fileflow.app.ui.components.SectionHeader
import com.fileflow.app.ui.components.StatPill
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PreviewState(
    val rule: Rule? = null,
    val preview: RulePreview? = null,
    val operations: List<PlannedOperation> = emptyList(),
    val computing: Boolean = true,
    val scanned: Int = 0,
    val matched: Int = 0,
    val running: Boolean = false,
    val progress: ExecutionProgress? = null,
    val result: String? = null,
    val logId: Long? = null,
    val undo: UndoSummary? = null,
)

@HiltViewModel
class RulePreviewViewModel @Inject constructor(
    private val repository: RulesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val ruleId: Long = savedStateHandle.get<Long>(RuleEditorViewModel.ARG_RULE_ID) ?: 0L

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val rule = repository.get(ruleId)
            if (rule == null) {
                _state.update { it.copy(computing = false, rule = null) }
                return@launch
            }
            _state.update { it.copy(rule = rule, computing = true, scanned = 0, matched = 0) }
            val draft = com.fileflow.app.domain.rules.RuleDraft(
                id = rule.id,
                name = rule.name,
                enabled = rule.enabled,
                spec = rule.spec,
            )
            val (preview, operations) = repository.preview(draft) { scanned, matched ->
                _state.update { it.copy(scanned = scanned, matched = matched) }
            }
            _state.update {
                it.copy(
                    computing = false,
                    preview = preview,
                    operations = operations,
                )
            }
        }
    }

    fun run() {
        val rule = _state.value.rule ?: return
        val operations = _state.value.operations
        if (operations.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(running = true, result = null, progress = null) }
            val outcome = repository.runPlan(rule, operations) { progress ->
                _state.update { it.copy(progress = progress) }
            }
            _state.update {
                it.copy(
                    running = false,
                    progress = null,
                    logId = outcome.logId,
                    result = if (outcome.failed > 0) {
                        "partial:${outcome.succeeded}:${outcome.failed}"
                    } else {
                        "ok:${outcome.succeeded}"
                    },
                    preview = it.preview?.copy(operations = emptyList()),
                    operations = emptyList(),
                )
            }
        }
    }

    fun undo() {
        val logId = _state.value.logId ?: return
        viewModelScope.launch {
            val summary = repository.undoRun(logId)
            _state.update { it.copy(undo = summary, result = null, logId = null) }
            _messages.send("${summary.restored} files restored")
        }
    }

    fun dismissResult() {
        _state.update { it.copy(result = null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulePreviewScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: RulePreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.rule?.name ?: stringResource(R.string.preview_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(
                            Icons.Filled.TaskAlt,
                            contentDescription = stringResource(R.string.cd_history),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                when {
                    state.running -> Unit
                    state.result != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::dismissResult,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.action_done))
                        }
                        if (state.logId != null) {
                            Button(onClick = viewModel::undo, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Undo, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_undo))
                            }
                        }
                    }
                    state.operations.isNotEmpty() -> Button(
                        onClick = viewModel::run,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.preview_run, state.operations.size))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.preview_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.computing) {
                item {
                    ProgressPanel(
                        title = stringResource(R.string.preview_scanning, state.scanned),
                        detail = if (state.matched > 0) {
                            stringResource(
                                R.string.preview_matches,
                                state.matched,
                                "",
                            )
                        } else {
                            null
                        },
                        fraction = null,
                    )
                }
            }

            if (state.running) {
                item {
                    ProgressPanel(
                        title = state.progress?.let {
                            stringResource(R.string.preview_running, it.processed, it.total)
                        } ?: stringResource(R.string.op_moving),
                        detail = state.progress?.currentName,
                        fraction = state.progress?.let { progress ->
                            if (progress.total > 0) {
                                progress.processed.toFloat() / progress.total.toFloat()
                            } else {
                                null
                            }
                        },
                    )
                }
            }

            state.result?.let { result ->
                item {
                    ResultCard(
                        text = formatResult(result),
                        undo = state.undo,
                    )
                }
            }

            state.preview?.let { preview ->
                if (preview.operations.isNotEmpty()) {
                    item {
                        SectionCard {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = stringResource(
                                        R.string.preview_matches,
                                        preview.operations.size,
                                        Formatters.bytes(preview.totalBytes),
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatPill(
                                        label = stringResource(R.string.common_items),
                                        value = preview.operations.size.toString(),
                                        modifier = Modifier.weight(1f),
                                    )
                                    StatPill(
                                        label = stringResource(R.string.analyzer_by_folder),
                                        value = preview.foldersAffected.toString(),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (preview.conflicts > 0) {
                                    Text(
                                        text = stringResource(
                                            R.string.preview_conflict_flagged,
                                            preview.conflicts,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                        }
                    }
                    item { SectionHeader(stringResource(R.string.preview_affected)) }
                    items(preview.operations.take(200), key = { it.sourcePath }) { operation ->
                        OperationRow(operation)
                    }
                } else if (!state.computing) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.CheckCircle,
                            title = stringResource(R.string.preview_no_matches),
                            body = stringResource(R.string.preview_no_matches_hint),
                        )
                    }
                }
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun OperationRow(operation: PlannedOperation) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = operation.sourceName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (operation.destinationPath != null && operation.destinationPath != operation.sourcePath) {
            Text(
                text = "→ ${operation.destinationPath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = Formatters.bytes(operation.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (operation.conflict) {
                Text(
                    text = stringResource(R.string.conflict_rename),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(text: String, undo: UndoSummary?) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(text = text, style = MaterialTheme.typography.titleSmall)
            }
            undo?.let {
                Text(
                    text = stringResource(
                        R.string.history_undo_partial,
                        it.restored,
                        it.failed,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun formatResult(result: String): String {
    val parts = result.split(':')
    return when (parts.firstOrNull()) {
        "ok" -> stringResource(R.string.preview_result_ok, parts.getOrNull(1)?.toIntOrNull() ?: 0)
        "partial" -> stringResource(
            R.string.preview_result,
            parts.getOrNull(1)?.toIntOrNull() ?: 0,
            parts.getOrNull(2)?.toIntOrNull() ?: 0,
        )
        else -> stringResource(R.string.action_done)
    }
}