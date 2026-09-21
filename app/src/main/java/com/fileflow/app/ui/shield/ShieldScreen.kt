package com.fileflow.app.ui.shield

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.model.SortOrder
import com.fileflow.app.core.storage.StorageVerdict
import com.fileflow.app.core.storage.StorageVerifier
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.domain.recovery.RecoveryShield
import com.fileflow.app.domain.recovery.ShardHeader
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.SectionHeader
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
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

@androidx.compose.runtime.Immutable
data class ShieldState(
    val folder: String = "",
    val files: List<Pair<String, Long>> = emptyList(),
    val selection: Set<String> = emptySet(),
    val dataShards: Int = 4,
    val parityShards: Int = 2,
    val shardFolders: List<String> = emptyList(),
    val recoverable: List<Pair<String, Int>> = emptyList(),
    val busy: Boolean = false,
    val progress: String? = null,
    val verdict: StorageVerdict? = null,
)

@HiltViewModel
class ShieldViewModel @Inject constructor(
    private val browseRepository: BrowseRepository,
    private val shield: RecoveryShield,
    private val verifier: StorageVerifier,
) : ViewModel() {

    private val _state = MutableStateFlow(ShieldState())
    val state: StateFlow<ShieldState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            val root = browseRepository.primaryRoot()
            _state.update { it.copy(folder = root, shardFolders = listOf(root)) }
            refresh()
        }
    }

    fun setFolder(path: String) {
        _state.update { it.copy(folder = path, selection = emptySet()) }
        refresh()
    }

    fun refresh() {
        val folder = _state.value.folder
        viewModelScope.launch {
            val items = browseRepository.listItems(folder, includeHidden = false, sort = SortOrder())
            _state.update { current ->
                current.copy(files = items.filter { !it.isDirectory }.map { it.path to it.sizeBytes })
            }
        }
    }

    fun toggle(path: String) {
        _state.update { current ->
            val selection = if (path in current.selection) current.selection - path else current.selection + path
            current.copy(selection = selection)
        }
    }

    fun setGeometry(data: Int, parity: Int) {
        _state.update { it.copy(dataShards = data, parityShards = parity) }
    }

    /** Writes shards for everything selected, next to the source by default. */
    fun create(destination: String) {
        val files = _state.value.selection.toList()
        if (files.isEmpty()) return
        val data = _state.value.dataShards
        val parity = _state.value.parityShards
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            var created = 0
            var failed = 0
            files.forEach { path ->
                val source = File(path)
                val result = runCatching {
                    shield.create(
                        source = source,
                        destination = File(destination),
                        dataShards = data,
                        parityShards = parity,
                        onProgress = { done, total, name ->
                            _state.update { it.copy(progress = "$name ($done/$total)") }
                        },
                    )
                }
                if (result.isSuccess) created++ else failed++
            }
            _state.update { it.copy(busy = false, progress = null, selection = emptySet()) }
            _messages.send(
                UiMessage.Info(
                    if (failed == 0) {
                        "$created file(s) sharded — any $data of ${data + parity} shards rebuild them"
                    } else {
                        "$created done, $failed failed"
                    }
                )
            )
            refreshShards()
        }
    }

    fun refreshShards() {
        val folder = _state.value.folder
        viewModelScope.launch {
            val groups = withContext(Dispatchers.IO) {
                shield.inspect(File(folder))
                    .groupBy { it.name.substringBefore('.') }
                    .map { (base, shards) -> base to shards.size }
            }
            _state.update { it.copy(recoverable = groups) }
        }
    }

    fun restore(baseName: String, destination: String) {
        val folder = _state.value.folder
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching {
                shield.restore(
                    folder = File(folder),
                    baseName = baseName,
                    destination = File(destination, baseName),
                    onProgress = { done, total, name ->
                        _state.update { it.copy(progress = "$name ($done/$total)") }
                    },
                )
            }
            _state.update { it.copy(busy = false, progress = null) }
            result.fold(
                onSuccess = { file ->
                    _messages.send(UiMessage.Info("Restored ${file.name}"))
                },
                onFailure = { error ->
                    _messages.send(UiMessage.Info(error.message ?: "Could not restore"))
                },
            )
        }
    }

    /** Writes and reads back, to find a card that lies about its size. */
    fun verifyStorage(target: String, megabytes: Int) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, verdict = null, progress = "0 MB") }
            val verdict = runCatching {
                verifier.verify(
                    folder = File(target),
                    limitBytes = megabytes.toLong() * 1024 * 1024,
                    onProgress = { written, verified ->
                        _state.update {
                            it.copy(progress = "${written / (1024 * 1024)} MB written, " +
                                "${verified / (1024 * 1024)} MB verified")
                        }
                    },
                )
            }.getOrElse { error ->
                StorageVerdict(0, 0, 0, false, 0, 0, error.message ?: "The check failed")
            }
            _state.update { it.copy(busy = false, progress = null, verdict = verdict) }
        }
    }
}

