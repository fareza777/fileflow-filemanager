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
import com.fileflow.app.core.semantic.PhotoWorkManager
import com.fileflow.app.core.semantic.PhotoWorkState
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
 * Reading photos, in a service so it outlives the screen.
 *
 * The first run is thousands of photos through two on-device models — minutes
 * of work that nobody watches from one screen. It runs here instead, with a
 * notification that says what is being read and can stop it, and the screen
 * observes the same [PhotoWorkManager] state the service does, so returning to
 * it shows the same number rather than a restarted run.
 *
 * One job at a time: the two passes share the same models and the same disk,
 * and a queued second start is answered by the job that is already running.
 */
@AndroidEntryPoint
class PhotoWorkService : Service() {

    @Inject lateinit var work: PhotoWorkManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val kind = when (intent?.action) {
            ACTION_EMBED -> PhotoWorkState.Kind.EMBEDDING
            else -> PhotoWorkState.Kind.INDEXING
        }

        startForegroundCompat(buildNotification(null, kind))
        if (job?.isActive == true) return START_STICKY

        job = scope.launch {
            runCatching {
                when (kind) {
                    PhotoWorkState.Kind.INDEXING -> work.runIndex()
                    PhotoWorkState.Kind.EMBEDDING -> work.runEmbed()
                }
            }
            stopSelf()
        }
        watching = scope.launch {
            work.state.collectLatest { state ->
                if (state is PhotoWorkState.Running || state is PhotoWorkState.Failed) {
                    notify(buildNotification(state, kind))
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
        // it; the reading is still running either way.
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: PhotoWorkState?, kind: PhotoWorkState.Kind): Notification {
        // The channel exists before this notification is handed to
        // startForeground; posting to a missing channel kills the process.
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
            Intent(this, PhotoWorkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val running = state as? PhotoWorkState.Running
        val text = when (state) {
            is PhotoWorkState.Running ->
                "${state.done}/${state.total} · ${state.name}"
            is PhotoWorkState.Failed -> state.reason
            else -> when (kind) {
                PhotoWorkState.Kind.INDEXING -> getString(R.string.photo_indexing)
                PhotoWorkState.Kind.EMBEDDING -> getString(R.string.photo_embed_title)
            }
        }
        val title = when (kind) {
            PhotoWorkState.Kind.INDEXING -> getString(R.string.photo_indexing)
            PhotoWorkState.Kind.EMBEDDING -> getString(R.string.photo_embed_title)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.op_cancel), stop)
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
                getString(R.string.photo_work_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.photo_work_channel_desc)
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
        private const val CHANNEL_ID = "fileflow_photo_work"
        private const val NOTIFICATION_ID = 4403
        const val ACTION_STOP = "com.fileflow.app.action.STOP_PHOTO_WORK"
        private const val ACTION_EMBED = "com.fileflow.app.action.EMBED_PHOTOS"

        /**
         * Creates the channel before anything needs it, for the same reason the
         * model download's channel is created early: a foreground service that
         * posts to a channel which does not exist yet takes the process with it.
         */
        fun ensureChannelEarly(context: Context) {
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                if (manager.getNotificationChannel(CHANNEL_ID) != null) return
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.photo_work_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.photo_work_channel_desc)
                        setShowBadge(false)
                    }
                )
            }
        }

        fun startIndexing(context: Context) {
            start(context, null)
        }

        /** Reading meaning: the model's pass over the indexed photos. */
        fun startEmbedding(context: Context) {
            start(context, ACTION_EMBED)
        }

        private fun start(context: Context, action: String?) {
            val intent = Intent(context, PhotoWorkService::class.java).apply {
                action?.let { setAction(it) }
            }
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PhotoWorkService::class.java)) }
        }
    }
}
