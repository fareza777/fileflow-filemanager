package com.fileflow.app.ui

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fileflow.app.R
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.data.repo.ActiveOperation
import com.fileflow.app.ui.about.AboutScreen
import com.fileflow.app.ui.analyzer.AnalyzerScreen
import com.fileflow.app.ui.apps.AppBackupScreen
import com.fileflow.app.ui.browser.BrowserScreen
import com.fileflow.app.ui.calendar.CalendarScreen
import com.fileflow.app.ui.changes.RecentChangesScreen
import com.fileflow.app.ui.compress.CompressScreen
import com.fileflow.app.ui.components.ProgressPanel
import com.fileflow.app.ui.compare.CompareScreen
import com.fileflow.app.ui.convert.ConvertScreen
import com.fileflow.app.ui.dualpane.DualPaneScreen
import com.fileflow.app.ui.duplicates.DuplicatesScreen
import com.fileflow.app.ui.home.HomeScreen
import com.fileflow.app.ui.inspect.InspectScreen
import com.fileflow.app.ui.junk.JunkScreen
import com.fileflow.app.ui.large.LargeFilesScreen
import com.fileflow.app.ui.photos.SimilarPhotosScreen
import com.fileflow.app.ui.power.PowerAccessScreen
import com.fileflow.app.ui.recent.RecentFilesScreen
import com.fileflow.app.ui.remote.RemoteBrowserScreen
import com.fileflow.app.ui.remote.RemoteScreen
import com.fileflow.app.ui.rename.SmartRenameScreen
import com.fileflow.app.ui.rules.HistoryScreen
import com.fileflow.app.ui.rules.RuleEditorScreen
import com.fileflow.app.ui.rules.RulePreviewScreen
import com.fileflow.app.ui.rules.RulesScreen
import com.fileflow.app.ui.search.SearchScreen
import com.fileflow.app.ui.settings.SettingsScreen
import com.fileflow.app.ui.shield.ShieldScreen
import com.fileflow.app.ui.starred.StarredScreen
import com.fileflow.app.ui.sync.SyncScreen
import com.fileflow.app.ui.tools.ToolsScreen
import com.fileflow.app.ui.transfer.TransferScreen
import com.fileflow.app.ui.trash.TrashScreen
import com.fileflow.app.ui.viewer.ImageViewerScreen
import com.fileflow.app.ui.viewer.TextFileScreen
import com.fileflow.app.ui.util.FileIntents
import com.fileflow.app.ui.util.FileOpen
import com.fileflow.app.ui.util.PreviewKind
import com.fileflow.app.ui.versions.VersionsScreen

