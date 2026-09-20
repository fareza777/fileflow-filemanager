package com.fileflow.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileflow.app.R

/**
 * Shown before a copy or move that would collide with existing names.
 *
 * Every action applies to the whole batch, which is the only sane behaviour
 * when someone drops two hundred files into a folder: being asked two hundred
 * times is not a choice, it is a punishment.
 */
@Composable
fun ConflictDialog(
    conflictingNames: List<String>,
    totalCount: Int,
    destination: String,
    onKeepBoth: () -> Unit,
    onOverwrite: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val collisionCount = conflictingNames.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conflict_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = pluralStringResource(
                        R.plurals.conflict_body,
                        collisionCount,
                        collisionCount,
                        totalCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = destination,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    conflictingNames.take(4).forEach { name ->
                        Text(
                            text = "• $name",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (collisionCount > 4) {
                        Text(
                            text = "+${collisionCount - 4}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.conflict_apply_all),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                TextButton(onClick = onKeepBoth) {
                    Text(
                        text = stringResource(R.string.conflict_rename),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = onOverwrite) {
                    Text(
                        text = stringResource(R.string.conflict_overwrite),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.conflict_skip))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
