package com.fileflow.app.domain.remote

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.util.Base64

/**
 * The stored keys, as JSch sees them.
 *
 * Written against the interface rather than pointed at a known-hosts file,
 * because the file format JSch expects is OpenSSH's and the app has its own
 * store; what matters is the three answers JSch can get back — this is the same
 * server, this is a different server, or this is a new one — and they line up
 * exactly with [HostKeyPolicy].
 */
internal class StoredHostKeys(
    private val remembered: List<KnownHostKey>,
    private val hostId: String,
    private val onTrust: (KnownHostKey) -> Unit,
) : HostKeyRepository {

    /**
     * JSch passes the host as it was used in the session, which may or may not
     * carry the port; both spellings are matched so a non-standard port does not
     * end up bypassing the check.
     */
    private fun stored(host: String): KnownHostKey? = remembered.firstOrNull { entry ->
        entry.host == hostId || entry.host == host || entry.host.startsWith("$host:")
    }

    override fun check(host: String, key: ByteArray): Int {
        val entry = stored(host) ?: return HostKeyRepository.NOT_INCLUDED
        return if (HostKeyPolicy.decide(entry, key) == HostKeyVerdict.MATCH) {
            HostKeyRepository.OK
        } else {
            HostKeyRepository.CHANGED
        }
    }

    /** Called by JSch only after [check] said the host was unknown. */
    override fun add(hostkey: HostKey, ui: UserInfo?) {
        // JSch reports the key and the algorithm as text; the fingerprint is
        // over the bytes behind that text.
        val encoded = hostkey.key ?: return
        val raw = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return
        onTrust(
            KnownHostKey(
                host = hostId,
                algorithm = hostkey.type.orEmpty(),
                key = encoded,
                fingerprint = HostKeyPolicy.fingerprint(raw),
                addedAt = System.currentTimeMillis(),
            )
        )
    }

    override fun remove(host: String, type: String) = Unit

    override fun remove(host: String, type: String, key: ByteArray) = Unit

    override fun getKnownHostsRepositoryID(): String = "fileflow"

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String, type: String): Array<HostKey> = emptyArray()
}

/**
 * Answers JSch's questions with "no secret to give" and "yes, trust this one".
 *
 * The yes is safe precisely because of when it is asked: JSch only reaches this
 * question when the repository had nothing for the host. A key that differs from
 * a remembered one never gets here — it is refused before a password is sent.
 */
internal object TrustFirstUse : UserInfo {
    override fun getPassphrase(): String? = null

    override fun getPassword(): String? = null

    override fun promptPassword(message: String?): Boolean = false

    override fun promptPassphrase(message: String?): Boolean = false

    override fun promptYesNo(message: String?): Boolean = true

    override fun showMessage(message: String?) = Unit
}
