package com.fileflow.app.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileflow.app.ui.util.LocalUserSettings
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fileflow.app.R
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.Paths
import com.fileflow.app.domain.toFileItem
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileActionSheet
import com.fileflow.app.ui.components.FileRow
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.SearchField
import com.fileflow.app.ui.util.FileIntents
import com.fileflow.app.ui.util.showMessage
import com.fileflow.app.core.model.FileItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    initialPath: String?,
    onCreateRule: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deviceRoot by viewModel.deviceRoot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val settings = LocalUserSettings.current
    val trashDays = settings.trashRetentionDays

    var showDelete by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<FileItem?>(null) }

    LaunchedEffect(initialPath) {
        if (!initialPath.isNullOrBlank()) viewModel.setRoot(initialPath)
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { group -> viewModel.undo(group) }
        }
    }

    // Back unwinds the screen before it leaves it: a selection first, then the
    // screen itself.
    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSort = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = stringResource(R.string.cd_sort))
                        }
                        DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                            SearchSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(labelForSort(option)) },
                                    onClick = {
                                        viewModel.setSort(option)
                                        showSort = false
                                    },
                                )
                            }
                        }
                    }
                    if (state.searching) {
                        IconButton(onClick = viewModel::stopSearch) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        placeholder = stringResource(R.string.search_hint),
                        trailing = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setQuery("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close))
                                }
                            }
                        },
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Where this search is looking. Without it, a search
                        // opened from inside a folder is silently scoped there
                        // and its empty result set reads as "the file is gone".
                        if (state.rootPath.isNotBlank()) {
                            InputChip(
                                selected = true,
                                onClick = { viewModel.clearRoot() },
                                label = {
                                    Text(
                                        text = Paths.display(state.rootPath),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.width(16.dp),
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.search_scope_all),
                                        modifier = Modifier.width(16.dp),
                                    )
                                },
                            )
                        } else {
                            AssistChip(
                                onClick = { },
                                enabled = false,
                                label = { Text(stringResource(R.string.search_whole_device)) },
                            )
                        }
                        AssistChip(
                            onClick = { viewModel.toggleDeep() },
                            label = {
                                Text(
                                    if (state.deep) stringResource(R.string.search_deep)
                                    else stringResource(R.string.search_any)
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.width(16.dp),
                                )
                            },
                        )
                        AssistChip(
                            onClick = { onCreateRule(state.query) },
                            label = { Text(stringResource(R.string.search_save_as_rule)) },
                        )
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FileCategory.entries.forEach { category ->
                            FilterChip(
                                selected = category in state.filters.categories,
                                onClick = { viewModel.toggleCategory(category) },
                                label = { Text(labelForCategory(category)) },
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (state.searching) {
                                if (state.indexed) {
                                    stringResource(R.string.search_scanning, state.results.size)
                                } else {
                                    stringResource(R.string.search_live_scan)
                                }
                            } else {
                                pluralStringResource(
                                    R.plurals.result_count,
                                    state.results.size,
                                    state.results.size,
                                )
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Answering from a stale index is the one case where a
                        // correct-looking result set can be wrong, so its age is
                        // shown and the fix is one tap away.
                        val stale = state.indexed && state.indexAge != null &&
                            System.currentTimeMillis() - (state.indexAge ?: 0L) > STALE_AFTER_MS
                        if (stale) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(
                                        R.string.search_index_stale,
                                        Formatters.relative(state.indexAge ?: 0L),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.search_rescan),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = !state.reindexing) { viewModel.reindex() }
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    if (state.selectionMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.browser_selected_count,
                                    state.selection.size,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = Formatters.bytes(state.selectedBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(onClick = viewModel::selectAll) {
                                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.cd_select_all))
                            }
                            IconButton(onClick = { showPicker = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.DriveFileMove,
                                    contentDescription = stringResource(R.string.cd_move),
                                )
                            }
                            IconButton(onClick = {
                                if (settings.confirmDelete) showDelete = true
                                else viewModel.deleteSelected(permanent = !settings.useTrash)
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete))
                            }
                            IconButton(onClick = viewModel::clearSelection) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close))
                            }
                        }
                    }
                }

                if (state.results.isEmpty() && !state.searching) {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.search_empty),
                        body = stringResource(R.string.search_empty_hint),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(state.results, key = { it.path }) { candidate ->
                            // Converting allocates; doing it per frame for every
                            // visible row is pure garbage churn.
                            val item = remember(candidate.path) { candidate.toFileItem() }
                            FileRow(
                                modifier = Modifier.animateItem(),
                                item = item,
                                selectionMode = state.selectionMode,
                                selected = candidate.path in state.selection,
                                subtitle = "${Formatters.bytes(candidate.sizeBytes)} · ${
                                    Formatters.relative(candidate.lastModified)
                                } · ${Paths.display(candidate.path.substringBeforeLast('/'))}",
                                onClick = {
                                    if (state.selectionMode) {
                                        viewModel.toggleSelection(candidate.path)
                                    } else if (candidate.isDirectory) {
                                        onOpenFolder(candidate.path)
                                    } else {
                                        FileIntents.open(context, item)
                                    }
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.toggleSelection(candidate.path)
                                },
                                trailing = {
                                    IconButton(onClick = { actionTarget = item }) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = stringResource(R.string.cd_more),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Progress is rendered by the root-level operation bar: it stays
            // visible when the user navigates away, which a panel owned by this
            // screen could not.
        }
    }

    actionTarget?.let { item ->
        FileActionSheet(
            item = item,
            onDismiss = { actionTarget = null },
            onOpen = {
                FileIntents.open(context, item)
                actionTarget = null
            },
            onOpenWith = {
                FileIntents.openWith(context, item)
                actionTarget = null
            },
            onShowInFolder = {
                onOpenFolder(item.path.substringBeforeLast('/'))
                actionTarget = null
            },
            onShare = {
                FileIntents.share(context, listOf(item))
                actionTarget = null
            },
            onSelect = {
                viewModel.toggleSelection(item.path)
                actionTarget = null
            },
            onDelete = {
                // Deleting from the long-press menu acted on whatever happened
                // to be selected — and never asked. With the trash off that
                // permanently removes a set of files the user never picked.
                if (state.selection.size != 1 || !state.selection.contains(item.path)) {
                    viewModel.clearSelection()
                    viewModel.toggleSelection(item.path)
                }
                if (settings.confirmDelete) {
                    showDelete = true
                } else {
                    viewModel.deleteSelected(permanent = !settings.useTrash)
                }
                actionTarget = null
            },
        )
    }

    if (showDelete) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_title, state.selection.size, state.selection.size),
            body = stringResource(R.string.delete_body, trashDays),
            confirmLabel = stringResource(R.string.delete_move_to_trash),
            onConfirm = {
                viewModel.deleteSelected(permanent = !settings.useTrash)
                showDelete = false
            },
            onDismiss = { showDelete = false },
            destructive = true,
        )
    }

    if (showPicker) {
        FolderPickerSheet(
            startPath = state.rootPath.ifBlank { deviceRoot },
            title = stringResource(R.string.action_move_here),
            onDismiss = { showPicker = false },
            onPick = { destination ->
                viewModel.moveSelectedTo(destination)
                showPicker = false
            },
        )
    }
}

