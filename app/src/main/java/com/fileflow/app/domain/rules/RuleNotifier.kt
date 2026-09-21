package com.fileflow.app.domain.rules

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fileflow.app.MainActivity
import com.fileflow.app.R
import com.fileflow.app.core.util.Formatters
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A scheduled run happens while the user is doing something else, so the only
 * trace it leaves is one summary notification — never one per file.
 */
@Singleton
class RuleNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_rules),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_rules_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyRunFinished(ruleName: String, succeeded: Int, failed: Int, bytesMoved: Long) {
        if (!canNotify()) return
        ensureChannel()

        val hasFailures = failed > 0
        val title = if (hasFailures) {
            context.getString(R.string.notif_rule_failed_title, ruleName)
        } else {
            context.getString(R.string.notif_rule_done_title, ruleName)
        }
        val body = if (hasFailures) {
            context.getString(R.string.notif_rule_failed_body, failed, failed + succeeded)
        } else {
            context.getString(
                R.string.notif_rule_done_body,
                succeeded,
                Formatters.bytes(bytesMoved),
            )
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            // The POST_NOTIFICATIONS check is canNotify(), called at the top of
            // this method; lint cannot follow it across the call.
            @SuppressLint("MissingPermission")
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val CHANNEL_ID = "fileflow_rules"
        private const val NOTIFICATION_ID = 1001
    }
}
