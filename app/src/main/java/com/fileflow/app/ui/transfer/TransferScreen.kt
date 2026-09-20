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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransferUiState(
    val addresses: List<TransferAddress> = emptyList(),
    val shareRoot: String = "",
    val portText: String = HttpFileServer.DEFAULT_PORT.toString(),
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
    val copied = stringResource(R.string.transfer_copied)
    val scope = rememberCoroutineScope()

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
