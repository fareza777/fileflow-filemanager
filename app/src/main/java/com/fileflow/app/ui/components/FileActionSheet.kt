package com.fileflow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileflow.app.R
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.util.Formatters

/**
 * One action menu for every list in the app. Long-pressing a file anywhere
 * should offer the same verbs in the same order, otherwise the app feels like
 * several apps stapled together.
 *
 * The verbs are grouped — open, then organise, then destroy — with a hairline
 * between the groups. A single undifferentiated column of eleven identical rows
 * is what made this read as a generic list rather than a considered menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionSheet(
    item: FileItem,
    onDismiss: () -> Unit,
    onOpen: (() -> Unit)? = null,
    onOpenWith: (() -> Unit)? = null,
    onShowInFolder: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onDetails: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onStar: (() -> Unit)? = null,
    isStarred: Boolean = false,
    onExtract: (() -> Unit)? = null,
    /** One tap into the folder the transfer server shares with the computer. */
    onMoveToTransfer: (() -> Unit)? = null,
    /** Strips EXIF, IPTC and comment segments out of an image, losslessly. */
    onCleanMetadata: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Which group each action lands in; separators are drawn between groups
    // that actually have something in them.
    val open = buildList {
        if (!item.isDirectory) {
            onOpen?.let { add(Triple(Icons.Filled.OpenInNew, R.string.action_open, it)) }
            onOpenWith?.let { add(Triple(Icons.Filled.FolderOpen, R.string.action_open_with, it)) }
        }
    }
    val organise = buildList {
        onShowInFolder?.let { add(Triple(Icons.Filled.FolderOpen, R.string.action_show_in_folder, it)) }
        onShare?.let { add(Triple(Icons.Filled.Share, R.string.action_share, it)) }
        onCopy?.let { add(Triple(Icons.Filled.ContentCopy, R.string.action_copy, it)) }
        onMove?.let { add(Triple(Icons.AutoMirrored.Filled.DriveFileMove, R.string.action_move, it)) }
        onMoveToTransfer?.let {
            add(Triple(Icons.Filled.Devices, R.string.action_move_to_transfer, it))
        }
        onRename?.let { add(Triple(Icons.Filled.Edit, R.string.action_rename, it)) }
        onExtract?.let { add(Triple(Icons.Filled.FolderZip, R.string.action_extract, it)) }
        onStar?.let {
            add(
                Triple(
                    if (isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    if (isStarred) R.string.action_unstar else R.string.action_star,
                    it,
                )
            )
        }
        onSelect?.let { add(Triple(Icons.Filled.SelectAll, R.string.action_select, it)) }
        onCleanMetadata?.let {
            add(Triple(Icons.Filled.PrivacyTip, R.string.action_clean_metadata, it))
        }
        onDetails?.let { add(Triple(Icons.Filled.Info, R.string.action_info, it)) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileThumb(item = item, enabled = true, modifier = Modifier.size(46.dp))
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (item.isDirectory) {
                            stringResource(R.string.common_empty_folder)
                        } else {
                            "${Formatters.bytes(item.sizeBytes)} · ${
                                Formatters.dateTime(item.lastModified)
                            }"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                open.forEach { (icon, label, action) ->
                    SheetAction(icon = icon, label = stringResource(label), onClick = action)
                }
            }

            if (open.isNotEmpty() && organise.isNotEmpty()) SheetDivider()

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                organise.forEach { (icon, label, action) ->
                    SheetAction(icon = icon, label = stringResource(label), onClick = action)
                }
            }

            onDelete?.let { delete ->
                if (open.isNotEmpty() || organise.isNotEmpty()) SheetDivider()
                SheetAction(
                    icon = Icons.Filled.Delete,
                    label = stringResource(R.string.action_delete),
                    onClick = delete,
                    destructive = true,
                )
            }
        }
    }
}

@Composable
private fun SheetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val tint = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (destructive) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) tint else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}
