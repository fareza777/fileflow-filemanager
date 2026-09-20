package com.fileflow.app.domain.remote

import com.fileflow.app.core.fs.RemoteEntry
import com.fileflow.app.core.fs.RemoteException
import com.fileflow.app.core.fs.RemoteFileSystem
import com.fileflow.app.core.fs.remotePath
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SFTP over SSH, with a session that is opened once and a channel per call.
 *
 * A channel is a single stream of requests, so sharing one across a listing and
 * three downloads would interleave them; opening one per operation costs a
 * round trip and removes the whole class of bug. The session is reused, which is
 * where the expensive part of SSH lives — the handshake and the key exchange.
 */
class SftpFileSystem(
    override val connectionId: String,
    override val label: String,
    private val connection: RemoteConnection,
) : RemoteFileSystem {

    private val lock = Mutex()
    private var session: Session? = null

    override fun home(): String = connection.path.trimEnd('/').ifEmpty { "/" }

    override fun parent(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty() || trimmed == "/") return null
        val parent = trimmed.substringBeforeLast('/', "")
        return parent.ifEmpty { "/" }
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        withChannel { channel ->
            val entries = channel.ls(path.ifBlank { home() })
            entries.filterIsInstance<ChannelSftp.LsEntry>()
                .filter { it.filename != "." && it.filename != ".." }
                .map { entry ->
                    val attributes = entry.attrs
                    RemoteEntry(
                        path = remotePath(path.ifBlank { home() }, entry.filename),
                        name = entry.filename,
                        isDirectory = attributes.isDir,
                        sizeBytes = attributes.size,
                        lastModified = attributes.mTime.toLong() * 1000L,
                    )
                }
                .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    override suspend fun openInput(path: String): InputStream = withContext(Dispatchers.IO) {
        val channel = channel()
        val stream = runCatching { channel.get(path) }.getOrElse {
            runCatching { channel.disconnect() }
            throw RemoteException("Download failed: ${it.message}", it)
        }
        object : FilterInputStream(stream) {
            override fun close() {
                runCatching { super.close() }
                runCatching { channel.disconnect() }
            }
        }
    }

    override suspend fun write(
        path: String,
        name: String,
        source: InputStream,
        sizeBytes: Long?,
        onProgress: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val target = remotePath(path, name)
        ensureDirectory(path)
        withChannel { channel ->
            val monitor = object : SftpProgressMonitor {
                override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit

                override fun count(count: Long): Boolean {
                    onProgress(count)
                    return true
                }

                override fun end() = Unit
            }
            runCatching { channel.put(source, target, monitor) }.getOrElse {
                throw RemoteException("Upload failed: ${it.message}", it)
            }
        }
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        ensureDirectory(path)
    }

    override suspend fun remove(path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        withChannel { channel ->
            if (!isDirectory) {
                runCatching { channel.rm(path) }.getOrElse {
                    throw RemoteException("Delete failed: ${it.message}", it)
                }
            } else {
                deleteRecursively(channel, path)
            }
        }
    }

    private fun deleteRecursively(channel: ChannelSftp, path: String) {
        val children = runCatching { channel.ls(path) }
            .getOrNull()
            ?.filterIsInstance<ChannelSftp.LsEntry>()
            ?.filter { it.filename != "." && it.filename != ".." }
            .orEmpty()
        children.forEach { child ->
            val childPath = remotePath(path, child.filename)
            if (child.attrs.isDir) deleteRecursively(channel, childPath)
            else runCatching { channel.rm(childPath) }
        }
        runCatching { channel.rmdir(path) }
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        withChannel { channel ->
            runCatching { channel.rename(from, to) }.getOrElse {
                throw RemoteException("Rename failed: ${it.message}", it)
            }
        }
    }

    override fun close() {
        runCatching { session?.disconnect() }
        session = null
    }

    private suspend fun <T> withChannel(block: (ChannelSftp) -> T): T {
        val channel = channel()
        try {
            return block(channel)
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    /** Called from an IO dispatcher; the session is created once and reused. */
    private suspend fun channel(): ChannelSftp {
        val active = lock.withLock {
            val current = session
            if (current != null && current.isConnected) {
                current
            } else {
                connect().also { session = it }
            }
        }
        return runCatching { active.openChannel("sftp") as ChannelSftp }
            .getOrElse { throw RemoteException("Could not open an SFTP channel: ${it.message}", it) }
            .also { channel ->
                runCatching { channel.connect(CONNECT_TIMEOUT_MS) }.getOrElse {
                    runCatching { channel.disconnect() }
                    throw RemoteException("Could not open an SFTP channel: ${it.message}", it)
                }
            }
    }

    private fun connect(): Session {
        if (connection.host.isBlank()) throw RemoteException("No host set for this connection")
        return runCatching {
            val jsch = JSch()
            val keyPath = connection.extra.trim()
            val usingKey = keyPath.isNotEmpty() && File(keyPath).isFile
            if (usingKey) {
                jsch.addIdentity(keyPath, connection.secret.takeIf { it.isNotBlank() })
            }
            val created = jsch.getSession(connection.user, connection.host, connection.portOr)
            if (!usingKey && connection.secret.isNotBlank()) created.setPassword(connection.secret)
            // Host keys are not verified, which is a deliberate hole and the
            // honest default for a file manager without a known-hosts store:
            // the alternative is refusing to connect to a server the user just
            // typed in. The connection list tells the user what it does.
            created.setConfig("StrictHostKeyChecking", "no")
            created.timeout = CONNECT_TIMEOUT_MS
            created.connect(CONNECT_TIMEOUT_MS)
            created
        }.getOrElse { error ->
            throw RemoteException("Could not connect: ${error.message ?: "unknown error"}", error)
        }
    }

    /** Creates every missing ancestor, ignoring the ones that are already there. */
    private suspend fun ensureDirectory(path: String) {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return
        withChannel { channel ->
            var accumulated = if (path.startsWith("/")) "" else "."
            for (segment in segments) {
                accumulated = "$accumulated/$segment"
                runCatching { channel.mkdir(accumulated) }
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
    }
}
