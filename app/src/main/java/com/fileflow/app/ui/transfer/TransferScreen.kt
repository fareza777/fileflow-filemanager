package com.fileflow.app.ui.transfer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.fileflow.app.core.delta.DeltaClient
import com.fileflow.app.core.delta.DeltaPlan
import com.fileflow.app.core.delta.DeltaTransfer
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.prefs.SettingsRepository
import com.fileflow.app.domain.transfer.HttpFileServer
import com.fileflow.app.domain.transfer.TransferAddress
import com.fileflow.app.domain.transfer.TransferLink
import com.fileflow.app.domain.transfer.TransferServerState
import com.fileflow.app.service.TransferService
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.SectionCard
import com.fileflow.app.ui.components.SectionHeader
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import okhttp3.OkHttpClient

data class TransferUiState(
    val addresses: List<TransferAddress> = emptyList(),
    val shareRoot: String = "",
    val portText: String = HttpFileServer.DEFAULT_PORT.toString(),
    // The other end of a delta sync: a device running this same screen, with its
    // own address and token.
    val peerHost: String = "",
    val peerToken: String = "",
    val peerPath: String = "",
    val destination: String = "",
    val checking: Boolean = false,
    val syncing: Boolean = false,
    val plan: DeltaPlan? = null,
    val planError: Boolean = false,
)

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val server: HttpFileServer,
    private val volumes: VolumeRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val serverState: StateFlow<TransferServerState> = server.state

    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            val root = volumes.primaryRoot()
            _state.update { it.copy(shareRoot = root, addresses = server.addresses()) }
        }
        viewModelScope.launch {
            // The shared folder is a setting, not screen state: the rest of the
            // app offers it as a destination ("move to the PC folder").
            settings.settings.collect { prefs ->
                val saved = prefs.transferFolder.ifBlank { volumes.primaryRoot() }
                _state.update { it.copy(shareRoot = saved) }
            }
        }
    }

    /** The address to open, token included. */
    fun pageUrl(host: String): String = server.pageUrl(host)

    fun refreshAddresses() {
        _state.update { it.copy(addresses = server.addresses()) }
    }

    fun setPort(text: String) {
        if (text.length <= 5 && text.all { it.isDigit() }) {
            _state.update { it.copy(portText = text) }
        }
    }

    fun setShareRoot(context: android.content.Context, path: String) {
        _state.update { it.copy(shareRoot = path) }
        viewModelScope.launch {
            settings.setTransferFolder(path)
            if (serverState.value.running) {
                // Changing the shared folder while the server is up restarts it
                // on the new root, so the page never shows the old tree.
                val port = serverState.value.port
                server.stop()
                TransferService.start(context = context, root = path, port = port)
            }
        }
    }

    /** The service owns the server, which is what keeps it alive off-screen. */
    fun start(context: android.content.Context) {
        refreshAddresses()
        TransferService.start(
            context = context,
            root = _state.value.shareRoot,
            port = _state.value.portText.toIntOrNull() ?: HttpFileServer.DEFAULT_PORT,
        )
    }

    fun stop(context: android.content.Context) {
        TransferService.stop(context)
    }

    fun setPeerHost(text: String) {
        _state.update { it.copy(peerHost = text.trim(), plan = null, planError = false) }
    }

    fun setPeerToken(text: String) {
        _state.update { it.copy(peerToken = text.trim(), plan = null, planError = false) }
    }

    fun setPeerPath(text: String) {
        _state.update { it.copy(peerPath = text.trim(), plan = null, planError = false) }
    }

    fun setDestination(path: String) {
        _state.update { it.copy(destination = path, plan = null, planError = false) }
    }

    /** The file this sync would write on this side. */
    private fun target(): File? {
        val name = _state.value.peerPath.trimEnd('/').substringAfterLast('/')
        val folder = _state.value.destination
        if (name.isEmpty() || folder.isEmpty()) return null
        return File(folder, name)
    }

    private fun client(): DeltaClient? {
        val host = _state.value.peerHost
        val token = _state.value.peerToken
        if (host.isEmpty() || token.isEmpty()) return null
        val base = if (host.startsWith("http")) host else "http://$host"
        return DeltaClient(base, token, OkHttpClient())
    }

    /**
     * Works out what would move, without moving anything.
     *
     * The number is the whole point of the feature, so it is shown before the
     * transfer rather than after: a sync that is going to move 4 MB of a 400 MB
     * file should be allowed to say so first.
     */
    fun checkPeer() {
        val client = client() ?: return
        val target = target() ?: return
        _state.update { it.copy(checking = true, plan = null, planError = false) }
        viewModelScope.launch {
            val remote = withContext(Dispatchers.IO) { client.fetchManifest(_state.value.peerPath) }
            if (remote == null) {
                _state.update { it.copy(checking = false, planError = true) }
                return@launch
            }
            val plan = DeltaTransfer.plan(target, remote)
            _state.update { it.copy(checking = false, plan = plan) }
        }
    }

    fun pull() {
        val client = client() ?: return
        val target = target() ?: return
        _state.update { it.copy(syncing = true) }
        viewModelScope.launch {
            val plan = withContext(Dispatchers.IO) { client.pull(_state.value.peerPath, target) }
            _state.update { it.copy(syncing = false, plan = plan ?: it.plan, planError = plan == null) }
            _messages.send(
                if (plan != null) {
                    UiMessage.Info("Synced ${target.name}")
                } else {
                    UiMessage.Info("The sync did not finish")
                }
            )
        }
    }
}

