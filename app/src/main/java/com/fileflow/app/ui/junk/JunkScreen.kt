package com.fileflow.app.ui.junk

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileflow.app.ui.util.LocalUserSettings
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationReport
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.domain.scan.JunkFinder
import com.fileflow.app.domain.scan.JunkKind
import com.fileflow.app.domain.scan.JunkReport
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.ProgressPanel
import com.fileflow.app.ui.components.SectionCard
import com.fileflow.app.ui.components.SelectionAction
import com.fileflow.app.ui.components.SelectionActionBar
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage
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

@androidx.compose.runtime.Immutable
data class JunkState(
    val scanning: Boolean = true,
    val scanned: Int = 0,
    val report: JunkReport? = null,
    val accepted: Set<JunkKind> = emptySet(),
    val expanded: JunkKind? = null,
    val working: Boolean = false,
    /** Derived when the accepted set changes, never summed during a frame. */
    val selectedCount: Int = 0,
    val selectedBytes: Long = 0,
) {
    fun withAccepted(report: JunkReport?, next: Set<JunkKind>): JunkState {
        val items = report?.buckets?.filter { it.kind in next }?.flatMap { it.items }.orEmpty()
        return copy(
            report = report,
            accepted = next,
            selectedCount = items.size,
            selectedBytes = items.sumOf { it.sizeBytes },
        )
    }

    /** Everything currently accepted, in one place so the UI never re-derives it. */
    fun acceptedItems(): List<com.fileflow.app.domain.Candidate> =
        report?.buckets?.filter { it.kind in accepted }?.flatMap { it.items }.orEmpty()
}

@HiltViewModel
class JunkViewModel @Inject constructor(
    private val finder: JunkFinder,
    private val actions: FileActionsRepository,
    private val operationRunner: OperationRunner,
) : ViewModel() {

    private val _state = MutableStateFlow(JunkState())
    val state: StateFlow<JunkState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var job: Job? = null

    init {
        scan()
    }

    fun scan() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update {
                it.copy(scanning = true, scanned = 0, report = null).withAccepted(null, emptySet())
            }
            try {
                val report = finder.scan { seen -> _state.update { it.copy(scanned = seen) } }
                // Empty files are off by default: a zero-byte file is usually
                // junk, but occasionally it is a placeholder someone needs.
                val defaults = report.buckets
                    .filter { it.kind != JunkKind.EMPTY_FILES }
                    .map { it.kind }
                    .toSet()
                _state.update {
                    it.copy(scanning = false).withAccepted(
                        report,
                        if (defaults.isEmpty()) report.buckets.map { b -> b.kind }.toSet()
                        else defaults,
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

    fun toggle(kind: JunkKind) {
        _state.update { current ->
            val next = if (kind in current.accepted) current.accepted - kind
            else current.accepted + kind
            current.withAccepted(current.report, next)
        }
    }

    fun expand(kind: JunkKind?) {
        _state.update { it.copy(expanded = if (it.expanded == kind) null else kind) }
    }

    fun clean() {
        val paths = _state.value.acceptedItems().map { it.path }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                var report: OperationReport? = null
                operationRunner.run(OperationKind.TRASH) { progress ->
                    report = actions.delete(paths, permanent = false, progress)
                }
                _state.update { it.copy(working = false) }
                val done = report
                if (done != null) {
                    _messages.send(
                        UiMessage.Done(
                            "${done.succeeded} items cleaned",
                            done.groupId.takeIf { done.succeeded > 0 },
                        )
                    )
                }
                scan()
            } catch (_: CancellationException) {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun undo(groupId: String) {
        viewModelScope.launch {
            val result = actions.undoGroup(groupId)
            _messages.send(UiMessage.Info("${result.restored} changes reverted"))
            scan()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JunkScreen(
    onBack: () -> Unit,
    viewModel: JunkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val settings = LocalUserSettings.current
    val trashDays = settings.trashRetentionDays
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { group -> viewModel.undo(group) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.junk_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::scan) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                    }
                },
            )
        },
        bottomBar = {
            val report = state.report
            if (report != null && !report.isEmpty) {
                SelectionActionBar(
                    title = stringResource(
                        R.string.junk_reclaimable,
                        Formatters.bytes(state.selectedBytes),
                    ),
                    subtitle = stringResource(R.string.junk_safe_note),
                    actions = listOf(
                        SelectionAction(
                            icon = Icons.Filled.CleaningServices,
                            label = stringResource(R.string.junk_clean_short),
                            onClick = {
                                if (settings.confirmDelete) confirm = true
                                else viewModel.clean()
                            },
                        ),
                    ),
                )
            }
        },
    ) { padding ->
        // Read the report once, here, and let the `when` smart-cast it.
        //
        // The item lambda below belongs to a LazyColumn, and Compose re-evaluates
        // it from a snapshot observer as soon as the state it reads changes —
        // which is *before* the enclosing `when` gets a chance to re-decide its
        // branch. `state.report!!` therefore blew up the instant a clean finished
        // and reset the report to null, which is exactly the crash that happened
        // after "Clean now". Capturing the list itself makes the lambda incapable
        // of seeing a null.
        val report = state.report
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.scanning -> ProgressPanel(
                    title = stringResource(R.string.junk_scanning),
                    detail = stringResource(R.string.analyzer_files, state.scanned),
                    fraction = null,
                    onCancel = viewModel::stop,
                    modifier = Modifier.padding(16.dp),
                )

                report == null || report.isEmpty -> EmptyState(
                    icon = Icons.Filled.CleaningServices,
                    title = stringResource(R.string.junk_none),
                    body = stringResource(R.string.junk_none_hint),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(report.buckets, key = { it.kind.name }) { bucket ->
                        SectionCard(modifier = Modifier.animateItem()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = labelFor(bucket.kind),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            text = pluralStringResource(
                                                R.plurals.items_and_size,
                                                bucket.items.size,
                                                bucket.items.size,
                                                Formatters.bytes(bucket.bytes),
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = bucket.kind in state.accepted,
                                        onCheckedChange = { viewModel.toggle(bucket.kind) },
                                    )
                                }
                                Text(
                                    text = if (state.expanded == bucket.kind) {
                                        stringResource(R.string.action_show_less)
                                    } else {
                                        stringResource(R.string.action_show_more)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { viewModel.expand(bucket.kind) }
                                        .padding(vertical = 4.dp, horizontal = 2.dp),
                                )
                                if (state.expanded == bucket.kind) {
                                    Column(modifier = Modifier.heightIn(max = 220.dp)) {
                                        bucket.items.take(40).forEach { item ->
                                            Text(
                                                text = item.path,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.size(4.dp)) }
                }
            }
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_title, state.selectedCount, state.selectedCount),
            body = stringResource(R.string.delete_body, trashDays),
            confirmLabel = stringResource(R.string.delete_move_to_trash),
            onConfirm = {
                viewModel.clean()
                confirm = false
            },
            onDismiss = { confirm = false },
            destructive = true,
        )
    }
}

@Composable
private fun labelFor(kind: JunkKind): String = when (kind) {
    JunkKind.EMPTY_FILES -> stringResource(R.string.junk_empty_files)
    JunkKind.EMPTY_FOLDERS -> stringResource(R.string.junk_empty_folders)
    JunkKind.TEMP -> stringResource(R.string.junk_temp)
    JunkKind.LOGS -> stringResource(R.string.junk_logs)
    JunkKind.OLD_APK -> stringResource(R.string.junk_old_apk)
    JunkKind.THUMBNAILS -> stringResource(R.string.junk_thumbnails)
}