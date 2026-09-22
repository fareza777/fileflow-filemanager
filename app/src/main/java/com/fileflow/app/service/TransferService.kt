package com.fileflow.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.fileflow.app.MainActivity
import com.fileflow.app.R
import com.fileflow.app.domain.transfer.HttpFileServer
import com.fileflow.app.domain.transfer.TransferLink
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps the transfer server alive while the user is somewhere else.
 *
 * The server itself is a singleton and needs nothing from a Service — but
 * Android does: an app without a foreground service is a candidate for being
 * frozen the moment it stops being visible, which would drop the connection
 * mid-download. So the service exists for one reason, to hold the process in
 * the foreground with a notification that says who is connected and offers to
 * stop — the price of the transfer being invisible otherwise.
 */
@AndroidEntryPoint
class TransferService : Service() {

    @Inject lateinit var server: HttpFileServer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val root = intent?.getStringExtra(EXTRA_ROOT).orEmpty()
        val port = intent?.getIntExtra(EXTRA_PORT, HttpFileServer.DEFAULT_PORT)
            ?: HttpFileServer.DEFAULT_PORT

        startForegroundCompat(notification(port = port, root = root))

        if (!server.isRunning) {
            if (!server.start(root = root, port = port)) {
                // Nothing to keep alive if the port never opened; the screen
                // shows the reason from the server's own state.
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // The address is only known after the socket is bound, so the
        // notification is refreshed with it — a number the user needs to type
        // is worth the second update.
        notify(notification(port = server.state.value.port, root = server.state.value.root))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun notification(port: Int, root: String): Notification {
        ensureChannel()
        val address = server.addresses()
            .firstOrNull { it.link == TransferLink.WIFI }
            ?: server.addresses().firstOrNull()
        val url = if (server.isRunning && address != null) {
            server.pageUrl(address.host)
        } else {
            getString(R.string.transfer_starting)
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TRANSFER, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transfer)
            .setContentTitle(getString(R.string.transfer_notification_title))
            .setContentText(url)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$url\n$root")
            )
            .setContentIntent(open)
            .addAction(0, getString(R.string.transfer_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.transfer_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.transfer_channel_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "fileflow_transfer"
        private const val NOTIFICATION_ID = 4401

        const val ACTION_STOP = "com.fileflow.app.action.STOP_TRANSFER"
        const val EXTRA_ROOT = "root"
        const val EXTRA_PORT = "port"

        fun start(context: Context, root: String, port: Int) {
            val intent = Intent(context, TransferService::class.java)
                .putExtra(EXTRA_ROOT, root)
                .putExtra(EXTRA_PORT, port)
            // The app's floor is Oreo, so the foreground entry point is always
            // the right one.
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }
    }
}
