package com.fileflow.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fileflow.app.R
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.ui.components.InlineAction
import com.fileflow.app.ui.components.AdBanner
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.DonutSegment
import com.fileflow.app.ui.components.FileActionSheet
import com.fileflow.app.ui.components.FileRow
import com.fileflow.app.ui.components.FileThumb
import com.fileflow.app.ui.components.ProgressPanel
import com.fileflow.app.ui.components.SectionCard
import com.fileflow.app.ui.components.SectionHeader
import com.fileflow.app.ui.components.FeatureBanner
import com.fileflow.app.ui.components.StorageDonut
import com.fileflow.app.ui.components.TILE_THUMBNAIL_PIXELS
import com.fileflow.app.ui.util.FileIntents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenFolder: (String) -> Unit,
    onOpenTool: (String) -> Unit,
    onPreview: (FileItem) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenRecent: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Read from the lifecycle rather than the ViewModel: access is granted on a
    // system screen, and the only reliable moment to notice is when the app
    // resumes. The banner then disappears on its own the moment the user comes
    // back, without a manual refresh.
    val hasAccess = com.fileflow.app.ui.rememberStorageAccess()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    var actionTarget by remember { mutableStateOf<FileItem?>(null) }
    var placeTarget by remember { mutableStateOf<PlaceEntry?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }
    // Data collected while the gate was closed is empty by definition; reload
    // it the moment access arrives.
    LaunchedEffect(hasAccess) {
        if (hasAccess) viewModel.refresh()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // A compact bar rather than the large one. The large title reserves
            // a band of empty space above the first card, so the dashboard
            // started below the fold of the one screen that opens on purpose.
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    // A labelled pill instead of a bare refresh glyph: the icon
                    // alone read as "reload the page", when what it does is walk
                    // the storage and measure it.
                    ScanButton(
                        scanning = state.scanning,
                        onClick = { viewModel.runScan() },
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { SearchPill(onClick = onOpenSearch) }
            item {
                FeatureBanner(
                    title = stringResource(R.string.photo_discover_title),
                    description = stringResource(R.string.photo_discover_desc),
                    icon = Icons.Filled.Search,
                    onClick = { onOpenTool("photo_search") },
                )
            }

            item {
                // Without access the whole screen is zeros and dashes. Saying so
                // at the top, with the one tap that fixes it, turns a screen
                // that looks broken into one that explains itself.
                if (!hasAccess) {
                    AccessBanner()
                } else {
                    StorageCard(
                        state = state,
                        onScan = viewModel::runScan,
                    )
                }
            }

            // The scan used to report inside the storage card, so its progress
            // bar stacked on top of the "1.56 GB of 9.70 GB" figures and the two
            // read as one broken block. Progress gets its own card.
            if (state.scanning) {
                item {
                    ProgressPanel(
                        title = stringResource(R.string.home_analyzing),
                        detail = pluralStringResource(
                            R.plurals.file_count,
                            state.scannedCount,
                            state.scannedCount,
                        ),
                        fraction = null,
                        onCancel = viewModel::stopScan,
                    )
                }
            }

            // The page reads in one direction from here: how full the storage
            // is, what is worth cleaning, then the files themselves. The
            // quick-action grid that used to sit between the summary and the
            // recents repeated what the summary tiles and the Tools tab already
            // offered, so it is gone and the recents start one scroll sooner.
            item {
                InsightsSection(
                    state = state,
                    onOpenTool = onOpenTool,
                )
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.home_recent),
                    trailing = {
                        InlineAction(
                            text = stringResource(R.string.home_view_all),
                            onClick = onOpenRecent,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
            }

            if (state.recent.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.home_recent_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Photos and videos first, as big tiles you can recognise at a
                // glance — the request was "see the content without opening
                // it", and a 42dp icon does not do that for a camera roll.
                // Everything that is not media keeps the compact row, because
                // nobody recognises a PDF by its pixels.
                val (media, others) = state.recent.partition {
                    it.category == FileCategory.IMAGES || it.category == FileCategory.VIDEOS
                }

                if (media.isNotEmpty()) {
                    item {
                        RecentMediaStrip(
                            items = media,
                            onPreview = onPreview,
                            onLongPress = { item ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                actionTarget = item
                            },
                        )
                    }
                }

                if (others.isNotEmpty()) {
                    item {
                        SectionCard {
                            Column {
                                others.take(MAX_RECENT_ROWS).forEach { item ->
                                    FileRow(
                                        item = item,
                                        // Photos and videos show the pixels, every
                                        // other type its own icon: a "recent" list
                                        // you cannot recognise is a list of names.
                                        thumbnail = true,
                                        subtitle = "${Formatters.bytes(item.sizeBytes)} · ${
                                            Formatters.relative(item.lastModified)
                                        } · ${
                                            item.path.substringBeforeLast('/').substringAfterLast('/')
                                        }",
                                        // A tap previews: photos and text open in the
                                        // app's own viewers, everything else goes out
                                        // to the system. It used to always leave the
                                        // app, even for a screenshot.
                                        onClick = { onPreview(item) },
                                        onLongClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            actionTarget = item
                                        },
                                        trailing = {
                                            IconButton(onClick = { actionTarget = item }) {
                                                Icon(
                                                    imageVector = Icons.Filled.MoreVert,
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
                    }
                }
            }

            if (state.places.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.home_shortcuts)) }
                item {
                    // One row of chips rather than a card of nine rows: the same
                    // one-tap destinations, without a block of scrolling that
                    // duplicated the root of the Files tab.
                    PlacesRow(
                        places = state.places,
                        isShortcut = viewModel::isShortcut,
                        onOpen = onOpenFolder,
                        onLongPress = { placeTarget = it },
                    )
                }
            }

            item { Spacer(Modifier.height(4.dp)) }
            item { AdBanner() }
        }
    }

    actionTarget?.let { item ->
        FileActionSheet(
            item = item,
            onDismiss = { actionTarget = null },
            onOpen = {
                onPreview(item)
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
            onDetails = {
                onOpenFolder(item.path.substringBeforeLast('/'))
                actionTarget = null
            },
        )
    }

    placeTarget?.let { place ->
        ConfirmDialog(
            title = place.name,
            body = place.path,
            confirmLabel = if (viewModel.isShortcut(place.path)) {
                stringResource(R.string.action_remove_shortcut)
            } else {
                stringResource(R.string.home_add_shortcut)
            },
            onConfirm = {
                viewModel.toggleShortcutForPlace(place.path)
                placeTarget = null
            },
            onDismiss = { placeTarget = null },
        )
    }
}

/**
 * How many recent files are listed as rows under the media strip. The strip is
 * the part people actually scan; the rows are the tail of the same list, so they
 * are capped rather than left to push the shortcuts and the ad off the page.
 */
private const val MAX_RECENT_ROWS = 5

/**
 * Search, where the thumb already is.
 *
 * Not a text field: the search screen owns the query, its scope and its
 * filters, and a field here would either duplicate that state or hand off
 * half-typed text. One tap, one screen that does the job.
 */
@Composable
private fun SearchPill(onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The scan control, spelled out. While the walk is running the glyph becomes a
 * spinner and the tap is disabled: a second scan on top of a running one is the
 * one thing this button must not do.
 */
@Composable
private fun ScanButton(scanning: Boolean, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(50)
    Surface(
        modifier = Modifier
            .padding(end = 12.dp)
            .clip(shape)
            .clickable(enabled = !scanning, onClick = onClick),
        shape = shape,
        color = tint.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = tint,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = stringResource(R.string.home_scan_action),
                style = MaterialTheme.typography.labelLarge,
                color = tint,
            )
        }
    }
}

@Composable
private fun AccessBanner() {
    val context = LocalContext.current
    val requestLegacy = com.fileflow.app.ui.rememberStoragePermissionRequest()
    // Tonal rather than alarming: this is a step the user chose to skip, not an
    // error the app ran into. Warm surface, clear copy, one action.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_no_access_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.home_no_access_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    com.fileflow.app.ui.requestAllFilesAccess(context)
                } else {
                    requestLegacy()
                }
            }) {
                Text(stringResource(R.string.home_grant_access))
            }
        }
    }
}

