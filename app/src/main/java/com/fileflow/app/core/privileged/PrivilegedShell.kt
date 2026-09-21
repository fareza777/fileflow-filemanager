package com.fileflow.app.core.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.fileflow.app.BuildConfig
import com.fileflow.app.core.util.Paths
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** What a privileged shell can be reached through, if anything. */
enum class PrivilegedEngine { SHIZUKU, ROOT, NONE }

data class AccessStatus(
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuGranted: Boolean = false,
    val rootAvailable: Boolean = false,
) {
    val engine: PrivilegedEngine
        get() = when {
            shizukuRunning && shizukuGranted -> PrivilegedEngine.SHIZUKU
            rootAvailable -> PrivilegedEngine.ROOT
            else -> PrivilegedEngine.NONE
        }

    val usable: Boolean get() = engine != PrivilegedEngine.NONE
}

/** One directory entry, as the shell reported it. */
@androidx.compose.runtime.Immutable
data class PrivilegedEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

data class ShellResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = code == 0
}

/**
 * The parts of privileged access that can be reasoned about without a shell.
 *
 * Quoting is the one place where a mistake is a security bug rather than a
 * nuisance: a path with a quote or a semicolon in it turns "list this folder"
 * into "run this command". Both helpers here are pure and unit tested for
 * exactly that reason.
 */
object ShellText {

    /** A single-quoted literal, with embedded quotes closed and reopened. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Parses the tab-separated listing the shell command prints:
     * `type \t size \t modified \t name`. Anything that does not look like that
     * is skipped rather than guessed at, because a half-read row is a row that
     * claims the wrong size.
     */
    fun parseListing(output: String, parent: String): List<PrivilegedEntry> =
        output.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 4) return@mapNotNull null
                val type = parts[0].trim()
                val size = parts[1].trim().toLongOrNull() ?: 0L
                val modified = parts[2].trim().toLongOrNull() ?: 0L
                val name = parts.drop(3).joinToString("\t").trim()
                if (name.isEmpty() || name == "." || name == "..") return@mapNotNull null
                PrivilegedEntry(
                    path = remoteChild(parent, name),
                    name = name,
                    isDirectory = type == "d",
                    sizeBytes = if (type == "d") 0L else size,
                    // `stat -c %Y` reports seconds.
                    lastModified = modified * 1000L,
                )
            }
            .sortedWith(compareByDescending<PrivilegedEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()

    fun remoteChild(parent: String, name: String): String =
        if (parent.endsWith("/")) "$parent$name" else "$parent/$name"

    /** `du -sk` output is kilobytes plus a path. */
    fun parseDu(output: String): Long? =
        output.lineSequence()
            .firstOrNull()
            ?.trim()
            ?.substringBefore('\t')
            ?.substringBefore(' ')
            ?.toLongOrNull()
            ?.times(1024L)
}

/**
 * The same files, through the shell.
 *
 * Android hides `Android/data` and a few dozen other places from every app,
 * permission or not. The two ways past that are root and Shizuku — Shizuku
 * being a service that runs with the shell's rights, so the app never has to be
 * rooted. Either way the work is the same: run a command, read what it printed,
 * and be extremely careful about what goes into that command.
 *
 * There is no privileged *browsing* mode bolted onto the file manager. What
 * this offers is narrow and deliberate: look at a folder the app cannot read,
 * copy one file out of it into normal storage, or delete one — with the path
 * quoted and the operation named before it runs.
 */
