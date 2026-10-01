package com.fileflow.app.ui.apps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.domain.apps.AppBackup
import com.fileflow.app.domain.apps.InstalledApp
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.LoadingState
import com.fileflow.app.ui.util.AppIntents
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
data class AppsState(
    val loading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    val selection: Set<String> = emptySet(),
    val exporting: Boolean = false,
    val progress: String? = null,
    val systemIncluded: Boolean = false,
    /** Biggest first by default: this list exists to find what takes space. */
    val sortBySize: Boolean = true,
)

@HiltViewModel
class AppBackupViewModel @Inject constructor(
    private val backup: AppBackup,
    private val browseRepository: BrowseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AppsState())
    val state: StateFlow<AppsState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        reload()
    }

    /** Re-reads the installed packages; called again after an uninstall. */
    fun reload() {
        viewModelScope.launch {
            val apps = backup.installed()
            _state.update {
                it.copy(
                    loading = false,
                    apps = apps,
                    // An uninstalled package cannot stay selected.
                    selection = it.selection.filter { name -> apps.any { a -> a.packageName == name } }
                        .toSet(),
                )
            }
        }
    }

    fun toggleSort() = _state.update { it.copy(sortBySize = !it.sortBySize) }

    fun toggle(packageName: String) {
        _state.update { current ->
            val selection = if (packageName in current.selection) {
                current.selection - packageName
            } else {
                current.selection + packageName
            }
            current.copy(selection = selection)
        }
    }

    fun setSystemIncluded(include: Boolean) {
        _state.update { it.copy(systemIncluded = include, selection = emptySet()) }
    }

    val visibleApps: List<InstalledApp>
        get() = _state.value.apps
            .filter { _state.value.systemIncluded || !it.isSystem }
            .let { apps ->
                if (_state.value.sortBySize) {
                    apps.sortedByDescending { it.sizeBytes }
                } else {
                    apps.sortedBy { it.label.lowercase() }
                }
            }

    /** What the header line reports, so the list opens with a total. */
    fun visibleBytes(visible: List<InstalledApp>): Long = visible.sumOf { it.sizeBytes }

    fun selectAll(visible: List<InstalledApp>) {
        _state.update { it.copy(selection = visible.map { app -> app.packageName }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    /** Copies the APKs — base and splits — into a folder the user picks. */
    fun export(destination: String) {
        val selected = _state.value.apps.filter { it.packageName in _state.value.selection }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            var exported = 0
            var files = 0
            var bytes = 0L
            selected.forEach { app ->
                val result = backup.export(app, File(destination)) { copied, total, name ->
                    _state.update { it.copy(progress = "$name · ${Formatters.bytes(copied)}/${Formatters.bytes(total)}") }
                }
                if (result.failure == null) {
                    exported++
                    files += result.files.size
                    bytes += result.bytes
                }
            }
            _state.update { it.copy(exporting = false, progress = null, selection = emptySet()) }
            _messages.send(
                UiMessage.Info("$exported app(s) · $files APK file(s) · ${Formatters.bytes(bytes)}")
            )
        }
    }

    fun defaultDestination(): String = browseRepository.primaryRoot()
}

/**
 * Installed apps, as files you can keep.
 *
 * The split APKs are the reason this is worth having: a backup of a modern app
 * that contains only the base APK is a file that installs and then crashes, so
 * everything the package owns goes, named after the app and its version — and a
 * folder of these reads like the app drawer it came from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBackupScreen(
    onBack: () -> Unit,
    viewModel: AppBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showPicker by remember { mutableStateOf(false) }
    val visible = viewModel.visibleApps

    // Uninstalling is one system dialog per app, so a batch is a queue: the
    // head is asked about, and its answer pulls the next one up. The list is
    // re-read when the queue empties, because only the installer knows what
    // actually happened.
    var uninstallQueue by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    val uninstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        uninstallQueue = uninstallQueue.drop(1)
        if (uninstallQueue.isEmpty()) viewModel.reload()
    }

    LaunchedEffect(uninstallQueue) {
        val next = uninstallQueue.firstOrNull() ?: return@LaunchedEffect
        val intent = AppIntents.uninstallIntent(next)
        if (intent == null) {
            // A system app: the platform would refuse, so it is skipped rather
            // than left blocking the queue.
            uninstallQueue = uninstallQueue.drop(1)
        } else {
            uninstallLauncher.launch(intent)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbar.showMessage(context, message) { } }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.apps_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = { if (state.selection.isEmpty()) onBack() else viewModel.clearSelection() },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (state.selection.isEmpty()) {
                        IconButton(onClick = viewModel::toggleSort) {
                            Icon(
                                imageVector = Icons.Filled.Sort,
                                contentDescription = stringResource(
                                    if (state.sortBySize) R.string.apps_sort_name else R.string.apps_sort_size
                                ),
                            )
                        }
                        IconButton(onClick = { viewModel.setSystemIncluded(!state.systemIncluded) }) {
                            Icon(
                                imageVector = if (state.systemIncluded) Icons.Filled.Close else Icons.Filled.Android,
                                contentDescription = stringResource(R.string.apps_system_toggle),
                            )
                        }
                        IconButton(onClick = { viewModel.selectAll(visible) }) {
                            Icon(
                                imageVector = Icons.Filled.SelectAll,
                                contentDescription = stringResource(R.string.cd_select_all),
                            )
                        }
                    } else {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(
                                imageVector = Icons.Filled.Deselect,
                                contentDescription = stringResource(R.string.action_deselect_all),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.selection.isNotEmpty() || state.exporting) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.apps_selected, state.selection.size),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = state.progress ?: stringResource(R.string.apps_export_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (state.exporting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            OutlinedButton(
                                onClick = {
                                    uninstallQueue = state.apps.filter {
                                        it.packageName in state.selection && !it.isSystem
                                    }
                                },
                                enabled = state.apps.any {
                                    it.packageName in state.selection && !it.isSystem
                                },
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.apps_uninstall))
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { showPicker = true },
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.apps_export))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingState(label = stringResource(R.string.state_loading))

                visible.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Android,
                    title = stringResource(R.string.apps_none),
                    body = stringResource(R.string.apps_none_hint),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        // What the list adds up to, and what it does not: the
                        // package size is the APKs, not the app's data, which
                        // only the system can measure.
                        Text(
                            text = stringResource(
                                R.string.apps_summary,
                                visible.size,
                                Formatters.bytes(viewModel.visibleBytes(visible)),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    items(visible, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            selected = app.packageName in state.selection,
                            onClick = { viewModel.toggle(app.packageName) },
                            onOpen = { AppIntents.open(context, app) },
                            onUninstall = { uninstallQueue = listOf(app) },
                            onDetails = { AppIntents.details(context, app) },
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        FolderPickerSheet(
            startPath = viewModel.defaultDestination(),
            title = stringResource(R.string.apps_pick_destination),
            onDismiss = { showPicker = false },
            onPick = { path ->
                viewModel.export(path)
                showPicker = false
            },
        )
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    selected: Boolean,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onUninstall: () -> Unit,
    onDetails: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onClick() })
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(Formatters.bytes(app.sizeBytes))
                    if (app.versionName.isNotBlank()) append(" · ").append(app.versionName)
                    if (app.splitCount > 0) append(" · ").append(app.splitCount).append(" split")
                    if (app.isSystem) append(" · ").append("system")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.cd_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_open)) },
                    leadingIcon = { Icon(Icons.Filled.OpenInNew, contentDescription = null) },
                    onClick = {
                        menu = false
                        onOpen()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_info)) },
                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    onClick = {
                        menu = false
                        onDetails()
                    },
                )
                // Offered only where it can work: a system app cannot be
                // removed, and a button that opens a refusal is not a feature.
                if (!app.isSystem) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.apps_uninstall),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menu = false
                            onUninstall()
                        },
                    )
                }
            }
        }
    }
}
