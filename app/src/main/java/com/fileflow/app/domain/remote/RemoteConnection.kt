package com.fileflow.app.domain.remote

import kotlinx.serialization.Serializable

/** The protocols a connection can speak. */
@Serializable
enum class RemoteKind {
    WEBDAV,
    S3,
    SFTP,
    SMB,
    ;

    val defaultPort: Int
        get() = when (this) {
            WEBDAV -> 0
            S3 -> 0
            SFTP -> 22
            SMB -> 445
        }
}

/**
 * A saved server.
 *
 * One shape for four protocols rather than four tables: the fields mean slightly
 * different things per protocol — `user` is an SSH login on SFTP and an access
 * key id on S3, `path` is a start directory on SFTP and a bucket name on S3 —
 * but they are the same four questions, and four separate forms would be four
 * times the screen for the same answers.
 */
@Serializable
data class RemoteConnection(
    val id: String,
    val kind: RemoteKind,
    val label: String,
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
    /** Password, access key secret, or key passphrase. Kept encrypted at rest. */
    val secret: String = "",
    /** Base path, bucket, or share. */
    val path: String = "",
    /** HTTPS for WebDAV and S3; ignored by the other two. */
    val secure: Boolean = true,
    /** Region for S3, domain for SMB, private key path for SFTP. */
    val extra: String = "",
) {
    val portOr: Int get() = if (port > 0) port else kind.defaultPort

    /** What the connection row shows under the name. */
    val summary: String
        get() = buildString {
            append(kind.name)
            append(" · ")
            append(host.ifBlank { "—" })
            if (path.isNotBlank()) append("/").append(path.trimStart('/'))
        }
}

/** Every connection, in the order the user last saw them. */
@Serializable
data class RemoteConnections(val items: List<RemoteConnection> = emptyList())
