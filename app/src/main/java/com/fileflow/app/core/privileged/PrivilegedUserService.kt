package com.fileflow.app.core.privileged

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.TimeUnit

/**
 * The half of privileged access that runs with the shell's uid.
 *
 * Shizuku does not hand an app a shell; it starts a service of the app's own in
 * a separate process that runs as the shell user, and the app talks to it over
 * binder. That is why this class exists at all: it is the same code as the root
 * path, in a process where "shell" is not a privilege to ask for but the
 * identity it already has.
 *
 * The commands it runs are the ones the app builds, and the app quotes every
 * path it puts into them — this side does not sanitise anything, because
 * sanitising in two places is how the two places end up disagreeing.
 */
class PrivilegedUserService : Service() {

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IPrivilegedService.Stub() {
        override fun destroy() {
            stopSelf()
        }

        /**
         * Runs one command and returns everything it printed, with the exit
         * code on the first line so the caller can tell "nothing to say" from
         * "it worked".
         */
        override fun runCommand(command: String, timeoutSeconds: Int): String {
            val timeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
            return runCatching {
                val process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    process.destroy()
                    return@runCatching "1\nThe command timed out"
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                "${process.exitValue()}\n$output"
            }.getOrElse { error ->
                "1\n${error.message ?: "the command could not start"}"
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopSelf()
        return super.onUnbind(intent)
    }

    private companion object {
        const val MAX_TIMEOUT_SECONDS = 600
    }
}
