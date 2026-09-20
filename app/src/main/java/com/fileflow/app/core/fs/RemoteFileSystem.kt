package com.fileflow.app.core.fs

import java.io.InputStream

/**
 * One entry on a remote source.
 *
 * Deliberately not [com.fileflow.app.core.model.FileItem]: that type is built
 * around a local path — an absolute filesystem location the rest of the app can
 * hand to `File`, `MediaStore` or a thumbnail loader. A remote entry has a path
 * only its own source understands, so it gets its own shape rather than
 * pretending to be something a `File` could open.
 */
@androidx.compose.runtime.Immutable
data class RemoteEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
}

/**
 * A storage source the app can browse and write to.
 *
 * The four implementations (WebDAV, S3, SFTP, SMB) share nothing but this
 * interface, which is exactly the point: the browser, the copy engine and the
 * transfer screen do not know or care which protocol is underneath. Adding a
 * fifth source means adding one implementation, not touching the UI.
 *
 * Every call is `suspend` and runs its own IO: these are network round trips,
 * and a screen that blocks a frame on a handshake is a screen that has to be
 * fixed twice.
 */
interface RemoteFileSystem {

    /** The connection this instance speaks for. */
    val connectionId: String

    /** Shown in the browser's title bar. */
    val label: String

    /** Read-only sources (an S3 bucket with a read-only key, say) hide their verbs. */
    val readOnly: Boolean get() = false

    /** Where a freshly opened connection starts. */
    fun home(): String

    suspend fun list(path: String): List<RemoteEntry>

    /** The parent of [path], or null at the root. */
    fun parent(path: String): String?

    suspend fun openInput(path: String): InputStream

    /**
     * Streams [source] to [path]. [sizeBytes] is a hint for protocols that need
     * the length up front (S3 and WebDAV both want a Content-Length); null means
     * the caller does not know it.
     */
    suspend fun write(
        path: String,
        name: String,
        source: InputStream,
        sizeBytes: Long?,
        onProgress: (bytesWritten: Long) -> Unit = {},
    )

    suspend fun mkdir(path: String)

    suspend fun remove(path: String, isDirectory: Boolean)

    suspend fun rename(from: String, to: String)

    /** Releases sockets and credentials. Called when a connection is closed. */
    fun close() {}
}

/** Thrown when a source cannot be reached or refuses the operation. */
class RemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Joins a remote path with a name, tolerating roots that end in a separator. */
fun remotePath(parent: String, name: String): String = when {
    parent.isEmpty() || parent == "/" -> "/$name"
    else -> "${parent.trimEnd('/')}/$name"
}

/** The last segment of a remote path, or the path itself at the root. */
fun remoteName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
