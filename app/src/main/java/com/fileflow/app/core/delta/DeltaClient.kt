package com.fileflow.app.core.delta

import java.io.File
import java.io.IOException
import java.net.URLEncoder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The client half of a delta transfer: ask a FileFlow server what a file is
 * made of, work out which pieces are missing, and fetch only those.
 *
 * The server side is the same two endpoints the transfer page uses —
 * `/api/manifest` and `/api/chunk` — so a phone can sync a file from another
 * phone, and a desktop script can sync from either, with the same protocol.
 * Everything that decides *what* to move lives in [DeltaTransfer]; this class
 * only carries bytes.
 */
class DeltaClient(
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient,
) {

    /** Fetches the remote manifest, or null when the file is not there. */
    fun fetchManifest(remotePath: String): DeltaManifest? {
        val url = url("/api/manifest", mapOf("path" to remotePath, "t" to token))
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return DeltaManifest.parse(body)
        }
    }

    /** A [ChunkSource] that reads pieces straight out of the remote file. */
    fun chunkSource(remotePath: String): ChunkSource = object : ChunkSource {
        override suspend fun chunk(offset: Long, length: Int): ByteArray? {
            val url = url(
                "/api/chunk",
                mapOf(
                    "path" to remotePath,
                    "offset" to offset.toString(),
                    "length" to length.toString(),
                    "t" to token,
                ),
            )
            val request = Request.Builder().url(url).get().build()
            return try {
                client.newCall(request).execute().use { response: Response ->
                    if (!response.isSuccessful) return null
                    val bytes = response.body?.bytes() ?: return null
                    if (bytes.size != length) null else bytes
                }
            } catch (_: IOException) {
                null
            }
        }
    }

    /**
     * Brings [remotePath] down to [localFile], sending only the pieces that are
     * genuinely missing. Returns the plan that was carried out, or null if the
     * file could not be read on the other side.
     *
     * The output is built beside the file and renamed over it at the end, so
     * the local copy is only ever replaced by a complete one.
     */
    suspend fun pull(remotePath: String, localFile: File): DeltaPlan? {
        val remote = fetchManifest(remotePath) ?: return null
        val plan = DeltaTransfer.plan(localFile, remote)
        val applied = DeltaTransfer.apply(localFile, plan, chunkSource(remotePath), localFile)
        return if (applied) plan else null
    }

    private fun url(path: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "${baseUrl.trimEnd('/')}$path?$query"
    }
}