/**
 * The phone as a file server, with the address to type on the other device.
 *
 * Every local address is listed rather than the first one found, because the
 * useful one depends on where the computer is: on the same Wi-Fi it is the
 * Wi-Fi address, on a different network it is the hotspot or USB-tethering
 * address. Picking one for the user would be right half the time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    onBack: () -> Unit,
    viewModel: TransferViewModel = hiltViewModel(),
) {
    val server by viewModel.serverState.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showPicker by remember { mutableStateOf(false) }
    var showDestination by remember { mutableStateOf(false) }
    val copied = stringResource(R.string.transfer_copied)
    val scope = rememberCoroutineScope()

    // The delta fields have to be filled before either button means anything.
    val ready = state.peerHost.isNotEmpty() &&
        state.peerToken.isNotEmpty() &&
        state.peerPath.isNotEmpty() &&
        state.destination.isNotEmpty()

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbar.showMessage(context, message) { } }
    }

    // Deliberately no stop-on-exit: the server is owned by TransferService, so
    // switching tabs, leaving the app or locking the screen keeps the PC
    // connected. The notification in the shade is the way out.
    LaunchedEffect(server.running) {
        if (server.running) viewModel.refreshAddresses()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                StatusCard(
                    running = server.running,
                    port = server.port,
                    error = server.error,
                    addresses = state.addresses,
                    onOpenPage = state.addresses.firstOrNull()?.let { address ->
                        {
                            val view = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(viewModel.pageUrl(address.host)),
                            )
                            runCatching { context.startActivity(view) }
                        }
                    },
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (server.running) {
                                viewModel.stop(context)
                            } else {
                                viewModel.start(context)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            imageVector = if (server.running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (server.running) R.string.transfer_stop else R.string.transfer_start
                            )
                        )
                    }
                    OutlinedButton(
                        onClick = { showPicker = true },
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.transfer_change))
                    }
                }
            }

            if (server.running) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(stringResource(R.string.transfer_addresses))
                        state.addresses.forEach { address ->
                            val link = viewModel.pageUrl(address.host)
                            AddressRow(
                                address = address,
                                url = link,
                                onCopy = {
                                    copyToClipboard(context, link)
                                    scope.launch { snackbar.showSnackbar(copied) }
                                },
                            )
                        }
                        if (state.addresses.isEmpty()) {
                            Text(
                                text = stringResource(R.string.transfer_no_address),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.transfer_share_folder))
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = state.shareRoot,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.transfer_activity))
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile(
                                icon = Icons.Filled.ArrowUpward,
                                label = stringResource(R.string.transfer_sent),
                                value = Formatters.bytes(server.bytesOut),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                icon = Icons.Filled.ArrowDownward,
                                label = stringResource(R.string.transfer_received),
                                value = Formatters.bytes(server.bytesIn),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (server.events.isEmpty()) {
                            Text(
                                text = stringResource(R.string.transfer_log_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            server.events.take(8).forEach { event ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = TIME_FORMAT.format(Date(event.at)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = event.client.ifEmpty { "—" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = buildString {
                                            append(event.summary)
                                            if (event.bytes > 0) {
                                                append(" · ")
                                                append(Formatters.bytes(event.bytes))
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.delta_title))
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.delta_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = state.peerHost,
                            onValueChange = viewModel::setPeerHost,
                            label = { Text(stringResource(R.string.delta_host)) },
                            placeholder = { Text("192.168.1.7:8080") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.peerToken,
                            onValueChange = viewModel::setPeerToken,
                            label = { Text(stringResource(R.string.delta_token)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.peerPath,
                            onValueChange = viewModel::setPeerPath,
                            label = { Text(stringResource(R.string.delta_remote)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = state.destination.ifEmpty {
                                    stringResource(R.string.delta_no_destination)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showDestination = true }) {
                                Text(stringResource(R.string.delta_choose_folder))
                            }
                        }

                        if (state.planError) {
                            Text(
                                text = stringResource(R.string.delta_failed),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        state.plan?.let { plan ->
                            Text(
                                text = stringResource(
                                    R.string.delta_plan,
                                    Formatters.bytes(plan.fetchBytes),
                                    Formatters.bytes(plan.remoteSize),
                                    plan.savedPercent,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = viewModel::checkPeer,
                                enabled = ready && !state.checking && !state.syncing,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.delta_check))
                            }
                            Button(
                                onClick = viewModel::pull,
                                enabled = ready && state.plan != null && !state.syncing,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.delta_pull))
                            }
                        }
                        if (state.checking || state.syncing) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.transfer_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showPicker) {
        FolderPickerSheet(
            startPath = state.shareRoot,
            title = stringResource(R.string.transfer_share_folder),
            onDismiss = { showPicker = false },
            onPick = { path ->
                viewModel.setShareRoot(context, path)
                showPicker = false
            },
        )
    }

    if (showDestination) {
        FolderPickerSheet(
            startPath = state.destination.ifEmpty { state.shareRoot },
            title = stringResource(R.string.delta_destination),
            onDismiss = { showDestination = false },
            onPick = { path ->
                viewModel.setDestination(path)
                showDestination = false
            },
        )
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    port: Int,
    error: String?,
    addresses: List<TransferAddress>,
    onOpenPage: (() -> Unit)? = null,
) {
    val accent = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (running) accent else MaterialTheme.colorScheme.outline),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        if (running) R.string.transfer_running else R.string.transfer_stopped
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (running) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = ":$port",
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                    )
                }
            }
            Text(
                text = if (running) {
                    stringResource(R.string.transfer_background)
                } else {
                    stringResource(R.string.transfer_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (running && onOpenPage != null) {
                // Handy while setting up, and the only way to check the page
                // without a second device.
                TextButton(
                    onClick = onOpenPage,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(stringResource(R.string.transfer_open_page))
                }
            }
            error?.let {
                Text(
                    text = stringResource(R.string.transfer_error, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddressRow(
    address: TransferAddress,
    url: String,
    onCopy: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onCopy),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = labelForLink(address.link),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.transfer_copy),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun labelForLink(link: TransferLink): String = stringResource(
    when (link) {
        TransferLink.WIFI -> R.string.transfer_link_wifi
        TransferLink.HOTSPOT -> R.string.transfer_link_hotspot
        TransferLink.USB -> R.string.transfer_link_usb
        TransferLink.OTHER -> R.string.transfer_link_other
    }
)

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("FileFlow", text))
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
