package com.fileflow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fileflow.app.R
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.domain.privacy.MetadataStripper
import java.io.File

/**
 * What a long-press can do to a selection, wherever the selection was made.
 *
 * Every list in this app ends up with files selected — the browser, recent,
 * starred, the calendar, a search result — and the verbs were drifting apart:
 * one list could compress, another could not; one offered "select all", the
 * next offered "clear". That is how an app starts feeling like several apps
 * stapled together, and it is also how a verb gets added to one list and
 * forgotten in the other four.
 *
 * So the bar and the sheet are defined once, here, and a screen supplies what
 * each verb means for it. Adding a verb means adding it in one place and every
 * list has it.
 */
data class BulkHandlers(
    val onShare: () -> Unit,
    val onMove: () -> Unit,
    val onCopy: () -> Unit,
    val onConvert: () -> Unit,
    /**
     * Renaming a whole batch needs a pattern and a preview, so it opens a
     * screen of its own. Null hides the verb, for the callers that have not
     * wired that screen up.
     */
    val onBatchRename: (() -> Unit)? = null,
    val onCompress: () -> Unit,
    val onCleanMetadata: () -> Unit,
    val onStar: () -> Unit,
    val onDelete: () -> Unit,
    /** Opens [BulkMoreSheet]; supplied by the screen because it owns that state. */
    val onMore: () -> Unit,
    /** Shown as "remove star" when every selected file already has one. */
    val allStarred: Boolean = false,
    /** Blank hides the PC folder verb, which needs a destination to name. */
    val transferFolder: String = "",
    val onMoveToTransfer: (() -> Unit)? = null,
    val onSelectAll: (() -> Unit)? = null,
    val onDeselectAll: (() -> Unit)? = null,
)

/**
 * The five verbs that sit on the bar.
 *
 * Five, and no more: the rest wait behind [BulkMoreSheet]. A row of eight
 * icons is a row of eight small targets, and a thumbnail is not a stylus.
 */
@Composable
fun bulkBarActions(handlers: BulkHandlers): List<SelectionAction> = listOfNotNull(
    SelectionAction(
        icon = Icons.Filled.Share,
        label = stringResource(R.string.action_share),
        onClick = handlers.onShare,
    ),
    SelectionAction(
        icon = Icons.AutoMirrored.Filled.DriveFileMove,
        label = stringResource(R.string.action_move),
        onClick = handlers.onMove,
    ),
    SelectionAction(
        icon = Icons.Filled.SwapHoriz,
        label = stringResource(R.string.action_convert),
        onClick = handlers.onConvert,
    ),
    SelectionAction(
        icon = Icons.Filled.MoreVert,
        label = stringResource(R.string.action_more),
        onClick = handlers.onMore,
    ),
    SelectionAction(
        icon = Icons.Filled.Delete,
        label = stringResource(R.string.action_delete),
        onClick = handlers.onDelete,
        destructive = true,
    ),
)

/**
 * The verbs that need a destination or a second question, plus the ones that
 * apply to the whole batch at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkMoreSheet(
    items: List<FileItem>,
    bytes: Long,
    handlers: BulkHandlers,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val strippable = items.count { item ->
        !item.isDirectory && MetadataStripper.canStrip(File(item.path))
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
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = pluralStringResource(R.plurals.item_count, items.size, items.size) +
                    " · " + Formatters.bytes(bytes),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )

            BulkRow(Icons.Filled.ContentCopy, R.string.action_copy, handlers.onCopy)
            if (handlers.transferFolder.isNotBlank() && handlers.onMoveToTransfer != null) {
                BulkRow(Icons.Filled.Devices, R.string.action_to_pc, handlers.onMoveToTransfer)
            }
            BulkRow(Icons.Filled.FolderZip, R.string.action_compress, handlers.onCompress)
            // With the other whole-batch verbs: renaming a selection is one
            // pattern for all of it, not a per-file question.
            handlers.onBatchRename?.let { rename ->
                BulkRow(Icons.Filled.DriveFileRenameOutline, R.string.action_batch_rename, rename)
            }
            if (strippable > 0) {
                BulkRow(Icons.Filled.PrivacyTip, R.string.action_clean_metadata, handlers.onCleanMetadata)
            }
            BulkRow(
                icon = if (handlers.allStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                label = if (handlers.allStarred) R.string.action_unstar else R.string.action_star,
                onClick = handlers.onStar,
            )

            if (handlers.onSelectAll != null || handlers.onDeselectAll != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                handlers.onSelectAll?.let { BulkRow(Icons.Filled.SelectAll, R.string.action_select_all, it) }
                handlers.onDeselectAll?.let { BulkRow(Icons.Filled.Deselect, R.string.action_deselect_all, it) }
            }
        }
    }
}

@Composable
private fun BulkRow(
    icon: ImageVector,
    label: Int,
    onClick: () -> Unit,
) {
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
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(text = stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}
