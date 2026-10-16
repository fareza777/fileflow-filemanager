package com.fileflow.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileflow.app.ui.util.LocalUserSettings
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationReport
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.domain.timeline.MonthData
import com.fileflow.app.domain.timeline.TimelineRepository
import com.fileflow.app.data.repo.MetadataCleanup
import com.fileflow.app.ui.components.BulkHandlers
import com.fileflow.app.ui.components.BulkMoreSheet
import com.fileflow.app.ui.components.bulkBarActions
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileRow
import com.fileflow.app.ui.components.FileThumb
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.ProgressPanel
import com.fileflow.app.ui.components.SelectionAction
import com.fileflow.app.ui.components.SelectionActionBar
import com.fileflow.app.ui.util.FileIntents
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
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
data class CalendarState(
    val year: Int = LocalDate.now().year,
    val month: Int = LocalDate.now().monthValue,
    val loading: Boolean = true,
    val deepScanning: Boolean = false,
    val scanned: Int = 0,
    val matched: Int = 0,
    val data: MonthData? = null,
    val selectedDay: Int? = null,
    val selection: Set<String> = emptySet(),
    /** Derived when the day or selection changes, not per frame. */
    val dayFiles: List<FileItem> = emptyList(),
    val selectedItems: List<FileItem> = emptyList(),
    val gridMode: Boolean = true,
) {
    fun withDay(day: Int?, files: List<FileItem>): CalendarState =
        copy(selectedDay = day, dayFiles = files, selection = emptySet(), selectedItems = emptyList())

    fun withSelection(next: Set<String>): CalendarState = copy(
        selection = next,
        selectedItems = dayFiles.filter { it.path in next },
    )
}

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: TimelineRepository,
    private val actions: FileActionsRepository,
    private val browseRepository: com.fileflow.app.data.repo.BrowseRepository,
    private val operationRunner: com.fileflow.app.data.repo.OperationRunner,
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        load()
    }

    fun defaultRoot(): String = browseRepository.primaryRoot()

    fun load() {
        val current = _state.value
        viewModelScope.launch {
            _state.update { it.copy(loading = true, data = null).withSelection(emptySet()) }
            val data = repository.month(current.year, current.month)
            _state.update { state ->
                val dayToSelect = state.selectedDay
                    ?: data.filesByDay.keys.maxOrNull()
                    ?: LocalDate.now().dayOfMonth
                val files = data.day(dayToSelect)
                state.copy(
                    loading = false,
                    data = data,
                    gridMode = if (files.isEmpty()) state.gridMode else prefersGrid(files),
                ).withDay(dayToSelect, files)
            }
        }
    }

    fun previousMonth() {
        val (year, month) = shift(-1)
        _state.update {
            it.copy(year = year, month = month, selectedDay = null).withSelection(emptySet())
        }
        load()
    }

    fun nextMonth() {
        val (year, month) = shift(1)
        _state.update {
            it.copy(year = year, month = month, selectedDay = null).withSelection(emptySet())
        }
        load()
    }

    fun goToToday() {
        val today = LocalDate.now()
        _state.update {
            it.copy(year = today.year, month = today.monthValue, selectedDay = today.dayOfMonth)
                .withSelection(emptySet())
        }
        load()
    }

    fun selectDay(day: Int) {
        val files = _state.value.data?.day(day).orEmpty()
        _state.update {
            it.withDay(day, files).copy(
                gridMode = if (files.isEmpty()) it.gridMode else prefersGrid(files),
            )
        }
    }

    fun toggleViewMode() {
        _state.update { it.copy(gridMode = !it.gridMode) }
    }

    fun deepScan() {
        val current = _state.value
        viewModelScope.launch {
            _state.update { it.copy(deepScanning = true, scanned = 0, matched = 0) }
            val data = repository.deepScan(current.year, current.month) { scanned, matched ->
                _state.update { it.copy(scanned = scanned, matched = matched) }
            }
            _state.update { state ->
                val day = state.selectedDay?.takeIf { data.filesByDay.containsKey(it) }
                    ?: data.filesByDay.keys.maxOrNull()
                state.copy(
                    deepScanning = false,
                    data = data,
                    selectedDay = day,
                    gridMode = day?.let { prefersGrid(data.day(it)) } ?: state.gridMode,
                )
            }
        }
    }

    fun stopDeepScan() {
        _state.update { it.copy(deepScanning = false) }
    }

    fun toggleSelection(path: String) {
        _state.update { current ->
            val next = if (path in current.selection) current.selection - path
            else current.selection + path
            current.withSelection(next)
        }
    }

    fun startSelection(path: String) {
        _state.update { it.withSelection(it.selection + path) }
    }

    fun selectAllOnDay() {
        _state.update { it.withSelection(it.dayFiles.map { file -> file.path }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.withSelection(emptySet()) }
    }

    fun deleteSelected() {
        val paths = _state.value.selectedItems.map { it.path }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            try {
                var report: OperationReport? = null
                operationRunner.run(OperationKind.TRASH) { progress ->
                    report = actions.delete(paths, permanent = false, progress)
                }
                _state.update { it.withSelection(emptySet()) }
                val done = report
                if (done != null) {
                    _messages.send(
                        UiMessage.Done(
                            "${done.succeeded} moved to trash",
                            done.groupId.takeIf { done.succeeded > 0 },
                        )
                    )
                }
                load()
            } catch (_: CancellationException) {
                // The user pressed Stop on the global bar, or a newer
                // operation superseded this one. Nothing to undo here; the
                // runner already cleared the indicator.
            }
        }
    }

    fun copySelectedTo(destination: String) {
        val paths = _state.value.selectedItems.map { it.path }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            var report: OperationReport? = null
            try {
                operationRunner.run(OperationKind.COPY) { progress ->
                    report = actions.copy(paths, destination, ConflictPolicy.RENAME, progress)
                }
            } catch (_: CancellationException) {
                // Stopped from the root bar.
            }
            _state.update { it.withSelection(emptySet()) }
            report?.let { done -> _messages.send(UiMessage.Info("${done.succeeded} copied")) }
        }
    }

    /** The same cleanup every other list offers, from the same place. */
    fun cleanMetadata() {
        val paths = _state.value.selectedItems.map { it.path }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            var cleanup: MetadataCleanup? = null
            try {
                operationRunner.run(OperationKind.CLEAN) { progress ->
                    cleanup = actions.cleanMetadata(paths, progress)
                }
            } catch (_: CancellationException) {
                return@launch
            }
            _state.update { it.withSelection(emptySet()) }
            cleanup?.let { result ->
                _messages.send(
                    UiMessage.Info(
                        if (result.cleaned == 0 && result.failed == 0) {
                            "Nothing to remove"
                        } else {
                            "Cleaned ${result.cleaned} files · " +
                                "${Formatters.bytes(result.savedBytes)} removed"
                        }
                    )
                )
            }
            load()
        }
    }

    fun starSelection() {
        val items = _state.value.selectedItems
        if (items.isEmpty()) return
        viewModelScope.launch {
            val allStarred = items.all { actions.isStarred(it.path) }
            items.forEach { actions.setStar(it, starred = !allStarred) }
            _state.update { it.withSelection(emptySet()) }
            _messages.send(UiMessage.Info(if (allStarred) "Star removed" else "Starred ${items.size}"))
        }
    }

    fun moveSelectedTo(destination: String) {
        val paths = _state.value.selectedItems.map { it.path }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            try {
                var report: OperationReport? = null
                operationRunner.run(OperationKind.MOVE) { progress ->
                    report = actions.move(paths, destination, ConflictPolicy.RENAME, progress)
                }
                _state.update { it.withSelection(emptySet()) }
                val done = report
                if (done != null) {
                    _messages.send(
                        UiMessage.Done(
                            "${done.succeeded} moved",
                            done.groupId.takeIf { done.succeeded > 0 },
                        )
                    )
                }
                load()
            } catch (_: CancellationException) {
                // See deleteSelected: cancellation is handled by the runner.
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

    /**
     * A day full of photos is a gallery; a day full of documents is a list.
     */
    private fun prefersGrid(files: List<FileItem>): Boolean {
        if (files.isEmpty()) return false
        val visual = files.count {
            it.category == com.fileflow.app.core.model.FileCategory.IMAGES ||
                it.category == com.fileflow.app.core.model.FileCategory.VIDEOS
        }
        return visual * 2 >= files.size
    }

    private fun shift(delta: Long): Pair<Int, Int> {
        val current = YearMonth.of(_state.value.year, _state.value.month).plusMonths(delta)
        return current.year to current.monthValue
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onConvert: (List<String>) -> Unit,
    onBatchRename: (List<String>) -> Unit,
    onCompress: (List<String>) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val settings = LocalUserSettings.current
    val trashDays = settings.trashRetentionDays
    val haptics = LocalHapticFeedback.current
    var confirmDelete by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var showMore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { group -> viewModel.undo(group) }
        }
    }

    val today = LocalDate.now()
    val dayFiles = state.dayFiles

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calendar_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::deepScan) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                    }
                },
            )
        },
        bottomBar = {
            if (state.selection.isNotEmpty()) {
                val handlers = BulkHandlers(
                    onShare = {
                        FileIntents.share(context, state.selectedItems)
                        viewModel.clearSelection()
                    },
                    onMove = { picker = Picker.MOVE },
                    onCopy = {
                        showMore = false
                        picker = Picker.COPY
                    },
                    onConvert = { onConvert(state.selection.toList()) },
                    onBatchRename = { onBatchRename(state.selection.toList()) },
                    onCompress = { onCompress(state.selection.toList()) },
                    onCleanMetadata = {
                        showMore = false
                        viewModel.cleanMetadata()
                    },
                    onStar = {
                        showMore = false
                        viewModel.starSelection()
                    },
                    onDelete = {
                        if (settings.confirmDelete) confirmDelete = true
                        else viewModel.deleteSelected()
                    },
                    onMore = { showMore = true },
                    transferFolder = settings.transferFolder,
                    onMoveToTransfer = if (settings.transferFolder.isNotBlank()) {
                        { viewModel.moveSelectedTo(settings.transferFolder) }
                    } else {
                        null
                    },
                    onSelectAll = viewModel::selectAllOnDay,
                    onDeselectAll = viewModel::clearSelection,
                )
                SelectionActionBar(
                    title = pluralStringResource(
                        R.plurals.item_count,
                        state.selection.size,
                        state.selection.size,
                    ),
                    subtitle = Formatters.bytes(state.selectedItems.sumOf { it.sizeBytes }),
                    actions = bulkBarActions(handlers),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            MonthHeader(
                year = state.year,
                month = state.month,
                summary = state.data?.let {
                    stringResource(
                        R.string.calendar_month_summary,
                        pluralStringResource(
                            R.plurals.file_count,
                            it.totalFiles,
                            it.totalFiles,
                        ),
                        Formatters.bytes(it.totalBytes),
                    )
                },
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
                onToday = viewModel::goToToday,
            )

            MonthGrid(
                year = state.year,
                month = state.month,
                data = state.data,
                selectedDay = state.selectedDay,
                todayDay = if (today.year == state.year && today.monthValue == state.month) {
                    today.dayOfMonth
                } else {
                    null
                },
                onSelect = viewModel::selectDay,
            )

            Spacer(Modifier.height(8.dp))

            DayHeader(
                day = state.selectedDay,
                year = state.year,
                month = state.month,
                fileCount = dayFiles.size,
                bytes = dayFiles.sumOf { it.sizeBytes },
                gridMode = state.gridMode,
                onToggleView = viewModel::toggleViewMode,
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading -> ProgressPanel(
                        title = stringResource(R.string.calendar_loading),
                        detail = null,
                        fraction = null,
                        modifier = Modifier.padding(16.dp),
                    )

                    state.deepScanning -> ProgressPanel(
                        title = stringResource(R.string.calendar_deep_scanning, state.scanned),
                        detail = stringResource(R.string.calendar_deep_scanning_matched, state.matched),
                        fraction = null,
                        onCancel = viewModel::stopDeepScan,
                        modifier = Modifier.padding(16.dp),
                    )

                    state.selectedDay == null -> EmptyState(
                        icon = Icons.Filled.CalendarMonth,
                        title = stringResource(R.string.calendar_pick_day),
                        body = null,
                    )

                    dayFiles.isEmpty() -> EmptyState(
                        icon = Icons.Filled.CalendarMonth,
                        title = stringResource(R.string.calendar_day_empty),
                        body = stringResource(R.string.calendar_day_empty_hint),
                        action = {
                            FilledTonalButton(onClick = viewModel::deepScan) {
                                Text(stringResource(R.string.calendar_deep_scan))
                            }
                        },
                    )

                    state.gridMode -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 108.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(dayFiles, key = { it.path }) { item ->
                            DayGridTile(
                                item = item,
                                selected = item.path in state.selection,
                                selectionMode = state.selection.isNotEmpty(),
                                onClick = {
                                    if (state.selection.isEmpty()) {
                                        FileIntents.open(context, item)
                                    } else {
                                        viewModel.toggleSelection(item.path)
                                    }
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.startSelection(item.path)
                                },
                            )
                        }
                    }

                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(dayFiles, key = { it.path }) { item ->
                            FileRow(
                                item = item,
                                selectionMode = state.selection.isNotEmpty(),
                                selected = item.path in state.selection,
                                subtitle = "${Formatters.bytes(item.sizeBytes)} · ${
                                    Formatters.time(item.lastModified)
                                }",
                                onClick = {
                                    if (state.selection.isEmpty()) {
                                        FileIntents.open(context, item)
                                    } else {
                                        viewModel.toggleSelection(item.path)
                                    }
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.startSelection(item.path)
                                },
                                trailing = {
                                    IconButton(
                                        onClick = {
                                            onOpenFolder(item.path.substringBeforeLast('/'))
                                        },
                                    ) {
                                        Icon(
                                            Icons.Filled.FolderOpen,
                                            contentDescription = stringResource(R.string.cd_open_folder),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                // Operation progress is drawn by the root-level bar, which stays
                // visible if the user leaves this screen mid-delete. A local
                // panel here would render a second copy.
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_title, state.selection.size, state.selection.size),
            body = stringResource(R.string.delete_body, trashDays),
            confirmLabel = stringResource(R.string.delete_move_to_trash),
            onConfirm = {
                viewModel.deleteSelected()
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }

    if (picker != null) {
        val mode = picker
        FolderPickerSheet(
            startPath = dayFiles.firstOrNull()?.path?.substringBeforeLast('/')
                ?: viewModel.defaultRoot(),
            title = stringResource(
                if (mode == Picker.COPY) R.string.action_copy_here else R.string.action_move_here
            ),
            onDismiss = { picker = null },
            onPick = { destination ->
                if (mode == Picker.COPY) viewModel.copySelectedTo(destination)
                else viewModel.moveSelectedTo(destination)
                picker = null
            },
        )
    }

    if (showMore) {
        BulkMoreSheet(
            items = state.selectedItems,
            bytes = state.selectedItems.sumOf { it.sizeBytes },
            handlers = BulkHandlers(
                onShare = {
                    FileIntents.share(context, state.selectedItems)
                    showMore = false
                    viewModel.clearSelection()
                },
                onMove = {
                    showMore = false
                    picker = Picker.MOVE
                },
                onCopy = {
                    showMore = false
                    picker = Picker.COPY
                },
                onConvert = {
                    showMore = false
                    onConvert(state.selection.toList())
                },
                onBatchRename = {
                    showMore = false
                    onBatchRename(state.selection.toList())
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
                    viewModel.starSelection()
                },
                onDelete = {
                    showMore = false
                    if (settings.confirmDelete) confirmDelete = true
                    else viewModel.deleteSelected()
                },
                onMore = { showMore = false },
                transferFolder = settings.transferFolder,
                onMoveToTransfer = if (settings.transferFolder.isNotBlank()) {
                    {
                        showMore = false
                        viewModel.moveSelectedTo(settings.transferFolder)
                    }
                } else {
                    null
                },
                onSelectAll = viewModel::selectAllOnDay,
                onDeselectAll = viewModel::clearSelection,
            ),
            onDismiss = { showMore = false },
        )
    }
}

/** Which folder picker is open. Null means none. */
private enum class Picker { MOVE, COPY }

@Composable
private fun MonthHeader(
    year: Int,
    month: Int,
    summary: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    val label = YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = stringResource(R.string.cd_previous_month),
            )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label.replaceFirstChar(Char::uppercase),
                style = MaterialTheme.typography.titleLarge,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onNext) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.cd_next_month),
            )
        }
        TextButton(onClick = onToday) {
            Text(stringResource(R.string.calendar_today))
        }
    }
}

