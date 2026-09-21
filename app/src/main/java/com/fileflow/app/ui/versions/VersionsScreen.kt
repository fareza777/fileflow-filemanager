package com.fileflow.app.ui.versions

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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.fileflow.app.core.model.SortOrder
import com.fileflow.app.core.storage.CollisionResolver
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.domain.versions.FileVersion
import com.fileflow.app.domain.versions.VersionStore
import com.fileflow.app.domain.versions.VersionStoreStats
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.SectionHeader
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class VersionsState(
    val folder: String = "",
    val files: List<FileRowState> = emptyList(),
    val openFile: String? = null,
    val versions: List<FileVersion> = emptyList(),
    val stats: VersionStoreStats = VersionStoreStats(0, 0, 0, 0),
    val busy: Boolean = false,
    val progress: String? = null,
)

data class FileRowState(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val versionCount: Int,
)

@HiltViewModel
class VersionsViewModel @Inject constructor(
    private val browseRepository: BrowseRepository,
    private val store: VersionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(VersionsState())
    val state: StateFlow<VersionsState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            _state.update { it.copy(folder = browseRepository.primaryRoot()) }
            refresh()
        }
    }

    fun setFolder(path: String) {
        _state.update { it.copy(folder = path, openFile = null, versions = emptyList()) }
        refresh()
    }

    fun refresh() {
        val folder = _state.value.folder
        viewModelScope.launch {
            val items = browseRepository.listItems(folder, includeHidden = false, sort = SortOrder())
            val rows = items.filter { !it.isDirectory }.map { item ->
                val file = File(item.path)
                FileRowState(
                    path = item.path,
                    name = item.name,
                    sizeBytes = item.sizeBytes,
                    versionCount = if (store.canVersion(file)) store.versions(file).size else 0,
                )
            }
            _state.update { it.copy(files = rows, stats = store.stats()) }
        }
    }

    fun open(file: FileRowState) {
        _state.update { it.copy(openFile = file.path) }
        viewModelScope.launch {
            _state.update { it.copy(versions = store.versions(File(file.path))) }
        }
    }

    fun closeFile() {
        _state.update { it.copy(openFile = null, versions = emptyList()) }
    }

    /** Stores the current contents as a new version, if anything changed. */
    fun snapshot(file: FileRowState) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = file.name) }
            val version = runCatching {
                store.snapshot(File(file.path)) { done, total ->
                    _state.update { it.copy(progress = "${file.name} ($done/$total)") }
                }
            }.getOrNull()
            _state.update { it.copy(busy = false, progress = null) }
            _messages.send(
                UiMessage.Info(
                    when (version) {
                        null -> "Nothing changed in ${file.name}"
                        else -> "Stored ${Formatters.bytes(version.storedBytes)} of new data for ${file.name}"
                    }
                )
            )
            if (_state.value.openFile == file.path) open(file) else refresh()
        }
    }

    /** Writes a version back beside the file, never over it. */
    fun restore(file: FileRowState, version: FileVersion) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = version.id) }
            val source = File(file.path)
            val parent = source.parentFile ?: return@launch
            val result = runCatching {
                val name = CollisionResolver.unique(parent, "restored-${source.name}")
                store.restore(source, version.id, File(parent, name)) { done, total ->
                    _state.update { it.copy(progress = "$name ($done/$total)") }
                }
            }
            _state.update { it.copy(busy = false, progress = null) }
            result.fold(
                onSuccess = { restored ->
                    browseRepository.invalidateListing(parent.absolutePath)
                    _messages.send(UiMessage.Info("Restored as ${restored.name}"))
                },
                onFailure = { error ->
                    _messages.send(UiMessage.Info(error.message ?: "Could not restore that version"))
                },
            )
            refresh()
        }
    }

    fun deleteVersion(file: FileRowState, version: FileVersion) {
        viewModelScope.launch {
            store.deleteVersion(File(file.path), version.id)
            _messages.send(UiMessage.Info("Version removed"))
            open(file)
            refresh()
        }
    }
}

/**
 * Versions of a file, stored as the chunks that changed.
 *
 * The screen answers the question that follows every "I edited this and now I
 * want the old one back": pick the file, pick a moment, get a copy beside the
 * current one. What makes it affordable is that a version is not a copy — it is
 * the list of chunks it is made of, and a chunk the store already has is not
 * written twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsScreen(
    onBack: () -> Unit,
    viewModel: VersionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbar.showMessage(context, message) { } }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showPicker = true }) {
                        Text(stringResource(R.string.versions_title))
                        Text(
                            text = state.openFile?.substringAfterLast('/')
                                ?: state.folder.substringAfterLast('/').ifEmpty { state.folder },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (state.openFile != null) viewModel.closeFile() else onBack() },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (state.openFile == null) {
                        IconButton(onClick = { showPicker = true }) {
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = stringResource(R.string.compress_change_folder),
                            )
                        }
                    }
                    if (state.busy) {
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.stats.versions > 0) {
                Text(
                    text = stringResource(
                        R.string.versions_stats,
                        state.stats.versions,
                        Formatters.bytes(state.stats.storedBytes),
                        Formatters.bytes(state.stats.savedBytes),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val openFile = state.openFile
            when {
                openFile != null -> {
                    val file = state.files.firstOrNull { it.path == openFile }
                    if (file != null) {
                        Button(
                            onClick = { viewModel.snapshot(file) },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.versions_snapshot))
                        }
                    }
                    if (state.versions.isEmpty()) {
                        EmptyState(
                            icon = Icons.Filled.History,
                            title = stringResource(R.string.versions_none),
                            body = stringResource(R.string.versions_none_hint),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(state.versions, key = { it.id }) { version ->
                                VersionRow(
                                    version = version,
                                    enabled = !state.busy,
                                    onRestore = { file?.let { viewModel.restore(it, version) } },
                                    onDelete = { file?.let { viewModel.deleteVersion(it, version) } },
                                )
                            }
                        }
                    }
                }

                state.files.isEmpty() -> EmptyState(
                    icon = Icons.Filled.History,
                    title = stringResource(R.string.browser_empty),
                    body = stringResource(R.string.versions_empty_hint),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item { SectionHeader(stringResource(R.string.versions_pick_file)) }
                    items(state.files, key = { it.path }) { file ->
                        FileVersionRow(
                            file = file,
                            onClick = { viewModel.open(file) },
                            onSnapshot = { viewModel.snapshot(file) },
                            enabled = !state.busy,
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        FolderPickerSheet(
            startPath = state.folder.ifBlank { "/" },
            title = stringResource(R.string.versions_pick_folder),
            onDismiss = { showPicker = false },
            onPick = { path ->
                viewModel.setFolder(path)
                showPicker = false
            },
        )
    }
}

@Composable
private fun FileVersionRow(
    file: FileRowState,
    onClick: () -> Unit,
    onSnapshot: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(Formatters.bytes(file.sizeBytes))
                    append(" · ")
                    append(
                        if (file.versionCount == 0) {
                            stringResource(R.string.versions_no_versions)
                        } else {
                            stringResource(R.string.versions_count, file.versionCount)
                        }
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (file.versionCount > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onSnapshot, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = stringResource(R.string.versions_snapshot),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun VersionRow(
    version: FileVersion,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = Formatters.dateTime(version.at),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    R.string.versions_detail,
                    version.chunks,
                    Formatters.bytes(version.storedBytes),
                    Formatters.bytes(version.sizeBytes),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRestore, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.Restore,
                contentDescription = stringResource(R.string.versions_restore),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
