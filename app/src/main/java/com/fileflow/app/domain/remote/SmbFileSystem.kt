package com.fileflow.app.domain.remote

import com.fileflow.app.core.fs.RemoteEntry
import com.fileflow.app.core.fs.RemoteException
import com.fileflow.app.core.fs.RemoteFileSystem
import com.fileflow.app.core.fs.remotePath
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SMB / CIFS, which is what a Windows share or a NAS actually speaks.
 *
 * jcifs-ng is the pure-Java implementation: no NDK, no native library to build
 * or to keep per architecture, at the cost of a Bouncy Castle in the APK for
 * SMB3 signing. The `SmbFile` API is close enough to `java.io.File` that the
 * awkward part of this file is only the URL building — a share name is not a
 * path segment, and a space in a folder name has to survive the trip.
 */
class SmbFileSystem(
    override val connectionId: String,
    override val label: String,
    private val connection: RemoteConnection,
) : RemoteFileSystem {

    private val context: CIFSContext = createContext()
    private val sharePath: String = connection.path.trim('/')

    private fun createContext(): CIFSContext {
        val properties = Properties().apply {
            // SMB1 is off by default on every current Windows and on most NAS
            // boxes; asking for 2.x and 3.x first is what makes this work on the
            // servers people actually have.
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "30000")
        }
        val base = BaseContext(PropertyConfiguration(properties))
        if (connection.user.isBlank() && connection.secret.isBlank()) return base
        return base.withCredentials(
            NtlmPasswordAuthenticator(connection.extra.trim(), connection.user, connection.secret)
        )
    }

    override fun home(): String = if (sharePath.isEmpty()) "/" else "/$sharePath"

    override fun parent(path: String): String? {
        val trimmed = path.trim('/')
        if (trimmed.isEmpty()) return null
        val parent = trimmed.substringBeforeLast('/', "")
        return if (parent.isEmpty()) "/" else "/$parent"
    }

    /** `smb://host/share/folder`, with the characters SMB treats specially escaped. */
    private fun toUrl(path: String): String {
        val full = listOf(sharePath, path.trim('/'))
            .filter { it.isNotEmpty() }
            .joinToString("/")
        val encoded = full.split('/').joinToString("/") { escape(it) }
        return "smb://${connection.host}/$encoded"
    }

    private fun escape(segment: String): String = buildString(segment.length) {
        segment.forEach { character ->
            when (character) {
                ' ', '%', '#', '?', '\\', '&' -> append("%%%02X".format(character.code))
                else -> append(character)
            }
        }
    }

    private fun file(path: String): SmbFile = runCatching { SmbFile(toUrl(path), context) }
        .getOrElse { throw RemoteException("Bad path: ${it.message}", it) }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val folder = file(path)
        val children = runCatching { folder.listFiles() }.getOrElse { error ->
            throw RemoteException(explain(error), error)
        }
        children.map { child ->
            val directory = runCatching { child.isDirectory }.getOrDefault(false)
            val size = if (directory) 0L else runCatching { child.length() }.getOrDefault(0L)
            RemoteEntry(
                path = remotePath(path, child.name.trimEnd('/')),
                name = child.name.trimEnd('/'),
                isDirectory = directory,
                sizeBytes = size,
                lastModified = runCatching { child.lastModified() }.getOrDefault(0L),
            )
        }.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun openInput(path: String): InputStream = withContext(Dispatchers.IO) {
        val source = file(path)
        runCatching { SmbFile(smbUrlOf(source), context).openInputStream() }.getOrElse { error ->
            throw RemoteException(explain(error), error)
        }
    }

    private fun smbUrlOf(file: SmbFile): String = runCatching { file.path }.getOrDefault("")

    override suspend fun write(
        path: String,
        name: String,
        source: InputStream,
        sizeBytes: Long?,
        onProgress: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val target = remotePath(path, name)
        ensureDirectory(path)
        val stream: OutputStream = runCatching { file(target).openOutputStream() }.getOrElse { error ->
            throw RemoteException(explain(error), error)
        }
        stream.use { output ->
            val buffer = ByteArray(COPY_BUFFER)
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
                onProgress(total)
            }
        }
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        ensureDirectory(path)
    }

    override suspend fun remove(path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        val target = file(path)
        if (isDirectory) {
            deleteRecursively(target)
        } else {
            runCatching { target.delete() }.getOrElse { error ->
                throw RemoteException(explain(error), error)
            }
        }
    }

    private fun deleteRecursively(target: SmbFile) {
        val children = runCatching { target.listFiles() }.getOrDefault(emptyArray())
        children.forEach { child ->
            val directory = runCatching { child.isDirectory }.getOrDefault(false)
            if (directory) deleteRecursively(child) else runCatching { child.delete() }
        }
        runCatching { target.delete() }
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        val source = file(from)
        val target = file(to)
        runCatching { source.renameTo(target) }.getOrElse { error ->
            throw RemoteException(explain(error), error)
        }
    }

    override fun close() {
        runCatching { context.close() }
    }

    private suspend fun ensureDirectory(path: String) {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return
        var accumulated = ""
        for (segment in segments) {
            accumulated = "$accumulated/$segment"
            runCatching { file(accumulated).mkdir() }
        }
    }

    /**
     * jcifs reports a refused login and a missing share with the same generic
     * message, so the common ones are named here instead of being passed on as
     * "SmbException: 0x…".
     */
    private fun explain(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("STATUS_LOGON_FAILURE", true) ||
                message.contains("Access is denied", true) ->
                "Login refused: check the username, password and domain"
            message.contains("STATUS_BAD_NETWORK_NAME", true) ||
                message.contains("status = 0xc00000cc", true) ->
                "Share not found: check the share name and the path"
            message.contains("UnknownHost", true) || message.contains("unresolved", true) ->
                "Host not found: check the address"
            message.contains("timed out", true) || message.contains("timeout", true) ->
                "The server did not answer: check the port and the firewall"
            else -> "SMB failed: ${message.ifBlank { error.javaClass.simpleName }}"
        }
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
    }
}
