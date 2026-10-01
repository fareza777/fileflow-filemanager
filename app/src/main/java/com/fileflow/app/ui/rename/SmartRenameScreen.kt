package com.fileflow.app.ui.rename

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationProgress
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.prefs.RenameConvention
import com.fileflow.app.data.prefs.SettingsRepository
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.data.repo.RenameSummary
import com.fileflow.app.domain.rename.Confidence
import com.fileflow.app.domain.rename.RenameProposal
import com.fileflow.app.domain.rename.RenameReason
import com.fileflow.app.domain.rename.SmartRenameEngine
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.ProgressPanel
import com.fileflow.app.ui.components.SectionCard
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

data class RenameState(
    val scanning: Boolean = false,
    val scanned: Int = 0,
    val proposals: List<RenameProposal> = emptyList(),
    val accepted: Set<String> = emptySet(),
    val convention: RenameConvention = RenameConvention.KEEP,
    val datePrefix: Boolean = false,
    val working: Boolean = false,
    val lastGroupId: String? = null,
    val hasScanned: Boolean = false,
)

@HiltViewModel
class SmartRenameViewModel @Inject constructor(
    private val engine: SmartRenameEngine,
    private val actions: FileActionsRepository,
    private val settings: SettingsRepository,
    private val operationRunner: OperationRunner,
) : ViewModel() {

    private val _state = MutableStateFlow(RenameState())
    val state: StateFlow<RenameState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            settings.settings.collect { prefs ->
                _state.update {
                    it.copy(
                        convention = prefs.renameConvention,
                        datePrefix = prefs.renameDatePrefix,
                    )
                }
            }
        }
    }

    fun setConvention(convention: RenameConvention) {
        _state.update { it.copy(convention = convention) }
        viewModelScope.launch { settings.setRenameConvention(convention) }
        if (_state.value.hasScanned) scan()
    }

    fun setDatePrefix(enabled: Boolean) {
        _state.update { it.copy(datePrefix = enabled) }
        viewModelScope.launch { settings.setRenameDatePrefix(enabled) }
        if (_state.value.hasScanned) scan()
    }

    fun scan() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update {
                it.copy(scanning = true, scanned = 0, proposals = emptyList(), accepted = emptySet())
            }
            try {
                val proposals = engine.scan(
                    convention = _state.value.convention,
                    datePrefix = _state.value.datePrefix,
                    onProgress = { seen, found ->
                        _state.update { it.copy(scanned = seen, proposals = it.proposals) }
                    },
                )
                _state.update {
                    it.copy(
                        scanning = false,
                        proposals = proposals,
                        accepted = proposals.map { p -> p.path }.toSet(),
                        hasScanned = true,
                    )
                }
            } catch (_: CancellationException) {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(scanning = false) }
    }

    fun toggle(path: String) {
        _state.update { current ->
            val accepted = if (path in current.accepted) current.accepted - path
            else current.accepted + path
            current.copy(accepted = accepted)
        }
    }

    fun selectAll() {
        _state.update { it.copy(accepted = it.proposals.map { p -> p.path }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(accepted = emptySet()) }
    }

    fun apply() {
        val chosen = _state.value.proposals.filter { it.path in _state.value.accepted }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                var summary: RenameSummary? = null
                operationRunner.run(OperationKind.RENAME) { progress ->
                    summary = actions.applyRenames(
                        chosen.map { it.path to it.proposedName },
                    ) { done, total ->
                        progress(
                            OperationProgress(
                                current = done,
                                total = total,
                                currentName = chosen.getOrNull(done)?.proposedName.orEmpty(),
                                bytesDone = 0L,
                                bytesTotal = 0L,
                            )
                        )
                    }
                }
                val result = summary
                if (result != null) {
                    _state.update {
                        it.copy(
                            working = false,
                            lastGroupId = result.groupId,
                            proposals = it.proposals.filterNot { p -> p.path in it.accepted },
                            accepted = emptySet(),
                        )
                    }
                    _messages.send(
                        if (result.failed > 0) {
                            "${result.succeeded} renamed, ${result.failed} failed"
                        } else {
                            "${result.succeeded} files renamed"
                        }
                    )
                } else {
                    _state.update { it.copy(working = false) }
                }
            } catch (_: CancellationException) {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun undo() {
        val group = _state.value.lastGroupId ?: return
        viewModelScope.launch {
            val result = actions.undoGroup(group)
            _state.update { it.copy(lastGroupId = null) }
            _messages.send("${result.restored} names restored")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartRenameScreen(
    onBack: () -> Unit,
    viewModel: SmartRenameViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rename_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (state.lastGroupId != null) {
                        IconButton(onClick = viewModel::undo) {
                            Icon(Icons.Filled.Undo, contentDescription = stringResource(R.string.cd_undo))
                        }
                    }
                    IconButton(onClick = viewModel::scan) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                    }
                },
            )
        },
        bottomBar = {
            if (state.accepted.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::clearSelection,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.action_deselect_all))
                        }
                        Button(
                            onClick = viewModel::apply,
                            modifier = Modifier.weight(1f),
                            enabled = !state.working,
                        ) {
                            Text(stringResource(R.string.rename_apply, state.accepted.size))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.rename_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.rename_convention),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RenameConvention.entries.forEach { convention ->
                            FilterChip(
                                selected = state.convention == convention,
                                onClick = { viewModel.setConvention(convention) },
                                label = { Text(labelForConvention(convention)) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.rename_conv_date_prefix),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = state.datePrefix,
                            onCheckedChange = viewModel::setDatePrefix,
                        )
                    }
                }

                when {
                    state.scanning -> ProgressPanel(
                        title = stringResource(R.string.rename_scanning),
                        detail = stringResource(R.string.analyzer_files, state.scanned),
                        fraction = null,
                        onCancel = viewModel::stop,
                        modifier = Modifier.padding(16.dp),
                    )

                    state.proposals.isEmpty() -> EmptyState(
                        icon = Icons.Filled.AutoAwesome,
                        title = if (state.hasScanned) {
                            stringResource(R.string.rename_none)
                        } else {
                            stringResource(R.string.rename_scan)
                        },
                        body = stringResource(R.string.rename_none_hint),
                        action = {
                            Button(onClick = viewModel::scan) {
                                Text(stringResource(R.string.rename_scan))
                            }
                        },
                    )

                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.rename_proposals,
                                        state.proposals.size,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedButton(onClick = viewModel::selectAll) {
                                    Text(stringResource(R.string.action_select_all))
                                }
                            }
                        }
                        items(state.proposals, key = { it.path }) { proposal ->
                            ProposalCard(
                                proposal = proposal,
                                accepted = proposal.path in state.accepted,
                                onToggle = { viewModel.toggle(proposal.path) },
                            )
                        }
                        item { Spacer(Modifier.size(8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: RenameProposal,
    accepted: Boolean,
    onToggle: () -> Unit,
) {
    SectionCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colorForConfidence(proposal.confidence)),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.rename_old),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = Formatters.bytes(proposal.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Switch(checked = accepted, onCheckedChange = { onToggle() })
            }
            Text(
                text = proposal.currentName,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = proposal.proposedName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                proposal.reasons.take(3).forEach { reason ->
                    Text(
                        text = labelForReason(reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = proposal.path.substringBeforeLast('/'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun colorForConfidence(confidence: Confidence) = when (confidence) {
    Confidence.HIGH -> MaterialTheme.colorScheme.primary
    Confidence.MEDIUM -> MaterialTheme.colorScheme.tertiary
    Confidence.LOW -> MaterialTheme.colorScheme.outline
}

@Composable
private fun labelForConvention(convention: RenameConvention): String = when (convention) {
    RenameConvention.KEEP -> stringResource(R.string.rename_conv_keep)
    RenameConvention.NATURAL -> stringResource(R.string.rename_conv_natural)
    RenameConvention.TITLE_CASE -> stringResource(R.string.rename_conv_title)
    RenameConvention.KEBAB -> stringResource(R.string.rename_conv_kebab)
    RenameConvention.SNAKE -> stringResource(R.string.rename_conv_snake)
}

@Composable
private fun labelForReason(reason: RenameReason): String = when (reason) {
    RenameReason.JUNK -> stringResource(R.string.rename_reason_junk)
    RenameReason.DUPLICATE_SUFFIX -> stringResource(R.string.rename_reason_duplicate_suffix)
    RenameReason.SPACING -> stringResource(R.string.rename_reason_spacing)
    RenameReason.DATE -> stringResource(R.string.rename_reason_date)
    RenameReason.EXTENSION -> stringResource(R.string.rename_reason_extension)
    RenameReason.RANDOM -> stringResource(R.string.rename_reason_hash)
    RenameReason.STYLE -> stringResource(R.string.rename_reason_case)
    RenameReason.UNIQUENESS -> stringResource(R.string.rename_reason_uniqueness)
}