@Composable
private fun labelForCategory(category: FileCategory): String = when (category) {
    FileCategory.IMAGES -> stringResource(R.string.cat_images)
    FileCategory.VIDEOS -> stringResource(R.string.cat_videos)
    FileCategory.AUDIO -> stringResource(R.string.cat_audio)
    FileCategory.DOCUMENTS -> stringResource(R.string.cat_documents)
    FileCategory.ARCHIVES -> stringResource(R.string.cat_archives)
    FileCategory.APPS -> stringResource(R.string.cat_apk)
    FileCategory.CODE -> stringResource(R.string.cat_code)
    FileCategory.OTHER -> stringResource(R.string.cat_other)
}

@Composable
private fun labelForSort(sort: SearchSort): String = when (sort) {
    SearchSort.RELEVANCE -> stringResource(R.string.search_sort_relevance)
    SearchSort.NAME -> stringResource(R.string.browser_sort_name)
    SearchSort.SIZE -> stringResource(R.string.browser_sort_size)
    SearchSort.NEWEST -> stringResource(R.string.browser_sort_date)
}

/**
 * Older than this and the index age is shown. Matches the freshness window the
 * app itself uses to decide whether a background refresh is owed, so the notice
 * never appears on an index the app still considers good enough to trust.
 */
private val STALE_AFTER_MS = com.fileflow.app.data.repo.FileIndexRepository.FRESH_FOR