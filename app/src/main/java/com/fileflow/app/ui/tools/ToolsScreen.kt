package com.fileflow.app.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fileflow.app.R
import com.fileflow.app.ui.components.AdBanner
import com.fileflow.app.ui.components.SectionCard
import com.fileflow.app.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onOpenTool: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.tools_title)) })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Three groups, and every tool appears in exactly one of them: the
            // cards used to mix "look at this" with "delete this" and listed a
            // journal and a run log side by side as if they were the same thing.
            item { SectionHeader(stringResource(R.string.tools_insight)) }
            item {
                SectionCard {
                    Column {
                        ToolRow(
                            icon = Icons.Filled.CalendarMonth,
                            title = stringResource(R.string.tool_calendar),
                            subtitle = stringResource(R.string.tool_calendar_desc),
                            onClick = { onOpenTool("calendar") },
                            accent = scheme.tertiary,
                        )
                        ToolRow(
                            icon = Icons.Filled.PieChart,
                            title = stringResource(R.string.tool_analyzer),
                            subtitle = stringResource(R.string.tool_analyzer_desc),
                            onClick = { onOpenTool("analyzer") },
                            accent = scheme.primary,
                        )
                        ToolRow(
                            icon = Icons.Filled.Memory,
                            title = stringResource(R.string.tool_large),
                            subtitle = stringResource(R.string.tool_large_desc),
                            onClick = { onOpenTool("large") },
                            accent = scheme.secondary,
                        )
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.tools_cleanup)) }
            item {
                SectionCard {
                    Column {
                        ToolRow(
                            icon = Icons.Filled.ContentCopy,
                            title = stringResource(R.string.tool_duplicates),
                            subtitle = stringResource(R.string.tool_duplicates_desc),
                            onClick = { onOpenTool("duplicates") },
                            accent = scheme.tertiary,
                        )
                        ToolRow(
                            icon = Icons.Filled.CleaningServices,
                            title = stringResource(R.string.tool_junk),
                            subtitle = stringResource(R.string.tool_junk_desc),
                            onClick = { onOpenTool("junk") },
                            accent = scheme.error,
                        )
                        ToolRow(
                            icon = Icons.Filled.AutoAwesome,
                            title = stringResource(R.string.tool_rename),
                            subtitle = stringResource(R.string.tool_rename_desc),
                            onClick = { onOpenTool("rename") },
                            accent = scheme.primary,
                        )
                        ToolRow(
                            icon = Icons.Filled.Inventory2,
                            title = stringResource(R.string.tool_compress),
                            subtitle = stringResource(R.string.tool_compress_desc),
                            onClick = { onOpenTool("compress") },
                            accent = scheme.secondary,
                        )
                        ToolRow(
                            icon = Icons.Filled.SwapHoriz,
                            title = stringResource(R.string.tool_convert),
                            subtitle = stringResource(R.string.tool_convert_desc),
                            onClick = { onOpenTool("convert") },
                            accent = scheme.tertiary,
                        )
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.tools_keep)) }
            item {
                SectionCard {
                    Column {
                        ToolRow(
                            icon = Icons.Filled.Star,
                            title = stringResource(R.string.tool_starred),
                            subtitle = stringResource(R.string.tool_starred_desc),
                            onClick = { onOpenTool("starred") },
                            accent = scheme.tertiary,
                        )
                        ToolRow(
                            icon = Icons.Filled.Devices,
                            title = stringResource(R.string.tool_transfer),
                            subtitle = stringResource(R.string.tool_transfer_desc),
                            onClick = { onOpenTool("transfer") },
                            accent = scheme.primary,
                        )
                        ToolRow(
                            icon = Icons.Filled.DeleteSweep,
                            title = stringResource(R.string.tool_trash),
                            subtitle = stringResource(R.string.tool_trash_desc),
                            onClick = { onOpenTool("trash") },
                            accent = scheme.secondary,
                        )
                        ToolRow(
                            icon = Icons.Filled.History,
                            title = stringResource(R.string.tool_changes),
                            subtitle = stringResource(R.string.tool_changes_desc),
                            onClick = { onOpenTool("changes") },
                            accent = scheme.primary,
                        )
                        ToolRow(
                            icon = Icons.Filled.History,
                            title = stringResource(R.string.tool_history),
                            subtitle = stringResource(R.string.tool_history_desc),
                            onClick = { onOpenTool("history") },
                            accent = scheme.tertiary,
                        )
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.tools_power)) }
            item {
                SectionCard {
                    Column {
                        ToolRow(
                            icon = Icons.Filled.Splitscreen,
                            title = stringResource(R.string.tool_dual),
                            subtitle = stringResource(R.string.tool_dual_desc),
                            onClick = { onOpenTool("dual") },
                            accent = scheme.primary,
                        )
                        ToolRow(
                            icon = Icons.Filled.Compare,
                            title = stringResource(R.string.tool_compare),
                            subtitle = stringResource(R.string.tool_compare_desc),
                            onClick = { onOpenTool("compare") },
                            accent = scheme.tertiary,
                        )
                        ToolRow(
                            icon = Icons.Filled.Sync,
                            title = stringResource(R.string.tool_sync),
                            subtitle = stringResource(R.string.tool_sync_desc),
                            onClick = { onOpenTool("sync") },
                            accent = scheme.tertiary,
                        )
                        ToolRow(
                            icon = Icons.Filled.CloudQueue,
                            title = stringResource(R.string.tool_remote),
                            subtitle = stringResource(R.string.tool_remote_desc),
                            onClick = { onOpenTool("remote") },
                            accent = scheme.secondary,
                        )
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.tools_analysis)) }
            item {
                SectionCard {
                    Column {
                        ToolRow(
                            icon = Icons.Filled.Troubleshoot,
                            title = stringResource(R.string.tool_inspect),
                            subtitle = stringResource(R.string.tool_inspect_desc),
                            onClick = { onOpenTool("inspect") },
                            accent = scheme.primary,
                        )
                        ToolRow(
                            icon = Icons.Filled.PhotoLibrary,
                            title = stringResource(R.string.tool_photos),
                            subtitle = stringResource(R.string.tool_photos_desc),
                            onClick = { onOpenTool("photos") },
                            accent = scheme.tertiary,
                        )
                        ToolRow(
                            icon = Icons.Filled.Shield,
                            title = stringResource(R.string.tool_shield),
                            subtitle = stringResource(R.string.tool_shield_desc),
                            onClick = { onOpenTool("shield") },
                            accent = scheme.secondary,
                        )
                        ToolRow(
                            icon = Icons.Filled.Android,
                            title = stringResource(R.string.tool_apps),
                            subtitle = stringResource(R.string.tool_apps_desc),
                            onClick = { onOpenTool("apps") },
                            accent = scheme.primary,
                        )
                        ToolRow(
                            icon = Icons.Filled.History,
                            title = stringResource(R.string.tool_versions),
                            subtitle = stringResource(R.string.tool_versions_desc),
                            onClick = { onOpenTool("versions") },
                            accent = scheme.tertiary,
                        )
                    }
                }
            }

            item { Spacer(Modifier.size(8.dp)) }
            item { AdBanner() }
        }
    }
}

@Composable
private fun ToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One accent per tool, drawn from the same family the badges on the home
        // summary use, so a tool is recognisable by colour before its label is
        // read — a column of ten identical primary tiles says nothing.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
