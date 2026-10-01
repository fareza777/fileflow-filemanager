package com.fileflow.app.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.fileflow.app.domain.apps.InstalledApp

/**
 * What can be done with an installed app, from the app's own list.
 *
 * These hand back intents rather than starting them, because uninstalling is a
 * conversation: the system shows its own dialog, the user answers, and only
 * then is the package gone. The screen launches that one through an activity
 * result so it can re-read the list when the answer comes back — guessing at
 * the outcome instead is how a list ends up showing an app that is not there.
 *
 * None of this can be done silently, and that is the platform working as
 * intended: an app cannot remove another app, it can only ask.
 */
object AppIntents {

    /** Launches the app, if it has anything launchable. */
    fun openIntent(context: Context, app: InstalledApp): Intent? = runCatching {
        context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()

    /**
     * The installer's own confirmation for this package.
     *
     * Null for a system app: the platform refuses to remove one, so the button
     * that offers it should not exist.
     */
    fun uninstallIntent(app: InstalledApp): Intent? {
        if (app.isSystem) return null
        return Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The system's app screen: permissions, storage, force stop. */
    fun detailsIntent(app: InstalledApp): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", app.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun open(context: Context, app: InstalledApp): Boolean =
        openIntent(context, app)?.let { intent ->
            runCatching { context.startActivity(intent) }.isSuccess
        } ?: false

    fun details(context: Context, app: InstalledApp): Boolean =
        runCatching { context.startActivity(detailsIntent(app)) }.isSuccess
}
