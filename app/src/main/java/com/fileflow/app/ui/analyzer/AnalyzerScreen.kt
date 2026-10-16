package com.fileflow.app.ui.analyzer

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fileflow.app.core.model.VolumeInfo
import com.fileflow.app.R
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.Candidates
import com.fileflow.app.domain.toFileItem
import com.fileflow.app.ui.components.BreadcrumbBar
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FileVisuals
import com.fileflow.app.ui.components.ProgressPanel
import com.fileflow.app.ui.components.SectionCard
import com.fileflow.app.ui.components.SectionHeader
import com.fileflow.app.ui.components.SelectionAction
import com.fileflow.app.ui.components.SelectionActionBar
import com.fileflow.app.ui.components.StatPill
import com.fileflow.app.ui.components.StorageBar
import com.fileflow.app.ui.components.UsageRow
import com.fileflow.app.ui.util.FileIntents
import com.fileflow.app.ui.util.UiMessage
import com.fileflow.app.ui.util.showMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzerScreen(
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenApps: () -> Unit,
    viewModel: AnalyzerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    /** Non-null while the copy/move destination sheet is open. */
    var pickDestination by remember { mutableStateOf<BulkTransfer?>(null) }
    val settings = com.fileflow.app.ui.util.LocalUserSettings.current

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.showMessage(context, message) { group -> viewModel.undo(group) }
        }
    }

    // Back unwinds in order: first the selection, then the drill-down, and only
    // then the screen. Jumping straight out to another tab made the drill-down
    // a one-way trip, and dropping a careful selection on one stray tap worse.
    BackHandler(enabled = state.selecting || !state.atRoot) {
        if (state.selecting) viewModel.clearSelection() else viewModel.up()
    }

    val rootLabel = stringResource(R.string.analyzer_root)
    val crumbs = remember(state.crumbs, state.atRoot, state.root, rootLabel) {
        if (state.atRoot) {
            emptyList()
        } else {
            listOf(rootLabel to state.root) + state.crumbs.map { it.name to it.path }
        }
    }
    // The share-of-total bars need a denominator; computing it inside the list
    // builder would run it on every item, and inside a LazyColumn item is not a
    // composable scope at all.
    val entryTotal = remember(state.entries) {
        state.entries.sumOf { it.bytes }.coerceAtLeast(1L)
    }
    val largestTotal = remember(state.largest) { largestTotalOf(state.largest) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (state.selecting) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.item_count,
                                state.selection.size,
                                state.selection.size,
                            ),
                            maxLines = 1,
                        )
                    } else {
                        Column {
                            Text(
                                text = if (state.atRoot) {
                                    stringResource(R.string.analyzer_title)
                                } else {
                                    state.focusName
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!state.atRoot && state.result != null) {
                                Text(
                                    text = Formatters.bytes(state.focusBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (!viewModel.up()) onBack() },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (state.selecting) {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_deselect_all),
                            )
                        }
                    } else {
                        // One tap to pick up every row on screen — the mass
                        // selection without having to long-press each one.
                        if (state.entries.isNotEmpty()) {
                            IconButton(onClick = viewModel::selectAll) {
                                Icon(
                                    imageVector = Icons.Filled.SelectAll,
                                    contentDescription = stringResource(R.string.action_select_all),
                                )
                            }
                        }
                        if (!state.atRoot) {
                            IconButton(
                                onClick = { onOpenFolder(state.focusPath) },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FolderOpen,
                                    contentDescription = stringResource(R.string.analyzer_browse),
                                )
                            }
                        }
                        IconButton(onClick = viewModel::refresh) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Stands down while an operation runs: the global operation bar is
            // already showing its progress in this exact spot, and the two
            // stacked on top of each other is the overlap this avoids.
            if (state.selecting && !state.working) {
                SelectionActionBar(
                    title = pluralStringResource(
                        R.plurals.item_count,
                        state.selection.size,
                        state.selection.size,
                    ),
                    subtitle = Formatters.bytes(state.selectedBytes),
                    actions = listOf(
                        SelectionAction(
                            icon = Icons.Filled.Close,
                            label = stringResource(R.string.action_deselect_all),
                            onClick = viewModel::clearSelection,
                        ),
                        SelectionAction(
                            icon = Icons.Filled.Share,
                            label = stringResource(R.string.action_share),
                            onClick = {
                                FileIntents.share(
                                    context,
                                    viewModel.selectedPaths().map { path ->
                                        Candidates.of(java.io.File(path)).toFileItem()
                                    },
                                )
                            },
                        ),
                        SelectionAction(
                            icon = Icons.Filled.ContentCopy,
                            label = stringResource(R.string.action_copy),
                            onClick = { pickDestination = BulkTransfer.COPY },
                        ),
                        SelectionAction(
                            icon = Icons.Filled.DriveFileMove,
                            label = stringResource(R.string.action_move),
                            onClick = { pickDestination = BulkTransfer.MOVE },
                        ),
                        SelectionAction(
                            icon = Icons.Filled.Delete,
                            label = stringResource(R.string.action_delete),
                            onClick = { confirmDelete = true },
                            destructive = true,
                        ),
                    ),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.running) {
                item {
                    ProgressPanel(
                        title = stringResource(R.string.analyzer_computing),
                        detail = stringResource(R.string.analyzer_files, state.scanned),
                        fraction = null,
                        onCancel = viewModel::stop,
                    )
                }
            }

            // Before the first scan the screen was an app bar over an empty
            // body — no prompt, no explanation, just nothing.
            if (!state.running && state.result == null) {
                item {
                    EmptyState(
                        icon = Icons.Filled.PieChart,
                        title = stringResource(R.string.analyzer_title),
                        body = stringResource(R.string.analyzer_empty_hint),
                        action = {
                            Button(onClick = viewModel::refresh) {
                                Text(stringResource(R.string.analyzer_scan))
                            }
                        },
                    )
                }
            }

            val result = state.result
            if (result != null) {
                if (state.atRoot) {
                    if (state.showVolumePicker) {
                        item {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.volumes.forEach { volume ->
                                    FilterChip(
                                        selected = volume.rootPath == state.volumePath ||
                                            (state.volumePath.isBlank() && volume.rootPath == state.root),
                                        onClick = { viewModel.setVolume(volume.rootPath) },
                                        label = { Text(volume.label) },
                                    )
                                }
                            }
                        }
                    }

                    item {
                        SectionCard {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatPill(
                                    label = stringResource(R.string.analyzer_total),
                                    value = Formatters.bytes(result.totalBytes),
                                    modifier = Modifier.weight(1f),
                                )
                                StatPill(
                                    label = stringResource(R.string.common_files),
                                    value = result.totalFiles.toString(),
                                    modifier = Modifier.weight(1f),
                                    accent = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            if (result.volumeTotal > 0) {
                                Spacer(Modifier.height(14.dp))
                                StorageBar(
                                    usedBytes = result.volumeTotal - result.volumeFree,
                                    totalBytes = result.volumeTotal,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = stringResource(
                                        R.string.home_free,
                                        Formatters.bytes(result.volumeFree),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Apps are not part of the file walk — they live in /data,
                    // which no file manager can read — so they get their own
                    // line rather than being folded into a total that would
                    // then be wrong in a way nobody could explain.
                    state.apps?.let { apps ->
                        item {
                            AppsCard(
                                summary = apps,
                                onClick = onOpenApps,
                            )
                        }
                    }
                } else {
                    item {
                        SectionCard(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                            BreadcrumbBar(
                                segments = crumbs,
                                onJump = { path ->
                                    if (path == state.root) viewModel.jumpTo(state.root)
                                    else viewModel.jumpTo(path)
                                },
                            )
                        }
                    }
                }

                if (result.categories.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.analyzer_by_category)) }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            item {
                                FilterChip(
                                    selected = state.category == null,
                                    onClick = { viewModel.setCategory(null) },
                                    label = { Text(stringResource(R.string.search_any)) },
                                )
                            }
                            items(
                                result.categories,
                                key = { it.category.name },
                            ) { usage ->
                                FilterChip(
                                    selected = state.category == usage.category,
                                    onClick = { viewModel.setCategory(usage.category) },
                                    label = {
                                        Text(
                                            text = "${labelForCategory(usage.category)} · ${
                                                Formatters.bytes(usage.bytes)
                                            }"
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                val categoryFilter = state.category
                item {
                    SectionHeader(
                        title = when {
                            !state.atRoot -> state.focusName
                            categoryFilter != null -> labelForCategory(categoryFilter)
                            else -> stringResource(R.string.analyzer_by_folder)
                        },
                        trailing = {
                            if (state.entries.isNotEmpty()) {
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.item_count,
                                        state.entries.size,
                                        state.entries.size,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }

                if (state.entries.isEmpty() && !state.loadingEntries) {
                    item {
                        SectionCard {
                            Text(
                                text = stringResource(R.string.analyzer_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // One LazyColumn item per row, not one item holding the whole
                // list. Wrapping every entry in a single card meant a folder
                // with two hundred children composed and measured all of them
                // before the first frame — which is exactly what showed up as
                // dropped frames while the scan was still reporting progress.
                val total = entryTotal
                items(state.entries, key = { it.path }) { entry ->
                    val accent = if (entry.isDirectory) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        FileVisuals.accentFor(categoryOfExtension(entry.name))
                    }
                    UsageRow(
                        name = entry.name,
                        subtitle = when {
                            // Filtered rows are described by the category being
                            // filtered, not by a total that would not match what
                            // is on screen.
                            entry.filtered -> categoryLabel(
                                state.category ?: categoryOfExtension(entry.name)
                            )
                            entry.isDirectory && entry.measured ->
                                pluralStringResource(
                                    R.plurals.file_count,
                                    entry.fileCount,
                                    entry.fileCount,
                                )
                            entry.isDirectory ->
                                stringResource(R.string.analyzer_unknown_size)
                            else -> categoryLabel(categoryOfExtension(entry.name))
                        },
                        sizeLabel = if (entry.measured) {
                            Formatters.bytes(entry.bytes)
                        } else {
                            "—"
                        },
                        fraction = entry.bytes.toFloat() / total.toFloat(),
                        accent = accent,
                        icon = if (entry.isDirectory) {
                            Icons.Filled.Folder
                        } else {
                            Icons.AutoMirrored.Filled.InsertDriveFile
                        },
                        muted = !entry.measured,
                        showChevron = entry.isDirectory,
                        selected = entry.path in state.selection,
                        selectable = state.selecting,
                        onClick = if (state.selecting) {
                            { viewModel.toggleSelected(entry.path) }
                        } else if (entry.isDirectory) {
                            { viewModel.open(entry) }
                        } else {
                            {
                                FileIntents.open(
                                    context,
                                    Candidates.of(java.io.File(entry.path)).toFileItem(),
                                )
                            }
                        },
                        // Long-press is what opens the selection; every row
                        // offers it, so there is no mode to enter first.
                        onLongClick = { viewModel.startSelection(entry.path) },
                    )
                }

                if (state.atRoot && state.largest.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.analyzer_largest)) }
                    val largestShare = largestTotal
                    items(state.largest.take(10), key = { it.path }) { file ->
                        val category = categoryOfExtension(file.name)
                        UsageRow(
                            name = file.name,
                            subtitle = file.path.substringBeforeLast('/').substringAfterLast('/'),
                            sizeLabel = Formatters.bytes(file.sizeBytes),
                            fraction = file.sizeBytes.toFloat() / largestShare.toFloat(),
                            accent = FileVisuals.accentFor(category),
                            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                            showChevron = true,
                            selected = file.path in state.selection,
                            selectable = state.selecting,
                            onClick = if (state.selecting) {
                                { viewModel.toggleSelected(file.path) }
                            } else {
                                { viewModel.revealFile(file) }
                            },
                            onLongClick = { viewModel.startSelection(file.path) },
                        )
                    }
                    item {
                        Text(
                            text = stringResource(R.string.analyzer_tap_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = pluralStringResource(
                R.plurals.delete_title,
                state.selection.size,
                state.selection.size,
            ),
            body = stringResource(R.string.delete_body, settings.trashRetentionDays),
            confirmLabel = stringResource(R.string.delete_move_to_trash),
            onConfirm = {
                viewModel.deleteSelection()
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }

    pickDestination?.let { transfer ->
        DestinationDialog(
            title = stringResource(
                if (transfer == BulkTransfer.COPY) R.string.analyzer_copy_to
                else R.string.analyzer_move_to,
            ),
            options = viewModel.quickDestinations(),
            onPick = { path ->
                if (transfer == BulkTransfer.COPY) viewModel.copySelection(path)
                else viewModel.moveSelection(path)
                pickDestination = null
            },
            onDismiss = { pickDestination = null },
        )
    }
}

/** Which bulk transfer the destination sheet is picking a folder for. */
private enum class BulkTransfer { COPY, MOVE }

/**
 * Where the checked files go. The standard folders of their own volume, so
 * "Copy" is two taps and no typing — a full folder browser is the Files tab's
 * job, not this screen's.
 */
@Composable
private fun DestinationDialog(
    title: String,
    options: List<FolderOption>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(option.path) }
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = option.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        confirmButton = {},
    )
}


/**
 * Installed apps, as a line the storage screen can open.
 *
 * Deliberately separate from the file breakdown above it: the walk measures
 * what is readable on storage, and an app's package lives in /data where no
 * file manager can look. Adding the two together would produce a total that is
 * neither the storage used nor the apps installed.
 */
@Composable
private fun AppsCard(summary: AppSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.analyzer_apps),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    R.string.analyzer_apps_detail,
                    summary.count,
                    Formatters.bytes(summary.bytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun categoryOfExtension(name: String): FileCategory =
    com.fileflow.app.core.util.MimeTypes.categoryOf(
        com.fileflow.app.core.util.MimeTypes.extensionOf(name)
    )

private fun largestTotalOf(files: List<Candidate>): Long =
    files.firstOrNull()?.sizeBytes?.coerceAtLeast(1L) ?: 1L

@Composable
private fun categoryLabel(category: FileCategory): String = labelForCategory(category)

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
