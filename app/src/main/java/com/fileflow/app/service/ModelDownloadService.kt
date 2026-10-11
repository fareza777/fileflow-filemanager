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
import com.fileflow.app.core.semantic.DownloadState
import com.fileflow.app.core.semantic.ModelDownloadManager
import com.fileflow.app.core.semantic.SemanticModels
import com.fileflow.app.core.util.Formatters
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The model download, in a service so it outlives the screen.
 *
 * A 795 MB download is not something to tie to a screen the user is looking at:
 * the phone will be locked, the app will be left, and the file still has to
 * arrive. A foreground service with a notification is what Android requires for
 * that, and it happens to be the honest interface too — the transfer is visible,
 * says how far along it is, and can be stopped from the shade.
 *
 * The download itself lives in [ModelDownloadManager], which resumes from
 * whatever is on disk; this only keeps the process alive and mirrors the
 * progress into the notification.
 */
@AndroidEntryPoint
class ModelDownloadService : Service() {

    @Inject lateinit var downloads: ModelDownloadManager
    @Inject lateinit var models: SemanticModels

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(null))
        if (work?.isActive == true) return START_STICKY

        work = scope.launch {
            // Pick up where a previous run stopped before asking the network
            // anything, so the notification opens with the real number.
            runCatching { downloads.restoreState() }
            runCatching { downloads.downloadAll() }
            stopSelf()
        }
        watching = scope.launch {
            downloads.state.collectLatest { state ->
                if (state is DownloadState.Running || state is DownloadState.Failed) {
                    notify(buildNotification(state))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watching?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(notification: Notification) {
        // A notification that cannot be posted must never take the process with
        // it; the download is still running either way.
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: DownloadState?): Notification {
        // The channel has to exist before this notification is handed to
        // startForeground: posting to a channel that is not there makes Android
        // reject it, and the rejection arrives as an exception thrown in the
        // main thread's handler — which no try/catch on our side can catch. That
        // was the crash on tapping download.
        ensureChannel()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ModelDownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val running = state as? DownloadState.Running
        val text = when (state) {
            is DownloadState.Running ->
                "${state.asset} · ${Formatters.bytes(state.done)} / ${Formatters.bytes(state.total)}"
            is DownloadState.Failed -> state.reason
            else -> getString(R.string.model_download_starting)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.model_download_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.transfer_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (running != null && running.total > 0) {
                    setProgress(100, ((running.done * 100) / running.total).toInt(), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.model_download_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.model_download_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }
    }

    companion object {
        private const val CHANNEL_ID = "fileflow_model_download"
        private const val NOTIFICATION_ID = 4402
        const val ACTION_STOP = "com.fileflow.app.action.STOP_MODEL_DOWNLOAD"

        /**
         * Creates the channel before anything needs it.
         *
         * A foreground service that posts to a channel which does not exist yet
         * is rejected by the framework, and the rejection arrives as an
         * exception on the main thread's handler — the process dies with
         * "Bad notification for startForeground", and no try/catch around
         * startForeground can stop it. Making the channel early at app start
         * means the service can never be the first thing to post.
         */
        fun ensureChannelEarly(context: Context) {
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                if (manager.getNotificationChannel(CHANNEL_ID) != null) return
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.model_download_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.model_download_channel_desc)
                        setShowBadge(false)
                    }
                )
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ModelDownloadService::class.java)) }
        }
    }
}
