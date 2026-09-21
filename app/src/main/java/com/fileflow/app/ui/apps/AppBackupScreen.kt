package com.fileflow.app.ui.apps

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
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
        viewModelScope.launch {
            val apps = backup.installed()
            _state.update { it.copy(loading = false, apps = apps) }
        }
    }

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
        get() = _state.value.apps.filter { _state.value.systemIncluded || !it.isSystem }

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
                    items(visible, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            selected = app.packageName in state.selection,
                            onClick = { viewModel.toggle(app.packageName) },
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
) {
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
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
                    append(app.packageName)
                    if (app.versionName.isNotBlank()) append(" · ").append(app.versionName)
                    if (app.splitCount > 0) append(" · ").append(app.splitCount).append(" split")
                    if (app.isSystem) append(" · system")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = Formatters.bytes(app.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
