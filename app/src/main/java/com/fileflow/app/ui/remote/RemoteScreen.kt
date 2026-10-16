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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.domain.remote.RemoteConnection
import com.fileflow.app.domain.remote.RemoteConnectionStore
import com.fileflow.app.domain.remote.RemoteFileSystems
import com.fileflow.app.domain.remote.RemoteKind
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class RemoteListState(
    val loading: Boolean = true,
    val connections: List<RemoteConnection> = emptyList(),
)

@HiltViewModel
class RemoteViewModel @Inject constructor(
    private val store: RemoteConnectionStore,
    private val sources: RemoteFileSystems,
) : ViewModel() {

    private val _state = MutableStateFlow(RemoteListState())
    val state: StateFlow<RemoteListState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.connections.collect { items ->
                _state.update { it.copy(loading = false, connections = items) }
            }
        }
    }

    fun save(connection: RemoteConnection) {
        viewModelScope.launch {
            store.save(connection)
            sources.disconnect(connection.id)
        }
    }

    fun remove(connection: RemoteConnection) {
        viewModelScope.launch {
            store.remove(connection.id)
            sources.disconnect(connection.id)
        }
    }
}

/**
 * The servers the app can reach.
 *
 * Four protocols behind one list and one form, because the questions are the
 * same and the differences are small enough to explain in a label: on S3 the
 * "path" is a bucket and the "user" is an access key, on SMB the "path" is a
 * share. A form per protocol would be four times the screen for the same four
 * answers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    onBack: () -> Unit,
    onOpenConnection: (RemoteConnection) -> Unit,
    viewModel: RemoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RemoteConnection?>(null) }
    var confirmDelete by remember { mutableStateOf<RemoteConnection?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_title)) },
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
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = blankConnection() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.remote_add))
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.connections.isEmpty() && !state.loading) {
                EmptyState(
                    icon = Icons.Filled.CloudQueue,
                    title = stringResource(R.string.remote_empty),
                    body = stringResource(R.string.remote_empty_hint),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.connections, key = { it.id }) { connection ->
                        ConnectionCard(
                            connection = connection,
                            onOpen = { onOpenConnection(connection) },
                            onEdit = { editing = connection },
                            onDelete = { confirmDelete = connection },
                        )
                    }
                }
            }
        }
    }

    editing?.let { connection ->
        ConnectionEditor(
            initial = connection,
            onDismiss = { editing = null },
            onSave = { saved ->
                viewModel.save(saved)
                editing = null
            },
        )
    }

    confirmDelete?.let { connection ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.remote_delete_title)) },
            text = { Text(connection.label) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(connection)
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
private fun ConnectionCard(
    connection: RemoteConnection,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    SectionCard(
        modifier = Modifier.clickable(onClick = onOpen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudQueue,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = connection.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = connection.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

private fun blankConnection(): RemoteConnection = RemoteConnection(
    id = RemoteFileSystems.newId(),
    kind = RemoteKind.WEBDAV,
    label = "",
    secure = true,
)

@Composable
private fun ConnectionEditor(
    initial: RemoteConnection,
    onDismiss: () -> Unit,
    onSave: (RemoteConnection) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial.label.isBlank()) R.string.remote_add else R.string.remote_edit
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RemoteKind.entries.forEach { kind ->
                        FilterChip(
                            selected = draft.kind == kind,
                            onClick = { draft = draft.copy(kind = kind) },
                            label = { Text(kind.name) },
                        )
                    }
                }

                OutlinedTextField(
                    value = draft.label,
                    onValueChange = { draft = draft.copy(label = it) },
                    label = { Text(stringResource(R.string.remote_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.host,
                    onValueChange = { draft = draft.copy(host = it) },
                    label = { Text(stringResource(R.string.remote_host)) },
                    supportingText = { Text(hostHint(draft.kind)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.port.takeIf { it > 0 }?.toString().orEmpty(),
                    onValueChange = { value ->
                        draft = draft.copy(port = value.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0)
                    },
                    label = { Text(stringResource(R.string.remote_port)) },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.remote_port_hint,
                                draft.kind.defaultPort.toString(),
                            )
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.user,
                    onValueChange = { draft = draft.copy(user = it) },
                    label = { Text(userLabel(draft.kind)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The password is the one field here that nobody should be able
                // to read over a shoulder, off a screenshot, or from a screen
                // share while the connection is being set up. It is masked like
                // every other password field, with the eye to check a typo
                // before the connection is saved.
                var secretVisible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = draft.secret,
                    onValueChange = { draft = draft.copy(secret = it) },
                    label = { Text(secretLabel(draft.kind)) },
                    singleLine = true,
                    visualTransformation = if (secretVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { secretVisible = !secretVisible }) {
                            Icon(
                                imageVector = if (secretVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = stringResource(
                                    if (secretVisible) R.string.cd_hide_secret else R.string.cd_show_secret
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.path,
                    onValueChange = { draft = draft.copy(path = it) },
                    label = { Text(pathLabel(draft.kind)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.extra,
                    onValueChange = { draft = draft.copy(extra = it) },
                    label = { Text(extraLabel(draft.kind)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft.kind == RemoteKind.WEBDAV || draft.kind == RemoteKind.S3) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.remote_secure),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = draft.secure,
                            onCheckedChange = { draft = draft.copy(secure = it) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.remote_secret_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val label = draft.label.ifBlank { draft.host.ifBlank { draft.kind.name } }
                    onSave(draft.copy(label = label))
                },
                enabled = draft.host.isNotBlank() || draft.kind == RemoteKind.S3,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun hostHint(kind: RemoteKind): String = when (kind) {
    RemoteKind.WEBDAV -> stringResource(R.string.remote_host_webdav)
    RemoteKind.S3 -> stringResource(R.string.remote_host_s3)
    RemoteKind.SFTP -> stringResource(R.string.remote_host_sftp)
    RemoteKind.SMB -> stringResource(R.string.remote_host_smb)
}

@Composable
private fun userLabel(kind: RemoteKind): String = when (kind) {
    RemoteKind.S3 -> stringResource(R.string.remote_access_key)
    else -> stringResource(R.string.remote_user)
}

@Composable
private fun secretLabel(kind: RemoteKind): String = when (kind) {
    RemoteKind.S3 -> stringResource(R.string.remote_secret_key)
    RemoteKind.SFTP -> stringResource(R.string.remote_password_or_passphrase)
    else -> stringResource(R.string.remote_password)
}

@Composable
private fun pathLabel(kind: RemoteKind): String = when (kind) {
    RemoteKind.S3 -> stringResource(R.string.remote_bucket)
    RemoteKind.SMB -> stringResource(R.string.remote_share)
    else -> stringResource(R.string.remote_start_path)
}

@Composable
private fun extraLabel(kind: RemoteKind): String = when (kind) {
    RemoteKind.S3 -> stringResource(R.string.remote_region)
    RemoteKind.SMB -> stringResource(R.string.remote_domain)
    RemoteKind.SFTP -> stringResource(R.string.remote_key_path)
    RemoteKind.WEBDAV -> stringResource(R.string.remote_extra_none)
}
