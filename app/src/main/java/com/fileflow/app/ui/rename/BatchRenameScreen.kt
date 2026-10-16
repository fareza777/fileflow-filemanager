package com.fileflow.app.ui.rename

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fileflow.app.R
import com.fileflow.app.domain.rename.NameTemplate
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.ProgressPanel
import com.fileflow.app.ui.components.SectionCard
import com.fileflow.app.ui.components.SectionHeader
import com.fileflow.app.ui.util.showMessage

/**
 * The placeholders, taken from the renderer's own help line rather than typed
 * out again: a chip that offers a token the renderer does not know is worse
 * than no chip at all.
 */
private val TOKENS: List<String> = NameTemplate.PLACEHOLDER_HELP.split(' ')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchRenameScreen(
    onBack: () -> Unit,
    viewModel: BatchRenameViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            // The group to undo is the one the ViewModel is holding, not the one
            // the snackbar names: it is cleared the moment an undo starts, so a
            // second tap on a stale snackbar cannot repeat the reversal.
            snackbar.showMessage(context, message) { viewModel.undo() }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.batch_rename_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    // Offered for as long as the last run can still be walked
                    // back, which is what the journal group id means.
                    if (state.lastGroupId != null) {
                        IconButton(onClick = viewModel::undo) {
                            Icon(
                                imageVector = Icons.Filled.Undo,
                                contentDescription = stringResource(R.string.cd_undo),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.hasFiles) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Button(
                        onClick = viewModel::apply,
                        enabled = state.canApply,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            pluralStringResource(
                                R.plurals.batch_rename_apply,
                                state.renamable,
                                state.renamable,
                            )
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.running -> ProgressPanel(
                    title = stringResource(R.string.op_renaming),
                    detail = state.progressDetail,
                    fraction = state.progress,
                    modifier = Modifier.padding(16.dp),
                )

                !state.hasFiles -> EmptyState(
                    icon = Icons.Filled.DriveFileRenameOutline,
                    title = stringResource(R.string.batch_rename_none),
                    body = stringResource(R.string.batch_rename_none_hint),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.batch_rename_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }

                    item { PatternCard(state = state, viewModel = viewModel) }

                    item {
                        SectionHeader(
                            title = stringResource(R.string.batch_rename_preview),
                            trailing = {
                                Text(
                                    text = stringResource(
                                        R.string.batch_rename_showing,
                                        state.preview.size,
                                        state.total,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }

                    items(state.preview, key = { it.path }) { row ->
                        PreviewCard(row = row)
                    }

                    if (state.unchanged > 0) {
                        item {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.batch_rename_unchanged,
                                    state.unchanged,
                                    state.unchanged,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }

                    item { Spacer(Modifier.size(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PatternCard(
    state: BatchRenameState,
    viewModel: BatchRenameViewModel,
) {
    SectionCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = state.pattern,
                onValueChange = viewModel::setPattern,
                label = { Text(stringResource(R.string.batch_rename_pattern)) },
                // The same help line the rule editor shows, because it is the
                // same set of placeholders.
                supportingText = { Text(NameTemplate.PLACEHOLDER_HELP) },
                singleLine = true,
                isError = state.blocked > 0,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.batch_rename_tokens),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TOKENS.forEach { token ->
                    AssistChip(
                        onClick = { viewModel.appendToken(token) },
                        label = { Text(token) },
                    )
                }
            }

            if (state.unknownTokens.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.batch_rename_unknown_tokens,
                        state.unknownTokens.joinToString(", ") { "{$it}" },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.blocked > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.batch_rename_blocked,
                        state.blocked,
                        state.blocked,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(row: RenamePreviewRow) {
    SectionCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.currentName,
                    style = MaterialTheme.typography.bodyMedium,
                    // A name that is about to go reads as the one being
                    // replaced; a name that stays is left alone.
                    textDecoration = if (row.blocked || row.unchanged) {
                        null
                    } else {
                        TextDecoration.LineThrough
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = row.newName.ifBlank {
                        stringResource(R.string.batch_rename_empty_name)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = when {
                        row.blocked -> MaterialTheme.colorScheme.error
                        row.unchanged -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (row.unchanged) {
                Text(
                    text = stringResource(R.string.batch_rename_row_unchanged),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = row.path.substringBeforeLast('/'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