/**
 * Two kinds of insurance, side by side.
 *
 * The shield is erasure coding: a file split into shards that can survive
 * losing some of them, which protects against the failure that a copy does not
 * — a card or a stick quietly going bad. The check is the other half: whether
 * the storage you are about to trust is the size it claims, which is the one
 * thing a counterfeit card will never tell you.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldScreen(
    onBack: () -> Unit,
    viewModel: ShieldViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var pickFolder by remember { mutableStateOf(false) }
    var pickDestination by remember { mutableStateOf(false) }
    var pickVerify by remember { mutableStateOf(false) }
    val folderName = state.folder.substringAfterLast('/').ifEmpty { state.folder }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbar.showMessage(context, message) { } }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { pickFolder = true }) {
                        Text(stringResource(R.string.shield_title))
                        Text(
                            text = folderName,
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
                    IconButton(onClick = { pickFolder = true }) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = stringResource(R.string.compress_change_folder),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader(stringResource(R.string.shield_section_create)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GEOMETRIES.forEach { (data, parity) ->
                            FilterChip(
                                selected = state.dataShards == data && state.parityShards == parity,
                                onClick = { viewModel.setGeometry(data, parity) },
                                label = { Text("$data + $parity") },
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.shield_hint, state.dataShards, state.dataShards + state.parityShards),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { pickDestination = true },
                        enabled = state.selection.isNotEmpty() && !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                R.string.shield_create,
                                state.selection.size,
                                state.dataShards + state.parityShards,
                            )
                        )
                    }
                    if (state.busy && state.progress != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(state.progress.orEmpty(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            items(state.files.take(60), key = { it.first }) { (path, size) ->
                val name = path.substringAfterLast('/')
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (path in state.selection) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                        .clickable { viewModel.toggle(path) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = path in state.selection, onCheckedChange = { viewModel.toggle(path) })
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = Formatters.bytes(size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.shield_section_restore)) }
            item {
                if (state.recoverable.isEmpty()) {
                    Text(
                        text = stringResource(R.string.shield_no_shards),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.recoverable.forEach { (base, count) ->
                            OutlinedButton(
                                onClick = { viewModel.restore(base, File(state.folder).parent ?: state.folder) },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.shield_restore, base, count),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.shield_section_check)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.shield_check_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pickVerify = true },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Filled.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.shield_check_run))
                        }
                    }
                    state.verdict?.let { verdict -> VerdictCard(verdict) }
                }
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    if (pickFolder) {
        FolderPickerSheet(
            startPath = state.folder.ifBlank { "/" },
            title = stringResource(R.string.shield_pick_folder),
            onDismiss = { pickFolder = false },
            onPick = { path ->
                viewModel.setFolder(path)
                pickFolder = false
            },
        )
    }

    if (pickDestination) {
        FolderPickerSheet(
            startPath = state.folder.ifBlank { "/" },
            title = stringResource(R.string.shield_pick_destination),
            onDismiss = { pickDestination = false },
            onPick = { path ->
                viewModel.create(path)
                pickDestination = false
            },
        )
    }

    if (pickVerify) {
        FolderPickerSheet(
            startPath = state.folder.ifBlank { "/" },
            title = stringResource(R.string.shield_pick_check),
            onDismiss = { pickVerify = false },
            onPick = { path ->
                viewModel.verifyStorage(path, megabytes = 64)
                pickVerify = false
            },
        )
    }
}

@Composable
private fun VerdictCard(verdict: StorageVerdict) {
    val tint = if (verdict.honest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.08f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (verdict.honest) R.string.shield_check_ok else R.string.shield_check_bad
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
        }
        Text(
            text = if (verdict.claimedBytes > 0) {
                stringResource(
                    R.string.shield_check_detail,
                    Formatters.bytes(verdict.claimedBytes),
                    Formatters.bytes(verdict.writtenBytes),
                    verdict.testFiles,
                )
            } else {
                stringResource(R.string.shield_check_wrote, Formatters.bytes(verdict.writtenBytes))
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        verdict.failure?.let { failure ->
            Text(
                text = failure,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private val GEOMETRIES = listOf(3 to 1, 4 to 2, 5 to 3, 6 to 3)