private enum class Tab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val outlineIcon: ImageVector,
) {
    HOME("home", R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    FILES("files", R.string.nav_files, Icons.Filled.Folder, Icons.Outlined.Folder),
    TOOLS("tools", R.string.nav_tools, Icons.Filled.Build, Icons.Outlined.Build),
    RULES("rules", R.string.nav_rules, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

private object Routes {
    const val SEARCH = "search"
    const val RECENT = "recent"
    const val CALENDAR = "calendar"
    const val ANALYZER = "analyzer"
    const val DUPLICATES = "duplicates"
    const val LARGE = "large"
    const val JUNK = "junk"
    const val RENAME = "rename"
    const val TRASH = "trash"
    const val HISTORY = "history"
    const val ABOUT = "about"
    const val RULE_EDITOR = "rule_editor"
    const val RULE_PREVIEW = "rule_preview"
    const val IMAGE_VIEWER = "image_viewer"
    const val TEXT_VIEWER = "text_viewer"
    const val STARRED = "starred"
    const val CHANGES = "changes"
    const val TRANSFER = "transfer"
    const val COMPRESS = "compress"
    const val CONVERT = "convert"
    const val DUAL = "dual"
    const val COMPARE = "compare"
    const val REMOTE = "remote"
    const val REMOTE_BROWSE = "remote_browse"
    const val INSPECT = "inspect"
    const val PHOTOS = "photos"
    const val SHIELD = "shield"
    const val APPS = "apps"
    const val VERSIONS = "versions"
    const val SYNC = "sync"
    const val POWER = "power"
}

@Composable
fun AppRoot(openTransferOnStart: Boolean = false) {
    val navController = rememberNavController()
    val rootViewModel: AppRootViewModel = hiltViewModel()
    val handoff = rootViewModel.handoff
    val hasAccess = rememberStorageAccess()
    // `remember`, not `rememberSaveable`: skipping the gate is a decision for
    // this session. Persisting it meant that after one "continue without
    // permission" the gate never came back — not even across a full restart —
    // and the user was left on an empty home screen with no obvious way back.
    var skippedAccess by remember { mutableStateOf(false) }
    var pendingFolder by remember { mutableStateOf<String?>(null) }
    var searchRoot by remember { mutableStateOf<String?>(null) }
    var openedTransfer by remember { mutableStateOf(false) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Arriving from the transfer notification: land on the screen that owns the
    // running server rather than on Home.
    LaunchedEffect(openTransferOnStart, hasAccess) {
        if (openTransferOnStart && hasAccess && !openedTransfer) {
            openedTransfer = true
            navController.navigate(Routes.TRANSFER)
        }
    }

    if (!hasAccess && !skippedAccess) {
        PermissionGate(onSkip = { skippedAccess = true })
        return
    }

    fun openTab(tab: Tab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val openFolder: (String) -> Unit = { path ->
        pendingFolder = path
        openTab(Tab.FILES)
    }

    // Photos and text open in the app's own viewers from anywhere; everything
    // else goes out to whatever the system has. Home, calendar and the scanners
    // all route through here so a tap means the same thing everywhere.
    val context = LocalContext.current
    val previewFile: (FileItem) -> Unit = { item ->
        when (FileOpen.kindOf(item)) {
            PreviewKind.IMAGE -> navController.navigate(
                "${Routes.IMAGE_VIEWER}?folder=${Uri.encode(item.path.substringBeforeLast('/'))}" +
                    "&path=${Uri.encode(item.path)}"
            )
            PreviewKind.TEXT -> navController.navigate(
                "${Routes.TEXT_VIEWER}?path=${Uri.encode(item.path)}"
            )
            PreviewKind.EXTERNAL -> FileIntents.open(context, item)
        }
    }

    val openTool: (String) -> Unit = { tool ->
        when (tool) {
            "calendar" -> navController.navigate(Routes.CALENDAR)
            "analyzer" -> navController.navigate(Routes.ANALYZER)
            "duplicates" -> navController.navigate(Routes.DUPLICATES)
            "large" -> navController.navigate(Routes.LARGE)
            "junk" -> navController.navigate(Routes.JUNK)
            "rename" -> navController.navigate(Routes.RENAME)
            "trash" -> navController.navigate(Routes.TRASH)
            "history" -> navController.navigate(Routes.HISTORY)
            "starred" -> navController.navigate(Routes.STARRED)
            "changes" -> navController.navigate(Routes.CHANGES)
            "transfer" -> navController.navigate(Routes.TRANSFER)
            "compress" -> navController.navigate(Routes.COMPRESS)
            "convert" -> navController.navigate(Routes.CONVERT)
            "dual" -> navController.navigate(Routes.DUAL)
            "compare" -> navController.navigate(Routes.COMPARE)
            "remote" -> navController.navigate(Routes.REMOTE)
            "inspect" -> navController.navigate(Routes.INSPECT)
            "photos" -> navController.navigate(Routes.PHOTOS)
            "shield" -> navController.navigate(Routes.SHIELD)
            "apps" -> navController.navigate(Routes.APPS)
            "versions" -> navController.navigate(Routes.VERSIONS)
            "sync" -> navController.navigate(Routes.SYNC)
            "power" -> navController.navigate(Routes.POWER)
            else -> Unit
        }
    }

    Scaffold(
        // The default content insets are the system bars only. On Android 15
        // edge-to-edge is enforced and `adjustResize` no longer resizes the
        // window, so without the IME in this union the keyboard simply covers
        // whichever field has focus — every search, rename and rule form in the
        // app. One place fixes all of them: the content box below already pads
        // by the bottom inset, which now grows by exactly the keyboard height.
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        bottomBar = {
            val showBar = currentRoute in Tab.entries.map { it.route }
            if (showBar) {
                FloatingNavBar(
                    tabs = Tab.entries,
                    current = currentRoute,
                    onSelect = ::openTab,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 10.dp)
                        .navigationBarsPadding(),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            NavHost(                navController = navController,
                startDestination = Tab.HOME.route,
                // A short, soft cross-fade rather than a slide: tab switches and
                // deep links both land here, and a directional push looks wrong
                // the moment you arrive from a different part of the app.
                enterTransition = {
                    fadeIn(tween(220)) + scaleIn(
                        initialScale = 0.98f,
                        animationSpec = tween(220),
                    )
                },
                exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { fadeOut(tween(140)) },
            ) {

                composable(Tab.HOME.route) {
                    HomeScreen(
                        onOpenFolder = openFolder,
                        onOpenTool = openTool,
                        onPreview = previewFile,
                        onOpenSearch = {
                            searchRoot = null
                            navController.navigate(Routes.SEARCH)
                        },
                        onOpenRecent = { navController.navigate(Routes.RECENT) },
                    )
                }

                composable(Routes.RECENT) {
                    RecentFilesScreen(
                        onBack = { navController.popBackStack() },
                        onPreview = previewFile,
                        onOpenFolder = openFolder,
                        onConvert = { paths ->
                            handoff.put(paths)
                            navController.navigate(Routes.CONVERT)
                        },
                        onCompress = { paths ->
                            handoff.put(paths)
                            navController.navigate(Routes.COMPRESS)
                        },
                    )
                }

                composable(Tab.FILES.route) {
                    BrowserScreen(
                        requestedPath = pendingFolder,
                        onRequestConsumed = { pendingFolder = null },
                        onOpenSearch = { path ->
                            searchRoot = path
                            navController.navigate(Routes.SEARCH)
                        },
                        onViewImage = { folder, path ->
                            navController.navigate(
                                "${Routes.IMAGE_VIEWER}?folder=${Uri.encode(folder)}" +
                                    "&path=${Uri.encode(path)}"
                            )
                        },
                        onViewText = { path ->
                            navController.navigate("${Routes.TEXT_VIEWER}?path=${Uri.encode(path)}")
                        },
                        onConvert = { paths ->
                            handoff.put(paths)
                            navController.navigate(Routes.CONVERT)
                        },
                    )
                }

                composable(Tab.TOOLS.route) {
                    ToolsScreen(onOpenTool = openTool)
                }

                composable(Tab.RULES.route) {
                    RulesScreen(
                        onBack = { openTab(Tab.HOME) },
                        onCreateRule = { navController.navigate(Routes.RULE_EDITOR) },
                        onEditRule = { id ->
                            navController.navigate("${Routes.RULE_EDITOR}?ruleId=$id")
                        },
                        onPreviewRule = { id ->
                            navController.navigate("${Routes.RULE_PREVIEW}?ruleId=$id")
                        },
                        onHistory = { id ->
                            navController.navigate("${Routes.HISTORY}?ruleId=$id")
                        },
                    )
                }

                composable(Tab.SETTINGS.route) {
                    SettingsScreen(
                        onOpenAbout = { navController.navigate(Routes.ABOUT) },
                        onOpenTrash = { navController.navigate(Routes.TRASH) },
                    )
                }

                composable(Routes.SEARCH) {
                    SearchScreen(
                        initialPath = searchRoot,
                        onCreateRule = { query ->
                            searchRoot = null
                            navController.navigate("${Routes.RULE_EDITOR}?q=${android.net.Uri.encode(query)}")
                        },
                        onOpenFolder = openFolder,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.CALENDAR) {
                    CalendarScreen(
                        onBack = { navController.popBackStack() },
                        onOpenFolder = openFolder,
                        onConvert = { paths ->
                            handoff.put(paths)
                            navController.navigate(Routes.CONVERT)
                        },
                        onCompress = { paths ->
                            handoff.put(paths)
                            navController.navigate(Routes.COMPRESS)
                        },
                    )
                }

                composable(
                    route = "${Routes.IMAGE_VIEWER}?folder={folder}&path={path}",
                    arguments = listOf(
                        navArgument("folder") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("path") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) {
                    ImageViewerScreen(onBack = { navController.popBackStack() })
                }

                composable(
                    route = "${Routes.TEXT_VIEWER}?path={path}",
                    arguments = listOf(
                        navArgument("path") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) {
                    TextFileScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.ANALYZER) {
                    AnalyzerScreen(
                        onBack = { navController.popBackStack() },
                        onOpenFolder = openFolder,
                    )
                }

                composable(Routes.DUPLICATES) {
                    DuplicatesScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.LARGE) {
                    LargeFilesScreen(
                        onBack = { navController.popBackStack() },
                        onConvert = { paths ->
                            handoff.put(paths)
                            navController.navigate(Routes.CONVERT)
                        },
                        onCompress = { paths ->
                            handoff.put(paths)
                            navController.navigate(Routes.COMPRESS)
                        },
                    )
                }

                composable(Routes.JUNK) {
                    JunkScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.RENAME) {
                    SmartRenameScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.TRASH) {
                    TrashScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.CHANGES) {
                    RecentChangesScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.STARRED) {
                    StarredScreen(
                        onBack = { navController.popBackStack() },
                        onOpenFolder = openFolder,
                        onPreview = previewFile,
                        onConvert = { paths ->
                            handoff.put(paths)
                            navController.navigate(Routes.CONVERT)
                        },
                        onCompress = { paths ->
                            handoff.put(paths)
                            navController.navigate(Routes.COMPRESS)
                        },
                    )
                }

                composable(Routes.TRANSFER) {
                    TransferScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.COMPRESS) {
                    CompressScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.CONVERT) {
                    ConvertScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.DUAL) {
                    DualPaneScreen(
                        onBack = { navController.popBackStack() },
                        onOpenFolder = openFolder,
                    )
                }

                composable(Routes.COMPARE) {
                    CompareScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.REMOTE) {
                    RemoteScreen(
                        onBack = { navController.popBackStack() },
                        onOpenConnection = { connection ->
                            navController.navigate("${Routes.REMOTE_BROWSE}?connectionId=${connection.id}")
                        },
                    )
                }

                composable(
                    route = "${Routes.REMOTE_BROWSE}?connectionId={connectionId}",
                    arguments = listOf(
                        navArgument("connectionId") {
                            type = NavType.StringType
                            defaultValue = ""
                        }
                    ),
                ) {
                    RemoteBrowserScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.INSPECT) {
                    InspectScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.PHOTOS) {
                    SimilarPhotosScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.SHIELD) {
                    ShieldScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.APPS) {
                    AppBackupScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.VERSIONS) {
                    VersionsScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.SYNC) {
                    SyncScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.POWER) {
                    PowerAccessScreen(onBack = { navController.popBackStack() })
                }

                composable(
                    route = "${Routes.HISTORY}?ruleId={ruleId}",
                    arguments = listOf(
                        navArgument("ruleId") {
                            type = NavType.LongType
                            defaultValue = 0L
                        }
                    ),
                ) {
                    HistoryScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.ABOUT) {
                    AboutScreen(onBack = { navController.popBackStack() })
                }

                composable(
                    route = "${Routes.RULE_EDITOR}?ruleId={ruleId}&q={q}",
                    arguments = listOf(
                        navArgument("ruleId") {
                            type = NavType.LongType
                            defaultValue = 0L
                        },
                        navArgument("q") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) {
                    RuleEditorScreen(
                        onBack = { navController.popBackStack() },
                        onSaved = { id ->
                            navController.navigate("${Routes.RULE_PREVIEW}?ruleId=$id") {
                                popUpTo(Routes.RULE_EDITOR) { inclusive = true }
                            }
                        },
                    )
                }

                composable(
                    route = "${Routes.RULE_PREVIEW}?ruleId={ruleId}",
                    arguments = listOf(
                        navArgument("ruleId") {
                            type = NavType.LongType
                            defaultValue = 0L
                        }
                    ),
                ) {
                    RulePreviewScreen(
                        onBack = { navController.popBackStack() },
                        onOpenHistory = { navController.navigate(Routes.HISTORY) },
                    )
                }
            }

            // One indicator for the whole app. Whichever screen started the
            // operation, its progress and its Stop button stay reachable after
            // you navigate away — the work belongs to the process, so the
            // progress belongs to the root.
            //
            val operation by rootViewModel.activeOperation.collectAsStateWithLifecycle()
            // Hoisted into a local val rather than read three times through the
            // delegate: a delegated read cannot be smart-cast, and any lambda
            // that captured it would keep seeing a stale value.
            val active = operation
            if (active != null && currentRoute != Tab.FILES.route) {
                GlobalOperationBar(
                    operation = active,
                    onCancel = rootViewModel::cancelOperation,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                )
            }
        }
    }
}

/**
 * The floating pill, drawn rather than assembled from [NavigationBar].
 *
 * With five items on a narrow screen the stock item's indicator left under a
 * dp of clearance inside the bar's own rounded end, which is what made the
 * first tab look like it was colliding with the card behind it. The pill keeps
 * its shape; every item now sits in its own well with room around it, and the
 * selected state animates instead of snapping.
 */
@Composable
private fun FloatingNavBar(
    tabs: List<Tab>,
    current: String?,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(26.dp)
    Surface(
        modifier = modifier
            .clip(shape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = shape,
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                NavBarItem(
                    tab = tab,
                    selected = current == tab.route,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavBarItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val indicator by animateColorAsState(
        targetValue = if (selected) scheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(180),
        label = "nav-indicator",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        animationSpec = tween(180),
        label = "nav-tint",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(indicator),
            contentAlignment = Alignment.Center,
        ) {
            // Filled when active, outlined when not: the state is readable from
            // the icon alone, which is what lets the label stay small.
            Icon(
                imageVector = if (selected) tab.icon else tab.outlineIcon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
        }
        Text(
            text = stringResource(tab.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The same [ProgressPanel] the browser draws, lifted to the root so it follows
 * the user between screens. Slides in from the bottom and back out again;
 * nothing animates while no operation is running.
 */
@Composable
private fun GlobalOperationBar(
    operation: ActiveOperation,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(160)),
        modifier = modifier.fillMaxWidth(),
    ) {
        ProgressPanel(
            title = when (operation.kind) {
                OperationKind.COPY -> stringResource(R.string.op_copying)
                OperationKind.MOVE -> stringResource(R.string.op_moving)
                OperationKind.TRASH, OperationKind.DELETE -> stringResource(R.string.op_deleting)
                OperationKind.RENAME -> stringResource(R.string.op_renaming)
                OperationKind.CREATE_FOLDER -> stringResource(R.string.browser_new_folder)
                OperationKind.ARCHIVE -> stringResource(R.string.op_archiving)
                OperationKind.RESTORE -> stringResource(R.string.op_restoring)
                OperationKind.CLEAN -> stringResource(R.string.op_cleaning)
            },
            detail = operation.progress?.let { progress ->
                if (progress.total > 1) {
                    "${progress.currentName} · ${progress.current + 1}/${progress.total}"
                } else {
                    progress.currentName
                }
            },
            fraction = operation.progress?.fraction,
            onCancel = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
