package com.fileflow.app.ui.changes

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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.OutcomeMessages
import com.fileflow.app.data.db.JournalGroupSummary
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.LoadingState
import com.fileflow.app.ui.components.SectionCard
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class ChangesState(
    val groups: List<JournalGroupSummary> = emptyList(),
    val loading: Boolean = true,
    val undoing: String? = null,
)

@HiltViewModel
class RecentChangesViewModel @Inject constructor(
    private val actions: FileActionsRepository,
    private val outcome: OutcomeMessages,
) : ViewModel() {

    private val _state = MutableStateFlow(ChangesState())
    val state: StateFlow<ChangesState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            actions.observeRecentChanges().collect { groups ->
                _state.update { it.copy(groups = groups, loading = false) }
            }
        }
    }

    fun undo(group: JournalGroupSummary) {
        viewModelScope.launch {
            _state.update { it.copy(undoing = group.groupId) }
            val result = actions.undoGroup(group.groupId)
            _state.update { it.copy(undoing = null) }
            // Undo is where a partial result has to be loudest: the user
            // believes the change is gone, and for the ones that failed it is
            // not. They stay reversible and stay listed.
            _messages.send(
                UiMessage.Info(outcome.reversed(result.restored, result.failed, result.skipped))
            )
        }
    }
}

/**
 * The journal, made visible.
 *
 * Every change the app makes is written to an undo journal, but that journal was
 * only ever read by the snackbar — so a wrong deletion was recoverable for a few
 * seconds and then, in practice, never. This screen is the reason to keep it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentChangesScreen(
    onBack: () -> Unit,
    viewModel: RecentChangesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbar.showMessage(context, message) {} }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.changes_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                // The first frame has an empty list, which used to render as an
                // empty LazyColumn — a blank screen rather than a loading one.
                state.loading && state.groups.isEmpty() -> LoadingState(
                    label = stringResource(R.string.state_loading),
                )

                state.groups.isEmpty() -> EmptyState(
                    icon = Icons.Filled.History,
                    title = stringResource(R.string.changes_empty),
                    body = stringResource(R.string.changes_empty_hint),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.groups, key = { it.groupId }) { group ->
                        ChangeCard(
                            group = group,
                            undoing = state.undoing == group.groupId,
                            onUndo = { viewModel.undo(group) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangeCard(
    group: JournalGroupSummary,
    undoing: Boolean,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summaryLine(group),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "${Formatters.relative(group.startedAt)} · ${
                            Formatters.bytes(group.bytes)
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (group.allUndone) {
                    Text(
                        text = stringResource(R.string.changes_undone),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (group.moves > 0) KindChip(Icons.AutoMirrored.Filled.DriveFileMove, group.moves)
                if (group.copies > 0) KindChip(Icons.Filled.ContentCopy, group.copies)
                if (group.trashes > 0) KindChip(Icons.Filled.DeleteSweep, group.trashes)
                if (group.renames > 0) KindChip(Icons.Filled.Edit, group.renames)
            }

            if (group.canUndo) {
                Button(
                    onClick = onUndo,
                    enabled = !undoing,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.changes_undo))
                }
            }
        }
    }
}

@Composable
private fun KindChip(icon: ImageVector, count: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun summaryLine(group: JournalGroupSummary): String = pluralStringResource(
    R.plurals.changes_files,
    group.entryCount,
    group.entryCount,
)
