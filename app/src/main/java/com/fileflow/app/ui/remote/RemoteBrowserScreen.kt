package com.fileflow.app.ui.remote

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.fs.RemoteEntry
import com.fileflow.app.core.fs.RemoteException
import com.fileflow.app.core.fs.RemoteFileSystem
import com.fileflow.app.core.fs.remoteName
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.model.SortOrder
import com.fileflow.app.core.storage.CollisionResolver
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationProgress
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.MimeTypes
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.FileIndexRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.domain.remote.RemoteConnection
import com.fileflow.app.domain.remote.RemoteConnectionStore
import com.fileflow.app.domain.remote.RemoteFileSystems
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileRow
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.LoadingState
import com.fileflow.app.ui.components.SelectionAction
import com.fileflow.app.ui.components.SelectionActionBar
import com.fileflow.app.ui.util.showMessage
import com.fileflow.app.ui.util.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.compose.runtime.Immutable
data class RemoteBrowseState(
    val connection: RemoteConnection? = null,
    val path: String = "/",
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val selection: Set<String> = emptySet(),
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()
    val selected: List<RemoteEntry> get() = entries.filter { it.path in selection }
}

@HiltViewModel
class RemoteBrowserViewModel @Inject constructor(
    private val store: RemoteConnectionStore,
    private val sources: RemoteFileSystems,
    private val browseRepository: BrowseRepository,
    private val indexRepository: FileIndexRepository,
    private val operationRunner: OperationRunner,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val connectionId: String = savedStateHandle.get<String>(ARG_ID).orEmpty()

    private val _state = MutableStateFlow(RemoteBrowseState())
    val state: StateFlow<RemoteBrowseState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var source: RemoteFileSystem? = null

    init {
        viewModelScope.launch {
            val connection = store.connections.first().firstOrNull { it.id == connectionId }
            if (connection == null) {
                _state.update { it.copy(loading = false, error = "That connection is gone") }
                return@launch
            }
            _state.update { it.copy(connection = connection, path = "/") }
            openHome(connection)
        }
    }

    private suspend fun openHome(connection: RemoteConnection) {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val fs = sources.open(connection)
            source = fs
            val home = fs.home()
            _state.update { it.copy(path = home) }
            refreshNow()
        } catch (error: Throwable) {
            _state.update { it.copy(loading = false, error = error.message ?: "Could not connect") }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    private suspend fun refreshNow() {
        val fs = source ?: return
        val path = _state.value.path
        _state.update { it.copy(loading = true, error = null) }
        try {
            val entries = withContext(Dispatchers.IO) { fs.list(path) }
            _state.update {
                it.copy(
                    loading = false,
                    entries = entries,
                    selection = it.selection.filter { selected -> entries.any { e -> e.path == selected } }.toSet(),
                )
            }
        } catch (error: Throwable) {
            _state.update {
                it.copy(loading = false, error = error.message ?: "Could not list that folder")
            }
        }
    }

    fun open(entry: RemoteEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, selection = emptySet()) }
        viewModelScope.launch { refreshNow() }
    }

    fun up() {
        val fs = source ?: return
        val parent = fs.parent(_state.value.path) ?: return
        _state.update { it.copy(path = parent, selection = emptySet()) }
        viewModelScope.launch { refreshNow() }
    }

    fun toggle(path: String) {
        _state.update { current ->
            val selection = if (path in current.selection) current.selection - path
            else current.selection + path
            current.copy(selection = selection)
        }
    }

    fun selectAll() {
        _state.update { it.copy(selection = it.entries.map { entry -> entry.path }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    fun mkdir(name: String) {
        val fs = source ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { fs.mkdir("${_state.value.path.trimEnd('/')}/$name") }
                .onFailure { error -> _messages.send(UiMessage.Info("Could not create folder: ${error.message}")) }
            _state.update { it.copy(busy = false) }
            refreshNow()
        }
    }

    fun rename(entry: RemoteEntry, newName: String) {
        val fs = source ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val parent = fs.parent(entry.path).orEmpty()
            runCatching { fs.rename(entry.path, "${parent.trimEnd('/')}/$newName") }
                .onFailure { error -> _messages.send(UiMessage.Info("Could not rename: ${error.message}")) }
            _state.update { it.copy(busy = false) }
            refreshNow()
        }
    }

    fun delete(entries: List<RemoteEntry>) {
        val fs = source ?: return
        if (entries.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            var failed = 0
            entries.forEach { entry ->
                runCatching { withContext(Dispatchers.IO) { fs.remove(entry.path, entry.isDirectory) } }
                    .onFailure { failed++ }
            }
            _state.update { it.copy(busy = false, selection = emptySet()) }
            _messages.send(
                UiMessage.Info(
                    if (failed == 0) "${entries.size} removed" else "${entries.size - failed} removed, $failed failed"
                )
            )
            refreshNow()
        }
    }

    /** Streams remote files into a local folder, indexed so the app can find them. */
    fun download(entries: List<RemoteEntry>, destination: String) {
        val fs = source ?: return
        val files = entries.filterNot { it.isDirectory }
        if (files.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val saved = mutableListOf<String>()
            try {
                operationRunner.run(OperationKind.COPY) { progress ->
                    files.forEachIndexed { index, entry ->
                        val name = CollisionResolver.unique(File(destination), entry.name)
                        val target = File(destination, name)
                        val partial = File(destination, "$name.fileflow-part")
                        try {
                            withContext(Dispatchers.IO) {
                                fs.openInput(entry.path).use { input ->
                                    FileOutputStream(partial).use { output ->
                                        val buffer = ByteArray(COPY_BUFFER)
                                        var written = 0L
                                        while (true) {
                                            val read = input.read(buffer)
                                            if (read <= 0) break
                                            output.write(buffer, 0, read)
                                            written += read
                                        }
                                        progress(
                                            OperationProgress(
                                                current = index,
                                                total = files.size,
                                                currentName = entry.name,
                                                bytesDone = written,
                                                bytesTotal = entry.sizeBytes,
                                            )
                                        )
                                    }
                                }
                            }
                            if (!partial.renameTo(target)) {
                                partial.copyTo(target, overwrite = true)
                                partial.delete()
                            }
                            saved += target.absolutePath
                        } catch (error: Throwable) {
                            partial.delete()
                            throw error
                        }
                    }
                }
                indexRepository.upsertPaths(saved)
                _state.update { it.copy(busy = false, selection = emptySet()) }
                _messages.send(UiMessage.Info("${saved.size} downloaded to ${remoteName(destination)}"))
            } catch (error: Throwable) {
                _state.update { it.copy(busy = false) }
                _messages.send(
                    UiMessage.Info(
                        if (saved.isEmpty()) {
                            "Download failed: ${error.message}"
                        } else {
                            "${saved.size} downloaded, then: ${error.message}"
                        }
                    )
                )
                if (saved.isNotEmpty()) indexRepository.upsertPaths(saved)
            }
        }
    }

    /** Streams local files into the folder the browser is showing. */
    fun upload(paths: List<String>, onDone: () -> Unit) {
        val fs = source ?: return
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            var uploaded = 0
            var lastError: String? = null
            try {
                operationRunner.run(OperationKind.COPY) { progress ->
                    paths.forEachIndexed { index, path ->
                        val file = File(path)
                        if (!file.isFile) return@forEachIndexed
                        try {
                            withContext(Dispatchers.IO) {
                                file.inputStream().use { input ->
                                    fs.write(
                                        path = _state.value.path,
                                        name = file.name,
                                        source = input,
                                        sizeBytes = file.length(),
                                    ) { written ->
                                        progress(
                                            OperationProgress(
                                                current = index,
                                                total = paths.size,
                                                currentName = file.name,
                                                bytesDone = written,
                                                bytesTotal = file.length(),
                                            )
                                        )
                                    }
                                }
                            }
                            uploaded++
                        } catch (error: Throwable) {
                            lastError = error.message
                        }
                    }
                }
            } finally {
                _state.update { it.copy(busy = false) }
                _messages.send(
                    UiMessage.Info(
                        if (lastError == null) {
                            "$uploaded uploaded"
                        } else {
                            "$uploaded uploaded, failed: $lastError"
                        }
                    )
                )
                onDone()
                refreshNow()
            }
        }
    }

    /** The listing the upload sheet browses; local, not remote. */
    suspend fun localListing(path: String): List<FileItem> =
        browseRepository.listItems(path, includeHidden = false, sort = SortOrder())

    fun localRoot(): String = browseRepository.primaryRoot()

    companion object {
        const val ARG_ID = "connectionId"
    }
}

