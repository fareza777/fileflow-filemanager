package com.fileflow.app.ui.rules

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
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Undo
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
import com.fileflow.app.data.db.RuleLogEntity
import com.fileflow.app.data.repo.RulesRepository
import com.fileflow.app.domain.rules.RunStatus
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.LoadingState
import com.fileflow.app.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryState(
    val logs: List<RuleLogEntity> = emptyList(),
    val undoing: Long? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: RulesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val ruleId: Long = savedStateHandle.get<Long>("ruleId") ?: 0L

    init {
        viewModelScope.launch {
            val flow = if (ruleId > 0L) repository.observeLogsForRule(ruleId)
            else repository.observeLogs()
            flow.collect { logs ->
                _state.update { it.copy(logs = logs, loading = false) }
            }
        }
    }

    fun undo(log: RuleLogEntity) {
        viewModelScope.launch {
            _state.update { it.copy(undoing = log.id) }
            val summary = repository.undoRun(log.id)
            _state.update { it.copy(undoing = null) }
            _messages.send("${summary.restored} files restored, ${summary.failed} failed")
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            _messages.send("History cleared")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (state.logs.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.cd_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.logs.isEmpty() && state.loading) {
            // The first frame always has an empty list, so the empty state used
            // to flash "no history yet" on every open before the rows arrived.
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LoadingState(label = stringResource(R.string.state_loading))
            }
        } else if (state.logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = stringResource(R.string.history_empty),
                    body = stringResource(R.string.history_empty_hint),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.logs, key = { it.id }) { log ->
                    SectionCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(colorForStatus(log.status)),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = log.ruleName,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${Formatters.relative(log.startedAt)} · ${
                                            if (log.trigger == "SCHEDULED") {
                                                stringResource(R.string.history_scheduled)
                                            } else {
                                                stringResource(R.string.history_manual)
                                            }
                                        }",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (log.undoneAt == null &&
                                    log.status != RunStatus.UNDONE.name &&
                                    log.status != "PREVIEW" &&
                                    log.succeeded > 0
                                ) {
                                    IconButton(
                                        onClick = { viewModel.undo(log) },
                                        enabled = state.undoing != log.id,
                                    ) {
                                        Icon(Icons.Filled.Undo, contentDescription = stringResource(R.string.cd_undo))
                                    }
                                }
                            }
                            Text(
                                text = stringResource(
                                    R.string.history_detail,
                                    log.succeeded,
                                    Formatters.bytes(log.bytesMoved),
                                    statusLabel(log.status),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            log.note?.let { note ->
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.history_clear),
            body = stringResource(R.string.history_clear_confirm),
            confirmLabel = stringResource(R.string.history_clear),
            onConfirm = {
                viewModel.clearHistory()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
            destructive = true,
        )
    }
}

@Composable
private fun colorForStatus(status: String) = when (status) {
    RunStatus.SUCCESS.name -> MaterialTheme.colorScheme.primary
    RunStatus.PARTIAL.name -> MaterialTheme.colorScheme.tertiary
    RunStatus.FAILED.name -> MaterialTheme.colorScheme.error
    RunStatus.UNDONE.name -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    RunStatus.SUCCESS.name -> stringResource(R.string.history_status_success)
    RunStatus.PARTIAL.name -> stringResource(R.string.history_status_partial)
    RunStatus.FAILED.name -> stringResource(R.string.history_status_failed)
    RunStatus.UNDONE.name -> stringResource(R.string.history_status_undone)
    RunStatus.CANCELLED.name -> stringResource(R.string.history_status_cancelled)
    else -> stringResource(R.string.history_status_success)
}