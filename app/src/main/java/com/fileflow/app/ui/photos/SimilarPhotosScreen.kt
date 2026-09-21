package com.fileflow.app.ui.photos

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.model.SortOrder
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationReport
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.prefs.SettingsRepository
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.domain.Candidates
import com.fileflow.app.domain.scan.PhotoGroup
import com.fileflow.app.domain.scan.SimilarPhotoScanner
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileThumb
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.LoadingState
import com.fileflow.app.ui.components.SectionHeader
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
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
data class PhotoState(
    val folder: String = "",
    val scanning: Boolean = false,
    val progress: String? = null,
    val groups: List<PhotoGroup> = emptyList(),
    /** Paths ticked for removal; the best copy in a group is never a default. */
    val marked: Set<String> = emptySet(),
    val working: Boolean = false,
) {
    val markedBytes: Long
        get() = groups.flatMap { it.photos }.filter { it.path in marked }.sumOf { it.sizeBytes }

    val reclaimable: Long get() = groups.sumOf { it.wastedBytes }
}

@HiltViewModel
class SimilarPhotosViewModel @Inject constructor(
    private val browseRepository: BrowseRepository,
    private val scanner: SimilarPhotoScanner,
    private val actions: FileActionsRepository,
    private val operationRunner: OperationRunner,
    settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PhotoState())
    val state: StateFlow<PhotoState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var useTrash = true

    init {
        viewModelScope.launch {
            settings.settings.collect { prefs -> useTrash = prefs.useTrash }
        }
        viewModelScope.launch {
            _state.update { it.copy(folder = browseRepository.primaryRoot()) }
        }
    }

    fun setFolder(path: String) {
        _state.update { it.copy(folder = path, groups = emptyList(), marked = emptySet()) }
    }

    fun scan() {
        val folder = _state.value.folder
        if (folder.isBlank() || _state.value.scanning) return
        viewModelScope.launch {
            _state.update { it.copy(scanning = true, groups = emptyList(), marked = emptySet()) }
            try {
                val items = browseRepository.listItems(folder, includeHidden = true, sort = SortOrder())
                val groups = scanner.scan(
                    candidates = items.mapNotNull { Candidates.of(File(it.path)) },
                    onProgress = { scanned, total ->
                        _state.update { it.copy(progress = "$scanned / $total") }
                    },
                )
                _state.update { it.copy(scanning = false, groups = groups, progress = null) }
                if (groups.isEmpty()) _messages.send(UiMessage.Info("No similar photos found"))
            } catch (error: Throwable) {
                _state.update { it.copy(scanning = false, progress = null) }
                _messages.send(UiMessage.Info(error.message ?: "The scan failed"))
            }
        }
    }

    /** Ticks everything except the best copy of each group. */
    fun markAllButBest() {
        _state.update { current ->
            val marked = current.groups.flatMap { group ->
                group.photos.filter { it.path != group.best.path }.map { it.path }
            }.toSet()
            current.copy(marked = marked)
        }
    }

    fun clearMarks() {
        _state.update { it.copy(marked = emptySet()) }
    }

    fun toggle(path: String) {
        _state.update { current ->
            val marked = if (path in current.marked) current.marked - path else current.marked + path
            current.copy(marked = marked)
        }
    }

    fun deleteMarked() {
        val paths = _state.value.marked.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                var report: OperationReport? = null
                operationRunner.run(
                    if (useTrash) OperationKind.TRASH else OperationKind.DELETE
                ) { progress ->
                    report = actions.delete(paths, permanent = !useTrash, progress)
                }
                _state.update { it.copy(working = false, marked = emptySet()) }
                val done = report
                _messages.send(
                    UiMessage.Done(
                        text = "${done?.succeeded ?: 0} removed" +
                            if ((done?.failed ?: 0) > 0) ", ${done?.failed} failed" else "",
                        undoGroup = done?.groupId?.takeIf { useTrash && (done?.succeeded ?: 0) > 0 },
                    )
                )
                scan()
            } catch (_: CancellationException) {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun undo(groupId: String) {
        viewModelScope.launch {
            actions.undoGroup(groupId)
            scan()
        }
    }
}

/**
 * Photos that look like each other, with the extras already ticked.
 *
 * The screen exists for one decision: which copy of a moment to keep. The
 * largest-then-oldest copy of each group is treated as the original and is
 * never ticked by default, everything else is, and the deletions go to the
 * trash so the decision is reversible for as long as the trash is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimilarPhotosScreen(
    onBack: () -> Unit,
    viewModel: SimilarPhotosViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showPicker by remember { mutableStateOf(false) }

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
                    Column(modifier = Modifier.clickable { showPicker = true }) {
                        Text(stringResource(R.string.photos_title))
                        Text(
                            text = state.folder.substringAfterLast('/').ifEmpty { state.folder },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showPicker = true }) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = stringResource(R.string.compress_change_folder),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state.marked.isNotEmpty()) {
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.photos_marked, state.marked.size),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = Formatters.bytes(state.markedBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = viewModel::deleteMarked,
                            enabled = !state.working,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.photos_remove))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = viewModel::scan,
                    enabled = !state.scanning,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (state.scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(state.progress ?: stringResource(R.string.photos_scanning))
                    } else {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.photos_scan))
                    }
                }
                OutlinedButton(
                    onClick = viewModel::markAllButBest,
                    enabled = state.groups.isNotEmpty(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.photos_auto))
                }
            }

            if (state.groups.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.photos_summary,
                        state.groups.size,
                        Formatters.bytes(state.reclaimable),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                state.scanning && state.groups.isEmpty() ->
                    LoadingState(label = state.progress ?: stringResource(R.string.photos_scanning))

                state.groups.isEmpty() -> EmptyState(
                    icon = Icons.Filled.PhotoLibrary,
                    title = stringResource(R.string.photos_none),
                    body = stringResource(R.string.photos_none_hint),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.groups) { group ->
                        SectionHeader(
                            stringResource(
                                R.string.photos_group,
                                group.photos.size,
                                Formatters.bytes(group.wastedBytes),
                            )
                        )
                        group.photos.forEach { photo ->
                            val isBest = photo.path == group.best.path
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (photo.path in state.marked) {
                                            MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    )
                                    .clickable { viewModel.toggle(photo.path) }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(modifier = Modifier.size(46.dp)) {
                                    FileThumb(
                                        item = com.fileflow.app.core.model.FileItem(
                                            path = photo.path,
                                            name = photo.name,
                                            isDirectory = false,
                                            sizeBytes = photo.sizeBytes,
                                            lastModified = photo.lastModified,
                                            extension = photo.name.substringAfterLast('.', ""),
                                            mimeType = "image/*",
                                            category = com.fileflow.app.core.model.FileCategory.IMAGES,
                                            isHidden = false,
                                        ),
                                        enabled = true,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = photo.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = buildString {
                                            append(Formatters.bytes(photo.sizeBytes))
                                            if (isBest) append(" · ").append(
                                                context.getString(R.string.photos_keep)
                                            )
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isBest) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Checkbox(
                                    checked = photo.path in state.marked,
                                    onCheckedChange = { viewModel.toggle(photo.path) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        FolderPickerSheet(
            startPath = state.folder.ifBlank { "/" },
            title = stringResource(R.string.photos_pick_folder),
            onDismiss = { showPicker = false },
            onPick = { path ->
                viewModel.setFolder(path)
                showPicker = false
            },
        )
    }
}
