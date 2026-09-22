package com.fileflow.app.core.privileged

import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Runs a shell command without the trap that catches everyone once.
 *
 * The obvious way to run a process is to wait for it and then read what it
 * printed:
 *
 * ```
 *   process.waitFor(...)               // never returns
 *   process.inputStream.readText()     // if it printed more than a pipe holds
 * ```
 *
 * A pipe holds about 64 KB. A process that writes more than that blocks on its
 * own write until somebody reads — and the somebody is waiting for the process
 * to exit. So `ls -la` in a folder with a few thousand files deadlocks: not
 * slowly, not partially, but until the timeout expires, after which the
 * command looks like it failed when in fact it had the answer ready.
 *
 * Reading both pipes on their own threads while the process runs removes the
 * deadlock entirely. The timeout still exists, but it now means what it says:
 * a command that genuinely takes too long.
 */
internal object ShellProcess {

    /** Output beyond this is dropped rather than held in memory. */
    private const val CAP = 4 * 1024 * 1024

    /** How long to wait for the reader threads once the process is gone. */
    private const val JOIN_MS = 1_000L

    fun run(command: List<String>, timeoutSeconds: Long): ShellResult {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (error: Exception) {
            return ShellResult(-1, "", error.message ?: "the command could not start")
        }
        return drain(process, timeoutSeconds)
    }

    /** Waits for [process], draining its output while it runs. */
    fun drain(process: Process, timeoutSeconds: Long): ShellResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val out = reader(process.inputStream, stdout)
        val err = reader(process.errorStream, stderr)

        val finished = runCatching {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        }.getOrDefault(false)

        if (!finished) {
            runCatching { process.destroy() }
            // The pipes close when the process dies; the readers finish there.
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        }
        out.join(JOIN_MS)
        err.join(JOIN_MS)

        if (!finished) return ShellResult(-1, stdout.toString(), "The command timed out")
        val code = runCatching { process.exitValue() }.getOrDefault(-1)
        return ShellResult(code, stdout.toString(), stderr.toString())
    }

    /**
     * Copies a stream into [sink] until it ends, on a thread of its own. The
     * thread is a daemon so a command that somehow outlives its process cannot
     * hold the app open.
     */
    private fun reader(stream: InputStream, sink: StringBuilder): Thread {
        val thread = Thread {
            runCatching {
                stream.bufferedReader().use { buffered ->
                    val buffer = CharArray(16 * 1024)
                    while (true) {
                        val read = buffered.read(buffer)
                        if (read <= 0) break
                        synchronized(sink) {
                            if (sink.length < CAP) {
                                sink.append(String(buffer, 0, read.coerceAtMost(CAP - sink.length)))
                            }
                        }
                    }
                }
            }
        }
        thread.isDaemon = true
        thread.name = "fileflow-shell"
        thread.start()
        return thread
    }
}
