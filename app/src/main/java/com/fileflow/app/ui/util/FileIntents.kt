package com.fileflow.app.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.fileflow.app.R
import com.fileflow.app.core.model.FileItem
import java.io.File

/**
 * Opening, sharing and "open with" are needed from the browser, search, the
 * home feed, the calendar and every scanner. Keeping the intent plumbing in one
 * place means the behaviour cannot drift between screens — and that a missing
 * handler always produces a message instead of a silent no-op.
 */
object FileIntents {

    fun uriFor(context: Context, path: String): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
    }.getOrNull()

    /** @return true when something was able to handle the file. */
    fun open(context: Context, item: FileItem): Boolean {
        val uri = uriFor(context, item.path)
        if (uri == null) {
            notify(context, context.getString(R.string.message_file_missing))
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(context, intent, context.getString(R.string.message_no_app))
    }

    fun openWith(context: Context, item: FileItem) {
        val uri = uriFor(context, item.path)
        if (uri == null) {
            notify(context, context.getString(R.string.message_file_missing))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, item.name)
        runCatching { context.startActivity(chooser) }
            .onFailure { notify(context, context.getString(R.string.message_no_app)) }
    }

    fun share(context: Context, items: List<FileItem>) {
        val uris = items.mapNotNull { uriFor(context, it.path) }
        if (uris.isEmpty()) {
            notify(context, context.getString(R.string.message_nothing_to_share))
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = items.first().mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, null)) }
            .onFailure { notify(context, context.getString(R.string.message_nothing_to_share)) }
    }

    /** Returns false so callers can fall back to a message of their own. */
    private fun launch(context: Context, intent: Intent, failureMessage: String): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(Intent.createChooser(intent, null)) }
                .map { true }
                .getOrElse {
                    notify(context, failureMessage)
                    false
                }
        } catch (_: SecurityException) {
            notify(context, failureMessage)
            false
        } catch (_: IllegalArgumentException) {
            notify(context, failureMessage)
            false
        }

    private fun notify(context: Context, message: String): Boolean {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        return false
    }
}
