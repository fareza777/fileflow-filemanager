package com.fileflow.app.core.privileged

import android.app.Service
import android.content.Intent
import android.os.IBinder

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
         *
         * stderr is folded into stdout here: this side runs as the shell, and
         * the app has no use for the distinction — a command that failed prints
         * why on whichever stream it likes.
         */
        override fun runCommand(command: String, timeoutSeconds: Int): String {
            val timeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS).toLong()
            val process = runCatching {
                ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
            }.getOrElse { error ->
                return "1\n${error.message ?: "the command could not start"}"
            }
            // Draining while the process runs, not after: output larger than a
            // pipe buffer would otherwise deadlock this call until the timeout.
            val result = ShellProcess.drain(process, timeout)
            return "${result.code}\n${if (result.ok) result.stdout else result.stdout + result.stderr}"
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
