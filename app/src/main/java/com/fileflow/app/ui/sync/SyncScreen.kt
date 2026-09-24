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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.Paths
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.domain.compare.DiffRow
import com.fileflow.app.domain.sync.SyncDirection
import com.fileflow.app.domain.sync.SyncInterval
import com.fileflow.app.domain.sync.SyncJob
import com.fileflow.app.domain.sync.SyncJobStore
import com.fileflow.app.domain.sync.SyncMode
import com.fileflow.app.domain.sync.SyncPlan
import com.fileflow.app.domain.sync.SyncRunOutcome
import com.fileflow.app.domain.sync.SyncRunner
import com.fileflow.app.domain.sync.SyncScheduler
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.SectionHeader
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
data class SyncState(
    val left: String = "",
    val right: String = "",
    val direction: SyncDirection = SyncDirection.LEFT_TO_RIGHT,
    val mode: SyncMode = SyncMode.INCREMENTAL,
    val compareContent: Boolean = false,
    val interval: SyncInterval = SyncInterval.DAILY,
    val jobs: List<SyncJob> = emptyList(),
    val plan: SyncPlan? = null,
    val working: Boolean = false,
    val jobRunning: String? = null,
    val progress: String? = null,
    val scanned: Int = 0,
) {
    val canPreview: Boolean get() = left.isNotBlank() && right.isNotBlank() && left != right

    /** True when the folder pair on screen is already saved and enabled. */
    val savedJob: SyncJob? get() = jobs.firstOrNull { it.left == left && it.right == right }
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val browseRepository: BrowseRepository,
    private val runner: SyncRunner,
    private val store: SyncJobStore,
    private val scheduler: SyncScheduler,
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
        viewModelScope.launch {
            store.jobs.collect { jobs -> _state.update { it.copy(jobs = jobs) } }
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

    fun setInterval(interval: SyncInterval) = _state.update { it.copy(interval = interval) }

    /** Compares and builds the plan, without touching anything. */
    fun preview() {
        val current = _state.value
        if (!current.canPreview || current.working) return
        viewModelScope.launch {
            _state.update { it.copy(working = true, plan = null, progress = null) }
            val result = runCatching {
                runner.plan(
                    left = current.left,
                    right = current.right,
                    direction = current.direction,
                    mode = current.mode,
                    compareContent = current.compareContent,
                    onScanned = { scanned -> _state.update { it.copy(scanned = scanned) } },
                )
            }
            result.fold(
                onSuccess = { plan ->
                    _state.update { it.copy(working = false, plan = plan) }
                },
                onFailure = { error ->
                    _state.update { it.copy(working = false) }
                    _messages.send(UiMessage.Info(error.message ?: "Could not compare"))
                },
            )
        }
    }

    /**
     * Runs the plan the user is looking at. The same runner the schedule uses,
     * so what happens here and what happens at 3 a.m. cannot diverge.
     */
    fun run() {
        val current = _state.value
        if (current.working) return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            val outcome = runner.run(
                left = current.left,
                right = current.right,
                direction = current.direction,
                mode = current.mode,
                compareContent = current.compareContent,
                onProgress = { line -> _state.update { it.copy(progress = line) } },
            )
            browseRepository.invalidateListing(current.left)
            browseRepository.invalidateListing(current.right)
            _state.update { it.copy(working = false, progress = null) }
            _messages.send(UiMessage.Info(describe(outcome)))
            preview()
        }
    }

    // ------------------------------------------------------------- schedules

    /** Saves the pair on screen so it can run without anyone watching. */
    fun saveJob() {
        val current = _state.value
        if (!current.canPreview) return
        viewModelScope.launch {
            val existing = current.savedJob
            val job = SyncJob(
                id = existing?.id ?: "sync-${System.currentTimeMillis()}",
                left = current.left,
                right = current.right,
                direction = current.direction,
                mode = current.mode,
                interval = current.interval,
                compareContent = current.compareContent,
                enabled = true,
                lastRunAt = existing?.lastRunAt ?: 0L,
                lastCopied = existing?.lastCopied ?: 0,
                lastDeleted = existing?.lastDeleted ?: 0,
                lastConflicts = existing?.lastConflicts ?: 0,
                lastFailed = existing?.lastFailed ?: 0,
                lastError = existing?.lastError,
            )
            store.save(job)
            scheduler.sync(job)
            _messages.send(
                UiMessage.Info(
                    if (existing != null) "Schedule updated" else "Schedule saved"
                )
            )
        }
    }

    fun setJobEnabled(job: SyncJob, enabled: Boolean) {
        viewModelScope.launch {
            val next = job.copy(enabled = enabled)
            store.save(next)
            scheduler.sync(next)
        }
    }

    fun deleteJob(job: SyncJob) {
        viewModelScope.launch {
            store.remove(job.id)
            scheduler.cancel(job.id)
        }
    }

    /** Pulls a saved job into the editor, so it can be changed and re-saved. */
    fun editJob(job: SyncJob) {
        _state.update {
            it.copy(
                left = job.left,
                right = job.right,
                direction = job.direction,
                mode = job.mode,
                compareContent = job.compareContent,
                interval = job.interval,
                plan = null,
            )
        }
        preview()
    }

    fun runJobNow(job: SyncJob) {
        if (_state.value.jobRunning != null) return
        viewModelScope.launch {
            _state.update { it.copy(jobRunning = job.id) }
            val outcome = runCatching {
                runner.run(
                    left = job.left,
                    right = job.right,
                    direction = job.direction,
                    mode = job.mode,
                    compareContent = job.compareContent,
                )
            }.getOrElse { error ->
                SyncRunOutcome(error = error.message ?: "the sync failed")
            }
            store.recordResult(job.id, outcome)
            browseRepository.invalidateListing(job.left)
            browseRepository.invalidateListing(job.right)
            _state.update { it.copy(jobRunning = null) }
            _messages.send(UiMessage.Info("${job.pair}: ${describe(outcome)}"))
        }
    }

    private fun describe(outcome: SyncRunOutcome): String = buildString {
        if (outcome.error != null) {
            append(outcome.error)
        } else {
            append("${outcome.copied} copied")
            if (outcome.trashed > 0) append(", ${outcome.trashed} moved to trash")
            if (outcome.failed > 0) append(", ${outcome.failed} failed")
        }
        if (outcome.conflicts > 0) append(" · ${outcome.conflicts} kept as conflicts")
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
    var showSchedule by remember { mutableStateOf(false) }

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
                    IconButton(onClick = { showSchedule = true }) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = stringResource(R.string.sync_schedule),
                            tint = if (state.jobs.isNotEmpty()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
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

    if (showSchedule) {
        SyncScheduleSheet(
            state = state,
            onDismiss = { showSchedule = false },
            onInterval = viewModel::setInterval,
            onSave = viewModel::saveJob,
            onToggle = viewModel::setJobEnabled,
            onRunNow = viewModel::runJobNow,
            onEdit = { job ->
                viewModel.editJob(job)
                showSchedule = false
            },
            onDelete = viewModel::deleteJob,
        )
    }
}

/**
 * The saved pairs, and the schedule for the pair currently on screen.
 *
 * Kept out of the main column on purpose: the sync screen is a preview the user
 * reads before approving, and a list of schedules wedged underneath it would
 * compete with the plan for the same attention. As a sheet it is one tap away
 * and does not push the confirmation off the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncScheduleSheet(
    state: SyncState,
    onDismiss: () -> Unit,
    onInterval: (SyncInterval) -> Unit,
    onSave: () -> Unit,
    onToggle: (SyncJob, Boolean) -> Unit,
    onRunNow: (SyncJob) -> Unit,
    onEdit: (SyncJob) -> Unit,
    onDelete: (SyncJob) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var deleteTarget by remember { mutableStateOf<SyncJob?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.sync_schedule),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (state.canPreview) {
                Text(
                    text = stringResource(
                        R.string.sync_schedule_pair,
                        Paths.nameOf(state.left),
                        Paths.nameOf(state.right),
                        labelFor(state.direction),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SyncInterval.entries.forEach { interval ->
                        FilterChip(
                            selected = state.interval == interval,
                            onClick = { onInterval(interval) },
                            label = { Text(labelFor(interval)) },
                        )
                    }
                }
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (state.savedJob != null) {
                                R.string.sync_schedule_update
                            } else {
                                R.string.sync_schedule_save
                            }
                        )
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.sync_schedule_pick_two),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.jobs.isEmpty()) {
                Text(
                    text = stringResource(R.string.sync_schedule_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                state.jobs.forEach { job ->
                    SyncJobRow(
                        job = job,
                        running = state.jobRunning == job.id,
                        onToggle = { enabled -> onToggle(job, enabled) },
                        onRunNow = { onRunNow(job) },
                        onEdit = { onEdit(job) },
                        onDelete = { deleteTarget = job },
                    )
                }
            }
        }
    }

    deleteTarget?.let { job ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.sync_schedule_delete)) },
            text = { Text(job.pair) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(job)
                    deleteTarget = null
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SyncJobRow(
    job: SyncJob,
    running: Boolean,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = job.pair,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(labelFor(job.interval))
                        append(" · ")
                        append(labelFor(job.direction))
                        if (job.mode == SyncMode.MIRROR) append(" · mirror")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Switch(checked = job.enabled, onCheckedChange = onToggle)
            }
        }
        Text(
            text = if (job.lastRunAt == 0L) {
                stringResource(R.string.sync_job_never)
            } else {
                "${Formatters.relative(job.lastRunAt)} · ${job.lastSummary}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (job.lastError != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRunNow, enabled = !running) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sync_run_now))
            }
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.sync_edit))
            }
            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
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

@Composable
private fun labelFor(interval: SyncInterval): String = stringResource(
    when (interval) {
        SyncInterval.SIX_HOURS -> R.string.sync_every_six_hours
        SyncInterval.DAILY -> R.string.sync_every_day
        SyncInterval.WEEKLY -> R.string.sync_every_week
    }
)
