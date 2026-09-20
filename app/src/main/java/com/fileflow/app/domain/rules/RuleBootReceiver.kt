package com.fileflow.app.domain.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fileflow.app.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * WorkManager persists scheduled work across reboots, but a device that has
 * been off for a while can come back with stale delays. Re-syncing on boot and
 * after an app update keeps rules firing when the user expects them to.
 */
@AndroidEntryPoint
class RuleBootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: RuleScheduler

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        // goAsync keeps the process alive until the rescheduling finishes;
        // without it the system can kill the receiver mid-flight.
        val pending = goAsync()
        scope.launch {
            try {
                scheduler.syncAll()
            } finally {
                pending.finish()
            }
        }
    }
}