@Composable
private fun MonthGrid(
    year: Int,
    month: Int,
    data: MonthData?,
    selectedDay: Int?,
    todayDay: Int?,
    onSelect: (Int) -> Unit,
) {
    val weekStart = remember {
        WeekFields.of(Locale.getDefault()).firstDayOfWeek
    }
    val cells = remember(year, month, weekStart) { monthCells(year, month, weekStart) }
    val maxCount = data?.busiestDay ?: 0
    val dayLabels = remember(weekStart) {
        (0..6).map { offset ->
            weekStart.plus(offset.toLong()).getDisplayName(TextStyle.NARROW, Locale.getDefault())
        }
    }
    val today = LocalDate.now()

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            dayLabels.forEach { label ->
                Text(
                    text = label.uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Box(modifier = Modifier.weight(1f).padding(2.dp)) {
                        if (day == null) {
                            Spacer(Modifier.aspectRatio(1f))
                        } else {
                            DayCell(
                                day = day,
                                count = data?.countOn(day) ?: 0,
                                maxCount = maxCount,
                                isSelected = day == selectedDay,
                                isToday = day == todayDay && today.year == year &&
                                    today.monthValue == month,
                                isFuture = YearMonth.of(year, month)
                                    .atDay(day)
                                    .isAfter(today),
                                onClick = { onSelect(day) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    count: Int,
    maxCount: Int,
    isSelected: Boolean,
    isToday: Boolean,
    isFuture: Boolean,
    onClick: () -> Unit,
) {
    val intensity = if (maxCount <= 0) 0f else (count.toFloat() / maxCount.toFloat())
    // On a dark surface a single-hue ramp at low alpha disappears; the same
    // data needs a wider range to stay readable.
    val darkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val heat = if (darkSurface) 0.18f + intensity * 0.42f else 0.10f + intensity * 0.30f
    val background = when {
        isSelected -> MaterialTheme.colorScheme.primary
        count > 0 -> MaterialTheme.colorScheme.primary.copy(alpha = heat)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    }
    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isFuture -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .then(
                if (isToday && !isSelected) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                    )
                } else {
                    Modifier
                }
            )
            .clickable(enabled = !isFuture || count > 0, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor,
            )
            if (count > 0) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                )
            } else {
                Spacer(Modifier.height(15.dp))
            }
        }
    }
}