/**
 * A remote folder, with the verbs a file manager needs and no more.
 *
 * Everything expensive happens off the frame: listings, downloads and uploads
 * are coroutines with the app's own progress panel, so navigating away from a
 * transfer does not cancel it and does not leave the screen guessing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteBrowserScreen(
    onBack: () -> Unit,
    viewModel: RemoteBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showNewFolder by remember { mutableStateOf(false) }
    var showDownloadPicker by remember { mutableStateOf(false) }
    var showUpload by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var actionTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.clickable { viewModel.up() },
                    ) {
                        Text(
                            text = state.connection?.label
                                ?: stringResource(R.string.remote_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (state.selectionMode) viewModel.clearSelection() else onBack() },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (state.selectionMode) {
                        IconButton(onClick = viewModel::selectAll) {
                            Icon(
                                imageVector = Icons.Filled.SelectAll,
                                contentDescription = stringResource(R.string.cd_select_all),
                            )
                        }
                    } else {
                        IconButton(onClick = { showNewFolder = true }) {
                            Icon(
                                imageVector = Icons.Filled.CreateNewFolder,
                                contentDescription = stringResource(R.string.remote_new_folder),
                            )
                        }
                        IconButton(onClick = viewModel::refresh) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh),
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                FloatingActionButton(onClick = { showUpload = true }) {
                    Icon(Icons.Filled.Upload, contentDescription = stringResource(R.string.remote_upload))
                }
            }
        },
        bottomBar = {
            if (state.selectionMode) {
                SelectionActionBar(
                    title = stringResource(R.string.remote_selected, state.selection.size),
                    subtitle = Formatters.bytes(state.selected.sumOf { it.sizeBytes }),
                    actions = listOf(
                        SelectionAction(
                            icon = Icons.Filled.Deselect,
                            label = stringResource(R.string.action_deselect_all),
                            onClick = viewModel::clearSelection,
                        ),
                        SelectionAction(
                            icon = Icons.Filled.Download,
                            label = stringResource(R.string.remote_download),
                            onClick = { showDownloadPicker = true },
                        ),
                        SelectionAction(
                            icon = Icons.Filled.Delete,
                            label = stringResource(R.string.action_delete),
                            onClick = { confirmDelete = true },
                            destructive = true,
                        ),
                    ),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null -> EmptyState(
                    icon = Icons.Filled.Close,
                    title = stringResource(R.string.remote_failed),
                    body = state.error,
                    action = {
                        TextButton(onClick = { viewModel.refresh() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    },
                )

                state.loading && state.entries.isEmpty() -> LoadingState(
                    label = stringResource(R.string.remote_connecting),
                )

                state.entries.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Folder,
                    title = stringResource(R.string.browser_empty),
                    body = stringResource(R.string.remote_empty_folder_hint),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.entries, key = { it.path }) { entry ->
                        RemoteRow(
                            entry = entry,
                            selected = entry.path in state.selection,
                            selectionMode = state.selectionMode,
                            onClick = {
                                if (state.selectionMode) viewModel.toggle(entry.path)
                                else if (entry.isDirectory) viewModel.open(entry)
                                else actionTarget = entry
                            },
                            onLongClick = { viewModel.toggle(entry.path) },
                            onMore = { actionTarget = entry },
                        )
                    }
                }
            }

            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(22.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }

    if (showNewFolder) {
        NameDialog(
            title = stringResource(R.string.remote_new_folder),
            initial = "",
            onConfirm = {
                viewModel.mkdir(it)
                showNewFolder = false
            },
            onDismiss = { showNewFolder = false },
        )
    }

    renameTarget?.let { target ->
        NameDialog(
            title = stringResource(R.string.action_rename),
            initial = target.name,
            onConfirm = {
                viewModel.rename(target, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    actionTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.path, style = MaterialTheme.typography.bodySmall)
                    if (!entry.isDirectory) {
                        Text(
                            text = Formatters.bytes(entry.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.toggle(entry.path)
                    actionTarget = null
                }) {
                    Text(stringResource(R.string.remote_select))
                }
            },
            dismissButton = {
                Row {
                    if (!entry.isDirectory) {
                        TextButton(onClick = {
                            viewModel.download(listOf(entry), viewModel.localRoot())
                            actionTarget = null
                        }) {
                            Text(stringResource(R.string.remote_download_here))
                        }
                    }
                    TextButton(onClick = {
                        renameTarget = entry
                        actionTarget = null
                    }) {
                        Text(stringResource(R.string.action_rename))
                    }
                }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.remote_delete_title)) },
            text = { Text(stringResource(R.string.remote_delete_body, state.selection.size)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(state.selected)
                    confirmDelete = false
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showDownloadPicker) {
        FolderPickerSheet(
            startPath = viewModel.localRoot(),
            title = stringResource(R.string.remote_download_to),
            onDismiss = { showDownloadPicker = false },
            onPick = { destination ->
                viewModel.download(state.selected, destination)
                showDownloadPicker = false
            },
        )
    }

    if (showUpload) {
        LocalFilePickerSheet(
            startPath = viewModel.localRoot(),
            listing = { path -> viewModel.localListing(path) },
            onDismiss = { showUpload = false },
            onPick = { paths ->
                showUpload = false
                viewModel.upload(paths) { }
            },
        )
    }
}

@Composable
private fun RemoteRow(
    entry: RemoteEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    val item = remember(entry) {
        FileItem(
            path = entry.path,
            name = entry.name,
            isDirectory = entry.isDirectory,
            sizeBytes = entry.sizeBytes,
            lastModified = entry.lastModified,
            extension = entry.extension,
            mimeType = if (entry.isDirectory) "resource/folder" else MimeTypes.mimeFor(entry.extension),
            category = MimeTypes.categoryOf(entry.extension),
            isHidden = entry.name.startsWith("."),
        )
    }
    FileRow(
        item = item,
        selectionMode = selectionMode,
        selected = selected,
        thumbnail = false,
        subtitle = if (entry.isDirectory) {
            stringResource(R.string.common_folder)
        } else {
            "${Formatters.bytes(entry.sizeBytes)} · ${Formatters.relative(entry.lastModified)}"
        },
        onClick = onClick,
        onLongClick = onLongClick,
        trailing = {
            IconButton(onClick = onMore) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.cd_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Picks local files to send up.
 *
 * Its own sheet rather than a hop to the Files tab: the point of the remote
 * browser is that the other side is one tap away, and a picker that navigates
 * away and back would make a two-step job into a four-step one.
 */
