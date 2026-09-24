package com.fileflow.app.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fileflow.app.R
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.model.SortField
import com.fileflow.app.core.storage.ArchiveFormat
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.core.util.Paths
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.ConflictDialog
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileActionSheet
import com.fileflow.app.ui.components.FileRow
import com.fileflow.app.ui.components.FileThumb
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.ProgressPanel
import com.fileflow.app.ui.components.folderSubtitle
import com.fileflow.app.domain.privacy.MetadataReport
import com.fileflow.app.domain.privacy.MetadataStripper
import com.fileflow.app.ui.util.FileIntents
import com.fileflow.app.ui.util.FileOpen
import com.fileflow.app.ui.util.LocalUserSettings
import com.fileflow.app.ui.util.PreviewKind
import com.fileflow.app.ui.util.showMessage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    requestedPath: String?,
    onRequestConsumed: () -> Unit,
    onOpenSearch: (String) -> Unit,
    onViewImage: (folder: String, path: String) -> Unit,
    onViewText: (path: String) -> Unit,
    onConvert: (List<String>) -> Unit,
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeOperation by viewModel.activeOperation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val settings = LocalUserSettings.current
    val trashDays = settings.trashRetentionDays

    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var actionTarget by remember { mutableStateOf<FileItem?>(null) }
    var pickerMode by remember { mutableStateOf<PickerMode?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showBreadcrumbs by remember { mutableStateOf(false) }
    var metadataReport by remember { mutableStateOf<Pair<FileItem, MetadataReport>?>(null) }

    val visibleItems = remember(state.items, state.filterCategory) {
        state.filterCategory?.let { category ->
            state.items.filter { it.isDirectory || it.category == category }
        } ?: state.items
    }

    val insideArchive = state.path.contains(BrowserViewModel.ARCHIVE_SEPARATOR)
    val archiveCrumb = remember(state.path) {
        if (!insideArchive) {
            emptyList()
        } else {
            val archiveFile = state.path.substringBefore(BrowserViewModel.ARCHIVE_SEPARATOR)
            val inner = state.path.substringAfter(BrowserViewModel.ARCHIVE_SEPARATOR).trim('/')
            buildList {
                add(archiveFile.substringAfterLast('/') to "$archiveFile${BrowserViewModel.ARCHIVE_SEPARATOR}")
                var accumulated = ""
                inner.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                    accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
                    add(segment to "$archiveFile${BrowserViewModel.ARCHIVE_SEPARATOR}$accumulated")
                }
            }
        }
    }

    /**
     * Inside an archive a tap means one of two things: walk into a folder, or
     * pull that one entry out to the cache and open it. The second is what makes
     * an archive browsable rather than a file that has to be unpacked first.
     */
    fun openInsideArchive(item: FileItem) {
        if (item.isDirectory) {
            viewModel.openFolder(item.path)
            return
        }
        viewModel.materialize(item) { local ->
            when (FileOpen.kindOf(local)) {
                PreviewKind.IMAGE -> onViewImage(local.path.substringBeforeLast('/'), local.path)
                PreviewKind.TEXT -> onViewText(local.path)
                PreviewKind.EXTERNAL ->
                    if (viewModel.isArchive(local)) viewModel.openArchive(local.path)
                    else FileIntents.open(context, local)
            }
        }
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    // Entering a folder should start at the top; staying in one should not.
    LaunchedEffect(state.path) {
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }

    LaunchedEffect(requestedPath) {
        if (requestedPath != null) {
            viewModel.openFolder(requestedPath)
            onRequestConsumed()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { group -> viewModel.undo(group) }
        }
    }

    // Android's back button means "go back one step", and inside a file manager
    // that step is the parent folder. Without this the system popped the whole
    // navigation stack, so backing out of a folder dropped the user somewhere
    // else entirely instead of one level up.
    BackHandler(enabled = state.selectionMode || state.canNavigateUp) {
        if (state.selectionMode) viewModel.clearSelection() else viewModel.navigateUp()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(
                    count = state.selection.size,
                    bytes = state.selectedItems.sumOf { it.sizeBytes },
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onCopy = { viewModel.copyToClipboard(cut = false) },
                    onMove = { viewModel.copyToClipboard(cut = true) },
                    onCompress = viewModel::compressSelection,
                    onConvert = { onConvert(state.selection.toList()) },
                    onShare = {
                        FileIntents.share(context, state.selectedItems)
                        viewModel.clearSelection()
                    },
                    onDelete = {
                        if (settings.confirmDelete) confirmDelete = true
                        else viewModel.deleteSelection(permanent = !settings.useTrash)
                    },
                    // Only offered once a PC folder exists: the action promises
                    // a destination, so it has to have one.
                    onMoveToTransfer = settings.transferFolder.takeIf { it.isNotBlank() }?.let { folder ->
                        { viewModel.moveSelectionTo(folder) }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Box {
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showBreadcrumbs = true }
                                    .padding(vertical = 4.dp),
                            ) {
                                Text(
                                    text = if (insideArchive) {
                                        archiveCrumb.lastOrNull()?.first
                                            ?: state.path.substringBefore("!/").substringAfterLast('/')
                                    } else {
                                        Paths.nameOf(state.path).ifEmpty {
                                            stringResource(R.string.browser_title)
                                        }
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = if (state.loading) {
                                        stringResource(R.string.common_loading)
                                    } else if (insideArchive) {
                                        stringResource(
                                            R.string.browser_in_archive,
                                            state.path.substringBefore("!/").substringAfterLast('/'),
                                        ) + " · " + pluralStringResource(
                                            R.plurals.item_count,
                                            visibleItems.size,
                                            visibleItems.size,
                                        )
                                    } else {
                                        pluralStringResource(
                                            R.plurals.item_count,
                                            visibleItems.size,
                                            visibleItems.size,
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            DropdownMenu(
                                expanded = showBreadcrumbs,
                                onDismissRequest = { showBreadcrumbs = false },
                            ) {
                                val crumbs = if (insideArchive) {
                                    archiveCrumb.reversed()
                                } else {
                                    Paths.breadcrumbSegments(state.path, null).reversed()
                                }
                                crumbs.forEach { (label, path) ->
                                    DropdownMenuItem(
                                        text = { Text(label, maxLines = 1) },
                                        onClick = {
                                            viewModel.openFolder(path)
                                            showBreadcrumbs = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        if (state.canNavigateUp) {
                            IconButton(onClick = viewModel::navigateUp) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { onOpenSearch(state.path) }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search))
                        }
                        IconButton(onClick = viewModel::toggleViewMode) {
                            Icon(
                                imageVector = if (state.grid) {
                                    Icons.Filled.ViewList
                                } else {
                                    Icons.Filled.GridView
                                },
                                contentDescription = stringResource(R.string.cd_view_mode),
                            )
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Filled.Sort, contentDescription = stringResource(R.string.cd_sort))
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                SortField.entries.forEach { field ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(labelForSort(field))
                                                if (state.sort.field == field) {
                                                    Spacer(Modifier.width(8.dp))
                                                    Icon(
                                                        Icons.Filled.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.setSort(field)
                                            showSortMenu = false
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.browser_show_hidden)) },
                                    onClick = {
                                        viewModel.toggleHidden()
                                        showSortMenu = false
                                    },
                                    trailingIcon = {
                                        if (state.showHidden) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    },
                                )
                                if (state.canPaste) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_paste)) },
                                        onClick = {
                                            viewModel.pasteIntoCurrentFolder()
                                            showSortMenu = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.ContentPaste,
                                                contentDescription = null,
                                            )
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.browser_add_to_places)) },
                                    onClick = {
                                        viewModel.addShortcut(state.path)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Star, contentDescription = null)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            // No new folders inside an archive: a zip can be appended to, but not
            // by this screen, and a button that fails is worse than no button.
            if (!state.selectionMode && !state.restricted && !insideArchive) {
                ExtendedFloatingActionButton(
                    onClick = { showNewFolder = true },
                    icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                    text = { Text(stringResource(R.string.browser_new_folder)) },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!state.restricted && state.items.isNotEmpty()) {
                TypeFilterRow(selected = state.filterCategory, onSelect = viewModel::setFilter)
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.restricted -> EmptyState(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.common_forbidden),
                        body = stringResource(R.string.restricted_folder_body),
                    )

                    visibleItems.isEmpty() && !state.loading -> EmptyState(
                        icon = Icons.Filled.CreateNewFolder,
                        title = if (state.items.isEmpty()) {
                            stringResource(R.string.browser_empty)
                        } else {
                            stringResource(R.string.browser_filter_empty)
                        },
                        body = if (state.items.isEmpty()) {
                            stringResource(R.string.browser_empty_hint)
                        } else {
                            null
                        },
                        action = {
                            if (state.items.isEmpty()) {
                                TextButton(onClick = { showNewFolder = true }) {
                                    Text(stringResource(R.string.browser_new_folder))
                                }
                            }
                        },
                    )

                    state.grid -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 108.dp),
                        state = gridState,
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visibleItems, key = { it.path }) { item ->
                            GridTile(
                                item = item,
                                selected = item.path in state.selection,
                                selectionMode = state.selectionMode,
                                thumbnails = state.thumbnails,
                                onClick = {
                                    if (state.selectionMode) {
                                        viewModel.toggleSelection(item.path)
                                    } else if (insideArchive) {
                                        openInsideArchive(item)
                                    } else {
                                        openOrNavigate(context, item, state.path, viewModel, onViewImage, onViewText)
                                    }
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.enterSelection(item.path)
                                },
                            )
                        }
                    }

                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(visibleItems, key = { it.path }) { item ->
                            FileRow(
                                modifier = Modifier.animateItem(),
                                item = item,
                                selectionMode = state.selectionMode,
                                selected = item.path in state.selection,
                                thumbnail = state.thumbnails,
                                subtitle = if (item.isDirectory) folderSubtitle(item) else null,
                                onClick = {
                                    if (state.selectionMode) {
                                        viewModel.toggleSelection(item.path)
                                    } else if (insideArchive) {
                                        openInsideArchive(item)
                                    } else {
                                        openOrNavigate(context, item, state.path, viewModel, onViewImage, onViewText)
                                    }
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.enterSelection(item.path)
                                },
                                trailing = {
                                    IconButton(onClick = { actionTarget = item }) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = stringResource(R.string.cd_more),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                activeOperation?.let { operation ->
                    ProgressPanel(
                        title = when (operation.kind) {
                            OperationKind.COPY -> stringResource(R.string.op_copying)
                            OperationKind.MOVE -> stringResource(R.string.op_moving)
                            OperationKind.TRASH, OperationKind.DELETE ->
                                stringResource(R.string.op_deleting)
                            OperationKind.RENAME -> stringResource(R.string.op_renaming)
                            OperationKind.CREATE_FOLDER ->
                                stringResource(R.string.browser_new_folder)
                            OperationKind.ARCHIVE ->
                                stringResource(R.string.op_archiving)
                            OperationKind.RESTORE ->
                                stringResource(R.string.op_restoring)
                        },
                        detail = operation.progress?.let { progress ->
                            if (progress.total > 1) {
                                "${progress.currentName} · ${progress.current + 1}/${progress.total}"
                            } else {
                                progress.currentName
                            }
                        },
                        fraction = operation.progress?.fraction,
                        onCancel = viewModel::cancelOperation,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp),
                    )
                }

                if (state.canPaste && !state.selectionMode) {
                    PasteBar(
                        isCut = state.clipboard?.isCut == true,
                        count = state.clipboard?.paths?.size ?: 0,
                        onPaste = viewModel::pasteIntoCurrentFolder,
                        onClear = viewModel::clearClipboard,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- overlays

    metadataReport?.let { (item, report) ->
        AlertDialog(
            onDismissRequest = { metadataReport = null },
            title = { Text(stringResource(R.string.metadata_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    if (report.segments.isEmpty()) {
                        Text(stringResource(R.string.metadata_none))
                    } else {
                        Text(stringResource(R.string.metadata_found, report.segments.joinToString(", ")))
                        if (report.hasLocation) {
                            Text(
                                text = stringResource(R.string.metadata_location),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.cleanMetadata(item)
                        metadataReport = null
                    },
                    enabled = report.segments.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.metadata_clean))
                }
            },
            dismissButton = {
                TextButton(onClick = { metadataReport = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showNewFolder) {
        NameDialog(
            title = stringResource(R.string.browser_new_folder),
            label = stringResource(R.string.browser_new_folder_hint),
            initial = "",
            onConfirm = {
                viewModel.createFolder(it)
                showNewFolder = false
            },
            onDismiss = { showNewFolder = false },
        )
    }

    renameTarget?.let { target ->
        NameDialog(
            title = stringResource(R.string.action_rename),
            label = stringResource(R.string.browser_name_hint),
            initial = target.name,
            onConfirm = {
                viewModel.rename(target.path, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    actionTarget?.let { item ->
        FileActionSheet(
            item = item,
            onDismiss = { actionTarget = null },
            onOpen = if (item.isDirectory) null else {
                {
                    if (insideArchive) {
                        openInsideArchive(item)
                    } else {
                        FileIntents.open(context, item)
                    }
                    actionTarget = null
                }
            },
            onOpenWith = if (item.isDirectory) null else {
                {
                    FileIntents.openWith(context, item)
                    actionTarget = null
                }
            },
            onShare = {
                FileIntents.share(context, listOf(item))
                actionTarget = null
            },
            onExtract = when {
                // A row inside an archive: extracting one entry next to the
                // archive is the useful verb, not extracting the archive again.
                insideArchive && !item.isDirectory -> {
                    {
                        viewModel.extractEntry(item)
                        viewModel.clearSelection()
                        actionTarget = null
                    }
                }

                viewModel.isArchive(item) -> {
                    {
                        viewModel.extract(item)
                        actionTarget = null
                    }
                }

                else -> null
            },
            onCleanMetadata = if (!item.isDirectory && MetadataStripper.canStrip(File(item.path))) {
                {
                    val target = item
                    actionTarget = null
                    viewModel.inspectMetadata(target) { report -> metadataReport = target to report }
                }
            } else {
                null
            },
            onRename = {
                renameTarget = item
                actionTarget = null
            },
            onMove = {
                viewModel.enterSelection(item.path)
                viewModel.copyToClipboard(cut = true)
                pickerMode = PickerMode.CLIPBOARD
                actionTarget = null
            },
            onCopy = {
                viewModel.enterSelection(item.path)
                viewModel.copyToClipboard(cut = false)
                pickerMode = PickerMode.CLIPBOARD
                actionTarget = null
            },
            onSelect = {
                viewModel.enterSelection(item.path)
                actionTarget = null
            },
            onStar = {
                viewModel.toggleStar(item)
                actionTarget = null
            },
            isStarred = item.path in state.starredPaths,
            onMoveToTransfer = settings.transferFolder.takeIf { it.isNotBlank() }?.let { folder ->
                {
                    // The move acts on the selection, so the row under the menu
                    // has to become the selection first.
                    viewModel.enterSelection(item.path)
                    viewModel.moveSelectionTo(folder)
                    actionTarget = null
                }
            },
            onDelete = {
                viewModel.enterSelection(item.path)
                // The long-press menu used to delete outright, which walked
                // straight past the "ask before deleting" setting — and with
                // the trash turned off that is an unrecoverable delete.
                if (settings.confirmDelete) {
                    confirmDelete = true
                } else {
                    viewModel.deletePaths(listOf(item.path), permanent = !settings.useTrash)
                }
                actionTarget = null
            },
        )
    }

    if (confirmDelete) {
        val items = state.selectedItems
        ConfirmDialog(
            title = pluralStringResource(
                R.plurals.delete_title,
                items.size,
                items.size,
            ),
            body = stringResource(R.string.delete_body, trashDays),
            confirmLabel = stringResource(R.string.delete_move_to_trash),
            onConfirm = {
                viewModel.deleteSelection(permanent = !settings.useTrash)
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
            extra = {
                Text(
                    text = Formatters.bytes(items.sumOf { it.sizeBytes }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }

    state.pendingConflict?.let { conflict ->
        ConflictDialog(
            conflictingNames = conflict.conflictingNames,
            totalCount = conflict.sources.size,
            destination = conflict.destination,
            onKeepBoth = { viewModel.resolveConflict(ConflictPolicy.RENAME) },
            onOverwrite = { viewModel.resolveConflict(ConflictPolicy.OVERWRITE) },
            onSkip = { viewModel.resolveConflict(ConflictPolicy.SKIP) },
            onDismiss = viewModel::dismissConflict,
        )
    }

    pickerMode?.let { mode ->
        FolderPickerSheet(
            startPath = state.path,
            title = when (mode) {
                PickerMode.COPY -> stringResource(R.string.action_copy_here)
                PickerMode.MOVE -> stringResource(R.string.action_move_here)
                PickerMode.CLIPBOARD -> stringResource(R.string.rule_destination)
            },
            onDismiss = {
                pickerMode = null
                viewModel.clearClipboard()
            },
            onPick = { destination ->
                when (mode) {
                    PickerMode.COPY -> viewModel.copySelectionTo(destination)
                    PickerMode.MOVE -> viewModel.moveSelectionTo(destination)
                    PickerMode.CLIPBOARD -> Unit
                }
                pickerMode = null
            },
        )
    }
}

private enum class PickerMode { COPY, MOVE, CLIPBOARD }

/**
 * Tapping a file opens it; tapping a folder walks into it. This is the one
 * interaction people arrive with, and getting it wrong is what makes a file
 * manager feel broken.
 *
 * Pictures and text open in FileFlow's own viewers, because bouncing out to
 * another app loses the folder you were in and the place you had scrolled to.
 */
private fun openOrNavigate(
    context: android.content.Context,
    item: FileItem,
    folder: String,
    viewModel: BrowserViewModel,
    onViewImage: (String, String) -> Unit,
    onViewText: (String) -> Unit,
) {
    when {
        item.isDirectory -> viewModel.openFolder(item.path)
        // An archive opens as a folder rather than being handed to another app:
        // "what is in this zip" is the question a file manager should answer.
        ArchiveFormat.of(item.name) != null -> viewModel.openArchive(item.path)
        item.category == FileCategory.IMAGES -> onViewImage(folder, item.path)
        item.extension in TEXT_EXTENSIONS -> onViewText(item.path)
        else -> FileIntents.open(context, item)
    }
}

private val TEXT_EXTENSIONS = FileOpen.TEXT_EXTENSIONS

@Composable
private fun TypeFilterRow(
    selected: FileCategory?,
    onSelect: (FileCategory?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.search_any)) },
        )
        FileCategory.entries.filter { it != FileCategory.OTHER }.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
                label = { Text(labelForCategory(category)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    bytes: Long,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onCompress: () -> Unit,
    onConvert: () -> Unit,
    onMoveToTransfer: (() -> Unit)? = null,
) {
    var showMore by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.browser_selected_count, count))
                Text(
                    text = Formatters.bytes(bytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.cd_select_all))
            }
            // Convert, compress and the PC folder all need a second question,
            // so they share the overflow instead of squeezing five more glyphs
            // into a bar a thumb has to hit.
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.cd_copy))
            }
            IconButton(onClick = onMove) {
                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.cd_move))
            }
            Box {
                IconButton(onClick = { showMore = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_more),
                    )
                }
                DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_convert)) },
                        leadingIcon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                        onClick = {
                            showMore = false
                            onConvert()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_compress)) },
                        leadingIcon = { Icon(Icons.Filled.FolderZip, contentDescription = null) },
                        onClick = {
                            showMore = false
                            onCompress()
                        },
                    )
                    if (onMoveToTransfer != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_move_to_transfer)) },
                            leadingIcon = { Icon(Icons.Filled.Devices, contentDescription = null) },
                            onClick = {
                                showMore = false
                                onMoveToTransfer()
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.cd_share))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete))
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTile(
    item: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    thumbnails: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            // Edge to edge: a grid of inset icons reads as a list with padding,
            // not as a gallery.
            FileThumb(
                item = item,
                enabled = thumbnails,
                fill = true,
            )
            if (selectionMode && selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
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

@Composable
private fun PasteBar(
    isCut: Boolean,
    count: Int,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.inverseSurface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (isCut) R.string.clipboard_to_move else R.string.clipboard_to_copy,
                count,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) { Text(stringResource(R.string.action_cancel)) }
        TextButton(onClick = onPaste) { Text(stringResource(R.string.action_paste)) }
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Survives a rotation or a process death: half-typed folder names used to
    // vanish on any configuration change.
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
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

@Composable
private fun labelForSort(field: SortField): String = when (field) {
    SortField.NAME -> stringResource(R.string.browser_sort_name)
    SortField.SIZE -> stringResource(R.string.browser_sort_size)
    SortField.MODIFIED -> stringResource(R.string.browser_sort_date)
    SortField.TYPE -> stringResource(R.string.browser_sort_type)
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