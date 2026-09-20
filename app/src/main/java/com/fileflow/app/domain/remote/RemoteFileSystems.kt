package com.fileflow.app.domain.remote

import com.fileflow.app.core.fs.RemoteFileSystem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The open sources, one per connection.
 *
 * A connection is expensive to establish — a TCP handshake, a TLS negotiation,
 * an SSH key exchange or an NTLM challenge — and a browser makes dozens of calls
 * per screen. Keeping the live source here means the browser asks for
 * "the connection with this id" and gets an authenticated session, rather than
 * each screen deciding how and when to connect.
 */
@Singleton
class RemoteFileSystems @Inject constructor(
    private val store: RemoteConnectionStore,
) {

    private val lock = Mutex()
    private val open = mutableMapOf<String, RemoteFileSystem>()

    suspend fun open(connection: RemoteConnection): RemoteFileSystem = lock.withLock {
        open[connection.id]?.let { return@withLock it }
        val prepared = store.withSecret(connection)
        create(prepared).also { open[connection.id] = it }
    }

    /** Drops the session, which is how a failed login gets a second chance. */
    suspend fun disconnect(id: String) {
        lock.withLock { open.remove(id) }?.close()
    }

    suspend fun disconnectAll() {
        val current = lock.withLock {
            val snapshot = open.values.toList()
            open.clear()
            snapshot
        }
        current.forEach { runCatching { it.close() } }
    }

    private fun create(connection: RemoteConnection): RemoteFileSystem = when (connection.kind) {
        RemoteKind.WEBDAV -> WebDavFileSystem(connection.id, connection.label, connection)
        RemoteKind.S3 -> S3FileSystem(connection.id, connection.label, connection)
        RemoteKind.SFTP -> SftpFileSystem(connection.id, connection.label, connection)
        RemoteKind.SMB -> SmbFileSystem(connection.id, connection.label, connection)
    }

    companion object {
        /** A stable id for a new connection; also the key it is stored under. */
        fun newId(): String = "conn-${System.currentTimeMillis()}"
    }
}