@Composable
private fun StorageCard(
    state: HomeState,
    onScan: () -> Unit,
) {
    val volume = state.primaryVolume
    val used = volume?.usedBytes ?: 0L
    val total = volume?.totalBytes ?: 0L
    val usedFraction = if (total > 0) used.toFloat() / total.toFloat() else 0f

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.home_storage))
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StorageDonut(
                    segments = listOf(
                        DonutSegment(usedFraction, MaterialTheme.colorScheme.primary),
                        DonutSegment(
                            1f - usedFraction,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        ),
                    ),
                    diameter = 104.dp,
                    thickness = 13.dp,
                    gapDegrees = 2f,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${(usedFraction * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.home_used_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.width(18.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = volume?.label ?: stringResource(R.string.home_storage),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (volume != null) {
                            stringResource(
                                R.string.home_used_of,
                                Formatters.bytes(used),
                                Formatters.bytes(total),
                            )
                        } else {
                            stringResource(R.string.common_loading)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (volume != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(
                                    R.string.home_free,
                                    Formatters.bytes(volume.freeBytes),
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    state.insights?.let { insights ->
                        if (insights.scannedFiles > 0) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.file_count,
                                    insights.scannedFiles,
                                    insights.scannedFiles,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (!state.scanning && state.insights == null) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onScan) {
                    Text(stringResource(R.string.home_scan_now))
                }
            }
        }
    }
}

@Composable
private fun InsightsSection(
    state: HomeState,
    onOpenTool: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title = stringResource(R.string.home_insights))
        val insights = state.insights
        val scheme = MaterialTheme.colorScheme
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InsightTile(
                label = stringResource(R.string.tool_large),
                value = insights?.largeCount?.toString() ?: "—",
                detail = insights?.largeTotalBytes?.takeIf { it > 0 }?.let(Formatters::bytes),
                onClick = { onOpenTool("large") },
                icon = Icons.Filled.Memory,
                tint = scheme.tertiary,
                modifier = Modifier.weight(1f),
            )
            InsightTile(
                label = stringResource(R.string.tool_junk),
                value = insights?.let { Formatters.bytes(it.junkBytes) } ?: "—",
                detail = insights?.junkCount?.takeIf { it > 0 }?.let { count ->
                    pluralStringResource(R.plurals.item_count, count, count)
                },
                onClick = { onOpenTool("junk") },
                icon = Icons.Filled.CleaningServices,
                tint = scheme.error,
                modifier = Modifier.weight(1f),
            )
            InsightTile(
                label = stringResource(R.string.tool_trash),
                value = Formatters.bytes(state.trashBytes),
                detail = state.trashCount.takeIf { it > 0 }?.let { count ->
                    pluralStringResource(R.plurals.item_count, count, count)
                },
                onClick = { onOpenTool("trash") },
                icon = Icons.Filled.DeleteOutline,
                tint = scheme.secondary,
                modifier = Modifier.weight(1f),
            )
        }
        // Judged by how many things were found, not by how many bytes they take
        // up. Empty files and empty folders are, by definition, zero bytes — so
        // a byte-based test declared storage clean while the tile right above it
        // was reporting eleven items.
        if (insights != null &&
            insights.junkCount == 0 &&
            insights.largeCount == 0 &&
            state.trashCount == 0
        ) {
            Text(
                text = stringResource(R.string.home_insight_clean),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InsightTile(
    label: String,
    value: String,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    tint: Color,
) {
    // The three summary tiles are one row of a summary: same width already,
    // and now the same height too. They used to grow to their content, so a
    // tile with no detail line sat shorter than its neighbours and the row
    // ended in a stair step. The minimum is shared and the detail line always
    // reserves its slot, so the heights match without clipping on large fonts.
    SectionCard(
        modifier = modifier.heightIn(min = SUMMARY_TILE_MIN_HEIGHT),
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A tinted icon badge gives each metric an identity, so the three
            // tiles read as "large / junk / trash" before the labels are even
            // parsed. It is the same visual language as the quick actions.
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    // A non-breaking space keeps the line's height when there
                    // is no detail, so the three tiles stay the same size.
                    text = detail ?: "\u00A0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Shared minimum height of the three summary tiles, so the row reads level. */
private val SUMMARY_TILE_MIN_HEIGHT = 132.dp

/**
 * The well-known folders, as one row of chips.
 *
 * These used to be a card of nine full-width rows, which is most of a screen
 * spent on links that the root of the Files tab already shows. A chip keeps the
 * one-tap destination and gives the space back to the recent files, which is
 * what the page is for. Long-press still pins or unpins a place.
 */
@Composable
private fun PlacesRow(
    places: List<PlaceEntry>,
    isShortcut: (String) -> Boolean,
    onOpen: (String) -> Unit,
    onLongPress: (PlaceEntry) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(places, key = { it.path }) { place ->
            PlaceChip(
                place = place,
                isShortcut = isShortcut(place.path),
                onClick = { onOpen(place.path) },
                onLongClick = { onLongPress(place) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceChip(
    place: PlaceEntry,
    isShortcut: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        modifier = Modifier
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A pinned place keeps the star it had in the old list, so the row
            // still answers "which of these did I add myself".
            Icon(
                imageVector = if (isShortcut) Icons.Filled.Star else Icons.Filled.Folder,
                contentDescription = null,
                tint = if (isShortcut) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = place.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Recent photos and videos as real thumbnails, in a row you can flick.
 *
 * A 42dp icon cannot answer "which photo is this?" — the whole reason a recent
 * list exists is recognition without opening anything. So the media gets big
 * tiles with the actual pixels, and a name underneath only as confirmation.
 *
 * Cost is bounded on purpose: the strip holds the first handful of items, each
 * decode is capped at [TILE_THUMBNAIL_PIXELS], and [LazyRow] only builds the
 * tiles on screen. Scrolling the home page therefore never touches more than a
 * couple of decodes at a time.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentMediaStrip(
    items: List<FileItem>,
    onPreview: (FileItem) -> Unit,
    onLongPress: (FileItem) -> Unit,
) {
    LazyRow(
        // The parent list already insets by 16dp, so no extra content padding
        // here — the first tile lines up with the section header above it and
        // the last one overflows the edge, which is what says "more this way".
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(items.take(8), key = { it.path }) { item ->
            MediaTile(item = item, onPreview = onPreview, onLongPress = onLongPress)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    item: FileItem,
    onPreview: (FileItem) -> Unit,
    onLongPress: (FileItem) -> Unit,
) {
    val isVideo = item.category == FileCategory.VIDEOS
    Column(
        modifier = Modifier.width(132.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.82f)
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(
                    onClick = { onPreview(item) },
                    onLongClick = { onLongPress(item) },
                ),
        ) {
            FileThumb(
                item = item,
                enabled = true,
                fill = true,
                pixels = TILE_THUMBNAIL_PIXELS,
                corner = 18,
            )

            if (isVideo) {
                // A play badge is what separates "photo of a screen" from
                // "video" at a glance, without needing the filename.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.42f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Time stamp in the corner: the one detail that makes "recent"
            // verifiable without reading the subtitle line.
            Text(
                text = Formatters.relative(item.lastModified),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Text(
            text = item.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            text = Formatters.bytes(item.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}