package com.fileflow.app.domain.remote

import android.util.Xml
import com.fileflow.app.core.fs.RemoteEntry
import com.fileflow.app.core.fs.RemoteException
import com.fileflow.app.core.fs.RemoteFileSystem
import com.fileflow.app.core.fs.remotePath
import java.io.FilterInputStream
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.xmlpull.v1.XmlPullParser

/**
 * WebDAV, written by hand.
 *
 * The protocol is four verbs and one XML response, which is less code than any
 * SDK that wraps it — and it means the app does not inherit a client library's
 * opinions about authentication or about which of the dozen DAV extensions it
 * supports. Nextcloud, ownCloud, Apache mod_dav, Synology and a dozen NAS boxes
 * all answer these four verbs.
 */
class WebDavFileSystem(
    override val connectionId: String,
    override val label: String,
    private val connection: RemoteConnection,
) : RemoteFileSystem {

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val base: HttpUrl = baseUrl()

    private fun baseUrl(): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme(if (connection.secure) "https" else "http")
            .host(connection.host)
        if (connection.portOr > 0) builder.port(connection.portOr)
        connection.path.trim('/').split('/').filter { it.isNotEmpty() }
            .forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    private fun url(path: String): HttpUrl = base.newBuilder().apply {
        path.trim('/').split('/').filter { it.isNotEmpty() }
            .forEach { addPathSegment(it) }
    }.build()

    /** The server path a listing has to strip to turn an href into a child name. */
    private fun basePath(): String = base.encodedPath.trimEnd('/')

    private fun Request.Builder.auth(): Request.Builder = apply {
        when {
            connection.user.isNotBlank() ->
                header("Authorization", Credentials.basic(connection.user, connection.secret))
            connection.secret.isNotBlank() ->
                header("Authorization", "Bearer ${connection.secret}")
        }
    }

    override fun home(): String = connection.path.trim('/').let { if (it.isEmpty()) "/" else "/$it" }

    override fun parent(path: String): String? {
        val trimmed = path.trim('/')
        if (trimmed.isEmpty()) return null
        val parent = trimmed.substringBeforeLast('/', "")
        return if (parent.isEmpty()) "/" else "/$parent"
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(path))
            .method("PROPFIND", ByteArray(0).toRequestBody(XML))
            .header("Depth", "1")
            .auth()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RemoteException(describe("List", response.code, response.message))
            parseListing(response.body?.string().orEmpty())
        }
    }

    /**
     * One `response` element per entry. Namespace prefixes are stripped rather
     * than matched: servers answer with `D:`, `d:`, or no prefix at all, and the
     * local name is the only part the standard fixes.
     */
    private fun parseListing(xml: String): List<RemoteEntry> {
        if (xml.isBlank()) return emptyList()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val base = basePath()
        val entries = mutableListOf<RemoteEntry>()

        var href: String? = null
        var isDirectory = false
        var size = 0L
        var modified = 0L
        var insideResponse = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfterLast(':')
            when (event) {
                XmlPullParser.START_TAG -> when (name) {
                    "response" -> {
                        insideResponse = true
                        href = null
                        isDirectory = false
                        size = 0L
                        modified = 0L
                    }
                    "href" -> if (insideResponse) href = parser.nextText().trim()
                    "getcontentlength" -> if (insideResponse) {
                        size = parser.nextText().trim().toLongOrNull() ?: 0L
                    }
                    "getlastmodified" -> if (insideResponse) {
                        modified = parseHttpDate(parser.nextText().trim())
                    }
                    "collection" -> if (insideResponse) isDirectory = true
                }

                XmlPullParser.END_TAG -> if (name == "response" && insideResponse) {
                    insideResponse = false
                    val raw = href
                    if (raw != null) {
                        val path = runCatching { URI(raw).path }.getOrNull() ?: raw
                        val relative = path.removePrefix(base).trim('/')
                        if (relative.isNotEmpty()) {
                            val childName = relative.substringAfterLast('/')
                            entries += RemoteEntry(
                                path = remotePath(home(), relative),
                                name = childName,
                                isDirectory = isDirectory,
                                sizeBytes = if (isDirectory) 0L else size,
                                lastModified = modified,
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        return entries.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun openInput(path: String): InputStream = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(path)).get().auth().build()
        val response = runCatching { client.newCall(request).execute() }.getOrElse {
            throw RemoteException("Download failed: ${it.message}", it)
        }
        if (!response.isSuccessful) {
            response.close()
            throw RemoteException(describe("Download", response.code, response.message))
        }
        val body = response.body
        if (body == null) {
            response.close()
            throw RemoteException("Empty response")
        }
        object : FilterInputStream(body.byteStream()) {
            override fun close() {
                runCatching { super.close() }
                response.close()
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
        val request = Request.Builder()
            .url(url(target))
            .put(StreamBody(source, sizeBytes, onProgress))
            .auth()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RemoteException(describe("Upload", response.code, response.message))
        }
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(path)).method("MKCOL", null).auth().build()
        client.newCall(request).execute().use { response ->
            // 405 means it is already there, which is what the caller wanted.
            if (!response.isSuccessful && response.code != 405) {
                throw RemoteException(describe("Create folder", response.code, response.message))
            }
        }
    }

    override suspend fun remove(path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(path)).delete().auth().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RemoteException(describe("Delete", response.code, response.message))
        }
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(from))
            .method("MOVE", null)
            .header("Destination", url(to).toString())
            .header("Overwrite", "T")
            .auth()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RemoteException(describe("Rename", response.code, response.message))
        }
    }

    /** Creates every missing ancestor, so an upload into a new folder works. */
    private suspend fun ensureDirectory(path: String) {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        var accumulated = ""
        for (segment in segments) {
            accumulated = "$accumulated/$segment"
            val request = Request.Builder()
                .url(url(accumulated))
                .method("MKCOL", null)
                .auth()
                .build()
            runCatching { client.newCall(request).execute().use { } }
        }
    }

    private fun describe(action: String, code: Int, message: String): String =
        if (code == 401 || code == 403) {
            "$action refused ($code): check the username and password"
        } else {
            "$action failed ($code $message)"
        }

    private fun parseHttpDate(value: String): Long = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    /**
     * A request body built from an already-open stream. WebDAV wants a
     * Content-Length, and the caller usually knows it — but a chunked upload is
     * legal too, so an unknown length is passed through rather than buffered.
     */
    private class StreamBody(
        private val source: InputStream,
        private val length: Long?,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaTypeOrNull()

        override fun contentLength(): Long = length ?: -1L

        override fun writeTo(sink: BufferedSink) {
            source.source().use { input ->
                val buffer = okio.Buffer()
                var total = 0L
                while (true) {
                    val read = input.read(buffer, CHUNK)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    total += read
                    onProgress(total)
                }
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val TRANSFER_TIMEOUT_SECONDS = 120L
        const val CHUNK = 64L * 1024

        val XML = "application/xml; charset=utf-8".toMediaTypeOrNull()
    }
}