@Composable
private fun LocalFilePickerSheet(
    startPath: String,
    listing: suspend (String) -> List<FileItem>,
    onDismiss: () -> Unit,
    onPick: (List<String>) -> Unit,
) {
    var path by remember { mutableStateOf(startPath) }
    var items by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(path) {
        loading = true
        items = runCatching { listing(path) }.getOrDefault(emptyList())
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.remote_upload_title))
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val parent = path.substringBeforeLast('/', "")
                            if (parent.isNotEmpty()) path = parent
                        },
                    ) {
                        Text(stringResource(R.string.remote_up_one_level))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.remote_selected, selection.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loading) {
                    LoadingState(label = stringResource(R.string.state_loading))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(items, key = { it.path }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (item.isDirectory) {
                                            path = item.path
                                        } else {
                                            selection = if (item.path in selection) {
                                                selection - item.path
                                            } else {
                                                selection + item.path
                                            }
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (item.isDirectory) {
                                    Icon(
                                        imageVector = Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                } else {
                                    Checkbox(
                                        checked = item.path in selection,
                                        onCheckedChange = { checked ->
                                            selection = if (checked) selection + item.path
                                            else selection - item.path
                                        },
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!item.isDirectory) {
                                        Text(
                                            text = Formatters.bytes(item.sizeBytes),
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
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(selection.toList()) },
                enabled = selection.isNotEmpty(),
            ) {
                Text(stringResource(R.string.remote_upload_count, selection.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private const val COPY_BUFFER = 128 * 1024