@Singleton
class PrivilegedShell @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun status(): AccessStatus {
        val installed = isShizukuInstalled()
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = if (!running) {
            false
        } else {
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        }
        return AccessStatus(
            shizukuInstalled = installed,
            shizukuRunning = running,
            shizukuGranted = granted,
            rootAvailable = rootAvailable(),
        )
    }

    private fun isShizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.manager", 0)
        true
    }.getOrDefault(false)

    /** Root is probed by asking it to print something; a missing su answers nothing. */
    private fun rootAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "echo fileflow").start()
        val finished = process.waitFor(ROOT_PROBE_SECONDS, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!finished) process.destroy()
        finished && output.contains("fileflow")
    }.getOrDefault(false)

    /** Asks Shizuku for the permission; the answer arrives on the listener. */
    fun requestShizukuPermission(): Boolean = runCatching {
        if (Shizuku.isPreV11()) return@runCatching false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return@runCatching true
        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        true
    }.getOrDefault(false)

    /** Runs a command through whichever engine is available, or returns null. */
    suspend fun run(command: String, status: AccessStatus = status()): ShellResult? =
        withContext(Dispatchers.IO) {
            when (status.engine) {
                PrivilegedEngine.SHIZUKU -> {
                    val service = ensureBound() ?: return@withContext null
                    runCatching {
                        val answer = service.runCommand(command, COMMAND_TIMEOUT_SECONDS.toInt())
                        parseAnswer(answer)
                    }.getOrNull()
                }

                PrivilegedEngine.ROOT -> runProcess(ProcessBuilder("su", "-c", command).start())
                PrivilegedEngine.NONE -> null
            }
        }

    /**
     * Binds the user service the first time it is needed.
     *
     * Shizuku starts the service in its own process as the shell user, which
     * takes a moment, so the first command pays for the binding and every one
     * after it uses the same connection. A failure to bind is reported as "no
     * engine", never as an empty result: a command that did not run must not
     * look like a command that found nothing.
     */
    private suspend fun ensureBound(): IPrivilegedService? {
        boundService?.let { return it }
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        val started = runCatching {
            Shizuku.bindUserService(userServiceArgs, connection)
            true
        }.getOrDefault(false)
        if (!started) {
            pending = null
            return null
        }
        withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
        pending = null
        return boundService
    }

    /** `exitCode \n output`, as the service formats it. */
    private fun parseAnswer(answer: String): ShellResult {
        val separator = answer.indexOf('\n')
        if (separator < 0) return ShellResult(0, answer, "")
        val code = answer.substring(0, separator).trim().toIntOrNull() ?: 1
        return ShellResult(code, answer.substring(separator + 1), "")
    }

    suspend fun list(path: String, status: AccessStatus = status()): List<PrivilegedEntry> {
        val command = listingCommand(path)
        val result = run(command, status) ?: return emptyList()
        if (!result.ok && result.stdout.isBlank()) return emptyList()
        return ShellText.parseListing(result.stdout, path)
    }

    /**
     * Copies one file out to somewhere ordinary. This is the whole point of the
     * feature: a file the app cannot read becomes a file it can, without
     * changing any permission and without the user installing anything else.
     */
    suspend fun copyOut(
        source: String,
        destinationDirectory: String,
        status: AccessStatus = status(),
    ): File? {
        val target = ShellText.remoteChild(destinationDirectory, names(source))
        val command = "cp -f ${ShellText.quote(source)} ${ShellText.quote(target)} 2>&1"
        val result = run(command, status) ?: return null
        // `cp` says nothing when it worked; anything it says is a reason.
        return if (result.ok && File(target).exists()) File(target) else null
    }

    suspend fun remove(path: String, status: AccessStatus = status()): Boolean {
        val result = run("rm -rf ${ShellText.quote(path)} 2>&1", status) ?: return false
        return result.ok
    }

    suspend fun folderSize(path: String, status: AccessStatus = status()): Long? {
        val result = run("du -sk ${ShellText.quote(path)} 2>/dev/null", status) ?: return null
        return ShellText.parseDu(result.stdout)
    }

    /**
     * One `stat` per child, printed as tabs.
     *
     * `ls -l` would be shorter and is unparseable: its column layout, its date
     * format and its quoting all change with the tool and the locale, and a
     * misread column is a file manager that lies about sizes.
     */
    private fun listingCommand(path: String): String {
        val directory = ShellText.quote(path.trimEnd('/'))
        return buildString {
            append("cd $directory 2>/dev/null || exit 1; ")
            append("for f in * .[!.]*; do ")
            append("[ -e \"\$f\" ] || continue; ")
            append("if [ -d \"\$f\" ]; then t=d; else t=f; fi; ")
            append("s=\$(stat -c %s \"\$f\" 2>/dev/null || echo 0); ")
            append("m=\$(stat -c %Y \"\$f\" 2>/dev/null || echo 0); ")
            append("printf '%s\\t%s\\t%s\\t%s\\n' \"\$t\" \"\$s\" \"\$m\" \"\$f\"; ")
            append("done")
        }
    }

    private fun names(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** The user service, and the connection that hands it over. */
    private var boundService: IPrivilegedService? = null
    private var pending: CompletableDeferred<Boolean>? = null

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context, PrivilegedUserService::class.java))
            // Not a daemon: the service is started when it is first needed and
            // shut down when the last client unbinds, so it does not sit in the
            // shell's process forever.
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            boundService = IPrivilegedService.Stub.asInterface(service)
            pending?.complete(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    private fun runProcess(process: Process): ShellResult {
        val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            return ShellResult(code = -1, stdout = "", stderr = "The command timed out")
        }
        val stdout = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
        val stderr = runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")
        return ShellResult(process.exitValue(), stdout, stderr)
    }

    /** A hint for the UI, so a path can be shortened the way the app does it. */
    fun display(path: String): String = Paths.display(path)

    companion object {
        const val SHIZUKU_REQUEST_CODE = 4402
        private const val COMMAND_TIMEOUT_SECONDS = 30L
        private const val ROOT_PROBE_SECONDS = 3L

        /** Shizuku starts a process for the service; a second is generous. */
        private const val BIND_TIMEOUT_MS = 5_000L
    }
}
