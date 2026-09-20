package com.fileflow.app.ui.util

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.fileflow.app.R

/**
 * What a screen says after doing something.
 *
 * Destructive and reversible operations carry the journal group they wrote to,
 * which is what lets the snackbar offer a real one-tap undo instead of just
 * announcing that files are gone.
 */
sealed interface UiMessage {
    data class Info(val text: String) : UiMessage
    data class Done(val text: String, val undoGroup: String? = null) : UiMessage
}

/**
 * Shows the message and invokes [onUndo] only if the user actually taps Undo.
 */
suspend fun SnackbarHostState.showMessage(
    context: Context,
    message: UiMessage,
    onUndo: (groupId: String) -> Unit,
) {
    when (message) {
        is UiMessage.Info -> showSnackbar(message.text)
        is UiMessage.Done -> {
            val result = showSnackbar(
                message = message.text,
                actionLabel = message.undoGroup?.let { context.getString(R.string.action_undo) },
                withDismissAction = message.undoGroup == null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && message.undoGroup != null) {
                onUndo(message.undoGroup)
            }
        }
    }
}
