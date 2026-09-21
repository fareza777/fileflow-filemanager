package com.fileflow.app.ui.power

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.fileflow.app.core.privileged.AccessStatus
import com.fileflow.app.core.privileged.PrivilegedEngine
import com.fileflow.app.core.privileged.PrivilegedEntry
import com.fileflow.app.core.privileged.PrivilegedShell
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.SectionCard
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

@androidx.compose.runtime.Immutable
data class PowerState(
    val status: AccessStatus = AccessStatus(),
    val path: String = "/storage/emulated/0/Android/data",
    val entries: List<PrivilegedEntry> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class PowerAccessViewModel @Inject constructor(
    private val shell: PrivilegedShell,
    private val browseRepository: BrowseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PowerState())
    val state: StateFlow<PowerState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            // The root probe starts a process and waits for it, which is not
            // something to do on the thread drawing the screen.
            val status = withContext(Dispatchers.IO) { shell.status() }
            _state.update { it.copy(status = status) }
            if (status.usable && _state.value.entries.isEmpty()) open(_state.value.path)
        }
    }

    /** Asks Shizuku for the permission; the answer arrives on the listener. */
    fun requestPermission() {
        if (!shell.requestShizukuPermission()) {
            viewModelScope.launch {
                _messages.send(UiMessage.Info("Shizuku is not available. Install and start it first."))
            }
        }
    }

    fun open(path: String) {
        _state.update { it.copy(path = path, loading = true, error = null) }
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { shell.status() }
            if (!status.usable) {
                _state.update {
                    it.copy(
                        status = status,
                        loading = false,
                        entries = emptyList(),
                        error = "No privileged access",
                    )
                }
                return@launch
            }
            val entries = shell.list(path, status)
            _state.update {
                it.copy(status = status, loading = false, entries = entries)
            }
        }
    }

    fun up() {
        val trimmed = _state.value.path.trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) open(parent)
    }

    /** Copies a file the app cannot read into a folder it can. */
    fun copyOut(entry: PrivilegedEntry, destination: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val copied = shell.copyOut(entry.path, destination)
            _state.update { it.copy(busy = false) }
            _messages.send(
                if (copied != null) {
                    UiMessage.Info("Copied ${copied.name} to ${destination.substringAfterLast('/')}")
                } else {
                    UiMessage.Info("Could not copy ${entry.name}")
                }
            )
        }
    }

    fun remove(entry: PrivilegedEntry) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val removed = shell.remove(entry.path)
            _state.update { it.copy(busy = false) }
            _messages.send(
                if (removed) UiMessage.Info("Deleted ${entry.name}") else UiMessage.Info("Could not delete ${entry.name}")
            )
            open(_state.value.path)
        }
    }

    fun fileSize(entry: PrivilegedEntry) {
        viewModelScope.launch {
            val size = shell.folderSize(entry.path)
            _messages.send(
                if (size != null) UiMessage.Info("${entry.name}: ${Formatters.bytes(size)}")
                else UiMessage.Info("Could not measure ${entry.name}")
            )
        }
    }

    fun defaultDestination(): String = browseRepository.primaryRoot()
}

/**
 * The folders Android hides, seen through the shell's eyes.
 *
 * `Android/data` and its neighbours are unreadable to every app by design, which
 * is why a file manager that cannot borrow the shell's rights can only show them
 * as empty. This screen is that one narrow door: list a folder, copy a file out
 * into ordinary storage where the rest of the app can work with it, or delete
 * one — with the path quoted, the operation named, and root or Shizuku required
 * before anything happens at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerAccessScreen(
    onBack: () -> Unit,
    viewModel: PowerAccessViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showDestination by remember { mutableStateOf<PrivilegedEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<PrivilegedEntry?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbar.showMessage(context, message) { } }
    }

    // Shizuku answers a permission request on a listener, and the listener has
    // to be removed when the screen goes away or it leaks the activity.
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                viewModel.refreshStatus()
            }
        }
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
        onDispose { runCatching { Shizuku.removeRequestPermissionResultListener(listener) } }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { viewModel.up() }) {
                        Text(stringResource(R.string.power_title))
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
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = viewModel::refreshStatus) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh),
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
            StatusCard(state = state, onRequest = viewModel::requestPermission)

            if (state.error != null) {
                Text(
                    text = stringResource(R.string.power_no_engine),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (state.loading) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.power_reading),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                items(state.entries, key = { it.path }) { entry ->
                    PrivilegedRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) viewModel.open(entry.path)
                            else showDestination = entry
                        },
                        onLongClick = { if (entry.isDirectory) viewModel.fileSize(entry) },
                        onCopy = { if (!entry.isDirectory) showDestination = entry },
                        onDelete = { confirmDelete = entry },
                    )
                }
            }
        }
    }

    showDestination?.let { entry ->
        FolderPickerSheet(
            startPath = viewModel.defaultDestination(),
            title = stringResource(R.string.power_copy_to),
            onDismiss = { showDestination = null },
            onPick = { destination ->
                viewModel.copyOut(entry, destination)
                showDestination = null
            },
        )
    }

    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.power_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.path, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = stringResource(R.string.power_delete_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(entry)
                    confirmDelete = null
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun StatusCard(state: PowerState, onRequest: () -> Unit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.AdminPanelSettings,
                    contentDescription = null,
                    tint = if (state.status.usable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when (state.status.engine) {
                        PrivilegedEngine.SHIZUKU -> stringResource(R.string.power_via_shizuku)
                        PrivilegedEngine.ROOT -> stringResource(R.string.power_via_root)
                        PrivilegedEngine.NONE -> stringResource(R.string.power_none)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = buildString {
                    append(stringResource(R.string.power_shizuku_state) + ": ")
                    append(
                        when {
                            !state.status.shizukuInstalled -> stringResource(R.string.power_shizuku_missing)
                            state.status.shizukuGranted -> stringResource(R.string.power_shizuku_granted)
                            state.status.shizukuRunning -> stringResource(R.string.power_shizuku_running)
                            else -> stringResource(R.string.power_shizuku_stopped)
                        }
                    )
                    append(" · root: ")
                    append(
                        if (state.status.rootAvailable) {
                            stringResource(R.string.power_root_yes)
                        } else {
                            stringResource(R.string.power_root_no)
                        }
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.power_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.status.shizukuGranted && state.status.shizukuInstalled) {
                Button(
                    onClick = onRequest,
                    shape = RoundedCornerShape(14.dp),
                    enabled = state.status.shizukuRunning,
                ) {
                    Text(stringResource(R.string.power_grant))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PrivilegedRow(
    entry: PrivilegedEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDirectory) {
                Icons.Filled.Folder
            } else {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            },
            contentDescription = null,
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDirectory) {
                Text(
                    text = Formatters.bytes(entry.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!entry.isDirectory) {
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = stringResource(R.string.power_copy_out),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