@Composable
private fun DayHeader(
    day: Int?,
    year: Int,
    month: Int,
    fileCount: Int,
    bytes: Long,
    gridMode: Boolean,
    onToggleView: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (day == null) {
                    stringResource(R.string.calendar_pick_day)
                } else {
                    LocalDate.of(year, month, day)
                        .format(DateTimeFormatter.ofPattern("EEEE, d MMMM"))
                        .replaceFirstChar(Char::uppercase)
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fileCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.file_count,
                        fileCount,
                        fileCount,
                    ) + " · " + Formatters.bytes(bytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (fileCount > 0) {
            IconButton(onClick = onToggleView) {
                Icon(
                    imageVector = if (gridMode) Icons.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = stringResource(R.string.cd_view_mode),
                )
            }
        }
    }
}

@Composable
private fun DayGridTile(
    item: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                }
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            FileThumb(
                item = item,
                enabled = true,
                modifier = Modifier.fillMaxSize(),
            )
            if (selectionMode && selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SelectAll,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Lays out a month as whole weeks, honouring the locale's first day of week.
 */
internal fun monthCells(
    year: Int,
    month: Int,
    weekStart: java.time.DayOfWeek,
): List<Int?> {
    val first = LocalDate.of(year, month, 1)
    val offset = (first.dayOfWeek.value - weekStart.value + 7) % 7
    val length = YearMonth.of(year, month).lengthOfMonth()
    val cells = mutableListOf<Int?>()
    repeat(offset) { cells += null }
    for (day in 1..length) cells += day
    while (cells.size % 7 != 0) cells += null
    return cells
}