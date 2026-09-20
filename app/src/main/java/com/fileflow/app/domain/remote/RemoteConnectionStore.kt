package com.fileflow.app.domain.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.connectionsStore: DataStore<Preferences> by preferencesDataStore(
    name = "fileflow_connections"
)

/**
 * Where the servers live between sessions.
 *
 * A preferences file rather than a Room table: connections are a handful of
 * records the user edits by hand, and keeping them out of the database means
 * adding a protocol never costs a schema migration. The secret field goes
 * through [SecretBox] on the way in and out, so what lands on disk is ciphertext.
 */
@Singleton
class RemoteConnectionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretBox,
) {

    private val json = Json { ignoreUnknownKeys = true }

    val connections: Flow<List<RemoteConnection>> = context.connectionsStore.data.map { prefs ->
        decode(prefs[KEY_CONNECTIONS].orEmpty())
    }

    suspend fun save(connection: RemoteConnection) {
        val current = read()
        val next = current.filterNot { it.id == connection.id } + connection
        write(next.sortedBy { it.label.lowercase() })
    }

    suspend fun remove(id: String) {
        write(read().filterNot { it.id == id })
    }

    /** A connection with its secret in the clear, for the source factory. */
    fun withSecret(connection: RemoteConnection): RemoteConnection =
        connection.copy(secret = secrets.open(connection.secret))

    private suspend fun read(): List<RemoteConnection> {
        var result: List<RemoteConnection> = emptyList()
        context.connectionsStore.edit { prefs ->
            result = decode(prefs[KEY_CONNECTIONS].orEmpty())
        }
        return result
    }

    private suspend fun write(items: List<RemoteConnection>) {
        val encoded = json.encodeToString(
            RemoteConnections.serializer(),
            RemoteConnections(
                items.map { it.copy(secret = secrets.seal(it.secret)) }
            ),
        )
        context.connectionsStore.edit { prefs -> prefs[KEY_CONNECTIONS] = encoded }
    }

    private fun decode(raw: String): List<RemoteConnection> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(RemoteConnections.serializer(), raw).items
        }.getOrDefault(emptyList())
    }

    private companion object {
        val KEY_CONNECTIONS = stringPreferencesKey("remote_connections")
    }
}
