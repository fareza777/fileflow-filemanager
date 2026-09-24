package com.fileflow.app.domain.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.hostKeyStore: DataStore<Preferences> by preferencesDataStore(name = "fileflow_hostkeys")

/** One server key this app has been told to trust, and when. */
@Serializable
data class KnownHostKey(
    /** `host:port`, so the same host on two ports is two servers. */
    val host: String,
    val algorithm: String,
    /** The key itself, base64, as the server sent it. */
    val key: String,
    val fingerprint: String,
    val addedAt: Long,
)

@Serializable
data class KnownHostKeys(val items: List<KnownHostKey> = emptyList())

/** What should happen to a key the server just presented. */
enum class HostKeyVerdict {
    /** Same server as last time. Connect. */
    MATCH,

    /** A different key for a host we have seen: the one case worth shouting about. */
    CHANGED,

    /** Never seen. Trust on first use, and say so. */
    UNKNOWN,
}

/**
 * The decision table for host keys, kept pure so it can be tested without a
 * network and reasoned about without a server.
 */
object HostKeyPolicy {

    /**
     * Compares the bytes behind the two encodings rather than the strings: JSch
     * hands its own base64 over, and a difference in padding or line breaks
     * would make every connection look like a changed key — which fails closed,
     * but fails on servers that are perfectly fine.
     */
    fun decide(stored: KnownHostKey?, key: ByteArray): HostKeyVerdict = when {
        stored == null -> HostKeyVerdict.UNKNOWN
        else -> {
            val remembered = runCatching { Base64.getDecoder().decode(stored.key) }.getOrNull()
            if (remembered != null && remembered.contentEquals(key)) {
                HostKeyVerdict.MATCH
            } else {
                HostKeyVerdict.CHANGED
            }
        }
    }

    /**
     * The same shape OpenSSH prints, so the value here can be compared with
     * `ssh -v` or `ssh-keyscan` on a computer instead of being trusted blindly.
     */
    fun fingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    fun encode(key: ByteArray): String = Base64.getEncoder().encodeToString(key)

    fun hostId(host: String, port: Int): String = "$host:$port"
}

/**
 * Where the remembered keys live.
 *
 * A file of its own rather than a column on the connection: the key belongs to
 * the server, not to one saved connection, and two connections to the same box
 * should not be able to disagree about which key is the right one.
 */
@Singleton
class SftpHostKeys @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }

    val keys: Flow<List<KnownHostKey>> = context.hostKeyStore.data.map { prefs ->
        decode(prefs[KEY_HOSTS].orEmpty())
    }

    suspend fun all(): List<KnownHostKey> = keys.first()

    suspend fun find(hostId: String): KnownHostKey? = all().firstOrNull { it.host == hostId }

    suspend fun remember(entry: KnownHostKey) {
        val next = all().filterNot { it.host == entry.host } + entry
        write(next.sortedBy { it.host })
    }

    /** Forgets one server, so the next connection is treated as a first one. */
    suspend fun forget(hostId: String) {
        write(all().filterNot { it.host == hostId })
    }

    private suspend fun write(items: List<KnownHostKey>) {
        val encoded = json.encodeToString(KnownHostKeys.serializer(), KnownHostKeys(items))
        context.hostKeyStore.edit { prefs -> prefs[KEY_HOSTS] = encoded }
    }

    private fun decode(raw: String): List<KnownHostKey> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(KnownHostKeys.serializer(), raw).items }
            .getOrDefault(emptyList())
    }

    private companion object {
        val KEY_HOSTS = stringPreferencesKey("known_host_keys")
    }
}
