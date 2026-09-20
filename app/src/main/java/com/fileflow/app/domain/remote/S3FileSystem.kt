package com.fileflow.app.domain.remote

import android.util.Xml
import com.fileflow.app.core.fs.RemoteEntry
import com.fileflow.app.core.fs.RemoteException
import com.fileflow.app.core.fs.RemoteFileSystem
import com.fileflow.app.core.fs.remotePath
import java.io.FilterInputStream
import java.io.InputStream
import java.io.StringReader
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * S3-compatible object storage, signed by hand.
 *
 * The official SDK is several megabytes and a build plugin; SigV4 is forty lines
 * of HMAC chaining and the REST surface this needs is four calls. The same code
 * speaks to AWS, MinIO, Cloudflare R2, Backblaze B2 and the object stores that
 * NAS boxes ship, which is more than any SDK does without configuration.
 *
 * Two honest limits: a bucket has no folders, so folders are emulated with the
 * conventional trailing-slash markers and a prefix listing; and renaming or
 * deleting a "folder" means copying or deleting every object under its prefix,
 * which is what the UI warns about before it starts.
 */
class S3FileSystem(
    override val connectionId: String,
    override val label: String,
    private val connection: RemoteConnection,
) : RemoteFileSystem {

    private val endpointHost: String
    private val endpointPort: Int
    private val bucket = connection.path.trim('/')
    private val region = connection.extra.trim().ifBlank { "us-east-1" }
    private val secure = connection.secure

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    init {
        val raw = connection.host.trim().removePrefix("https://").removePrefix("http://")
        val parts = raw.split(':')
        endpointHost = parts.first()
        endpointPort = parts.getOrNull(1)?.toIntOrNull() ?: connection.portOr
    }

    override val readOnly: Boolean get() = connection.secret.isBlank()

    override fun home(): String = "/"

    override fun parent(path: String): String? {
        val trimmed = path.trim('/')
        if (trimmed.isEmpty()) return null
        val parent = trimmed.substringBeforeLast('/', "")
        return if (parent.isEmpty()) "/" else "/$parent"
    }

    /**
     * S3 has one flat namespace; a "folder" is a key that ends in a slash, and
     * everything under it is a prefix match.
     */
    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val prefix = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val response = execute(
            "GET",
            key = "",
            query = listOf(
                "list-type" to "2",
                "delimiter" to "/",
                "max-keys" to "1000",
                "prefix" to prefix,
            ),
        )
        val body = response
        parseListing(body, prefix)
    }

    private fun parseListing(xml: String, prefix: String): List<RemoteEntry> {
        if (xml.isBlank()) return emptyList()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val entries = mutableListOf<RemoteEntry>()

        var key: String? = null
        var size = 0L
        var modified = 0L
        var inside = false
        var folderPrefix: String? = null
        var inCommonPrefixes = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfterLast(':')
            when (event) {
                XmlPullParser.START_TAG -> when (name) {
                    "commonprefixes" -> inCommonPrefixes = true
                    "contents" -> {
                        inside = true
                        key = null
                        size = 0L
                        modified = 0L
                    }
                    "key" -> if (inside) key = parser.nextText()
                    "size" -> if (inside) size = parser.nextText().trim().toLongOrNull() ?: 0L
                    "lastmodified" -> if (inside) modified = parseIso(parser.nextText().trim())
                    "prefix" -> if (inCommonPrefixes) folderPrefix = parser.nextText()
                }

                XmlPullParser.END_TAG -> when (name) {
                    "commonprefixes" -> {
                        inCommonPrefixes = false
                        val value = folderPrefix
                        if (!value.isNullOrBlank()) {
                            val trimmed = value.trimEnd('/')
                            entries += RemoteEntry(
                                path = "/$trimmed",
                                name = trimmed.substringAfterLast('/'),
                                isDirectory = true,
                                sizeBytes = 0L,
                                lastModified = 0L,
                            )
                        }
                        folderPrefix = null
                    }

                    "contents" -> {
                        inside = false
                        val value = key
                        // The folder marker itself is not a child of the folder.
                        if (!value.isNullOrBlank() && value != prefix) {
                            val trimmed = value.trimEnd('/')
                            entries += RemoteEntry(
                                path = "/$trimmed",
                                name = trimmed.substringAfterLast('/'),
                                isDirectory = value.endsWith('/'),
                                sizeBytes = size,
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
        val key = path.trim('/')
        val request = signed("GET", key, emptyList(), null, EMPTY_SHA256)
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
        val key = remotePath(path, name).trim('/')
        // UNSIGNED-PAYLOAD is what lets a large upload stream without hashing it
        // twice; S3 accepts it over HTTPS.
        val request = signed(
            method = "PUT",
            key = key,
            query = emptyList(),
            body = StreamBody(source, onProgress),
            contentHash = UNSIGNED_PAYLOAD,
            length = sizeBytes,
        )
        runCatching { client.newCall(request).execute().use { } }.getOrElse {
            throw RemoteException("Upload failed: ${it.message}", it)
        }
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        // A zero-byte object whose key ends in a slash is how every S3 tool
        // makes a folder the console will show.
        val key = path.trim('/') + "/"
        val request = signed("PUT", key, emptyList(), ByteArray(0).toRequestBody(OCTET), EMPTY_SHA256, length = 0L)
        runCatching { client.newCall(request).execute().use { } }.getOrElse {
            throw RemoteException("Create folder failed: ${it.message}", it)
        }
    }

    override suspend fun remove(path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        if (!isDirectory) {
            deleteKey(path.trim('/'))
            return@withContext
        }
        // Every object under the prefix, then the marker itself.
        var token: String? = null
        do {
            val prefix = path.trim('/') + "/"
            val query = buildList {
                add("list-type" to "2")
                add("prefix" to prefix)
                token?.let { add("continuation-token" to it) }
            }
            val xml = execute("GET", key = "", query = query)
            val keys = keysOf(xml)
            keys.forEach { deleteKey(it) }
            token = nextToken(xml)
        } while (token != null)
        runCatching { deleteKey(path.trim('/') + "/") }
        Unit
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        // Objects are immutable, so a rename is a copy followed by a delete.
        // A "folder" has no object to copy unless its marker exists, and saying
        // so is better than a rename that quietly does nothing.
        val source = from.trim('/')
        val target = to.trim('/')
        val request = signed(
            method = "PUT",
            key = target,
            query = emptyList(),
            body = null,
            contentHash = EMPTY_SHA256,
            extraHeaders = mapOf("x-amz-copy-source" to "/$bucket/$source"),
        )
        val response = runCatching { client.newCall(request).execute() }.getOrElse {
            throw RemoteException("Rename failed: ${it.message}", it)
        }
        response.use { result ->
            if (!result.isSuccessful) {
                throw RemoteException(
                    "Rename failed (${result.code}). S3 objects cannot be renamed in place; " +
                        "a folder has to be copied object by object."
                )
            }
        }
        deleteKey(source)
    }

    private fun deleteKey(key: String) {
        val request = signed("DELETE", key, emptyList(), null, EMPTY_SHA256)
        runCatching { client.newCall(request).execute().use { } }.getOrElse {
            throw RemoteException("Delete failed: ${it.message}", it)
        }
    }

    private fun keysOf(xml: String): List<String> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val keys = mutableListOf<String>()
        var event = parser.eventType
        var inContents = false
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfterLast(':')
            when (event) {
                XmlPullParser.START_TAG -> when (name) {
                    "contents" -> inContents = true
                    "key" -> if (inContents) keys += parser.nextText()
                }
                XmlPullParser.END_TAG -> if (name == "contents") inContents = false
            }
            event = parser.next()
        }
        return keys
    }

    private fun nextToken(xml: String): String? {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var next: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name?.substringAfterLast(':') == "nextcontinuationtoken") {
                next = parser.nextText().takeIf { it.isNotBlank() }
            }
            event = parser.next()
        }
        return next
    }

    /** Runs a signed request and returns the body, or throws with the reason. */
    private fun execute(
        method: String,
        key: String,
        query: List<Pair<String, String>>,
    ): String {
        val request = signed(method, key, query, null, EMPTY_SHA256)
        return runCatching { client.newCall(request).execute() }.getOrElse {
            throw RemoteException("Request failed: ${it.message}", it)
        }.use { response ->
            if (!response.isSuccessful) {
                throw RemoteException(describe("Request", response.code, response.message))
            }
            response.body?.string().orEmpty()
        }
    }

    private fun describe(action: String, code: Int, message: String): String =
        when (code) {
            403 -> "$action refused (403): check the access key, the secret and the bucket policy"
            404 -> "$action failed (404): no such bucket or key"
            else -> "$action failed ($code $message)"
        }

    private fun parseIso(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    // ------------------------------------------------------------ signing

    private fun signed(
        method: String,
        key: String,
        query: List<Pair<String, String>>,
        body: RequestBody?,
        contentHash: String,
        length: Long? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Request {
        val now = Instant.now()
        val amzDate = AMZ_DATE.format(now)
        val dateStamp = DATE_STAMP.format(now)

        val encodedPath = encodePath(key)
        val canonicalQuery = query
            .sortedBy { it.first }
            .joinToString("&") { "${uriEncode(it.first)}=${uriEncode(it.second)}" }
        val hostHeader = if (endpointPort > 0) "$endpointHost:$endpointPort" else endpointHost
        val canonicalHeaders = buildString {
            append("host:$hostHeader\n")
            append("x-amz-content-sha256:$contentHash\n")
            append("x-amz-date:$amzDate\n")
        }
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = buildString {
            append(method).append('\n')
            append(encodedPath).append('\n')
            append(canonicalQuery).append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(contentHash)
        }

        val scope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = buildString {
            append("AWS4-HMAC-SHA256").append('\n')
            append(amzDate).append('\n')
            append(scope).append('\n')
            append(sha256Hex(canonicalRequest))
        }
        val signature = hex(hmac(signingKey(dateStamp), stringToSign))
        val authorization = "AWS4-HMAC-SHA256 Credential=${connection.user}/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        val url: HttpUrl = HttpUrl.Builder()
            .scheme(if (secure) "https" else "http")
            .host(endpointHost)
            .apply { if (endpointPort > 0) port(endpointPort) }
            .encodedPath(encodedPath)
            .apply { if (canonicalQuery.isNotEmpty()) encodedQuery(canonicalQuery) }
            .build()

        val builder = Request.Builder()
            .url(url)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", contentHash)
            .header("Authorization", authorization)
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }

        return when (method) {
            "GET" -> builder.get().build()
            "DELETE" -> builder.delete().build()
            "PUT" -> builder.put(body ?: ByteArray(0).toRequestBody(OCTET)).build()
            else -> builder.method(method, body).build()
        }
    }

    /** `/bucket/key`, each segment RFC3986-encoded, slashes preserved. */
    private fun encodePath(key: String): String {
        val segments = buildList {
            add(bucket)
            key.trim('/').split('/').filter { it.isNotEmpty() }.forEach { add(it) }
        }
        val encoded = segments.joinToString("/") { uriEncode(it) }
        val trailing = if (key.endsWith("/") && key.trim('/').isNotEmpty()) "/" else ""
        return "/$encoded$trailing"
    }

    private fun signingKey(dateStamp: String): ByteArray {
        val kDate = hmac(("AWS4" + connection.secret).toByteArray(), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, "s3")
        return hmac(kService, "aws4_request")
    }

    private fun sha256Hex(value: String): String = hex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    )

    private fun hmac(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte -> append("%02x".format(byte)) }
    }

    private fun uriEncode(value: String): String = buildString(value.length) {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val char = byte.toInt().toChar()
            if (char.isLetterOrDigit() && char.code < 128 || char in "-_.~") {
                append(char)
            } else {
                append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }

    private class StreamBody(
        private val source: InputStream,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaTypeOrNull()

        override fun contentLength() = -1L

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
        const val TRANSFER_TIMEOUT_SECONDS = 180L
        const val CHUNK = 64L * 1024
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        val OCTET = "application/octet-stream".toMediaTypeOrNull()
        val AMZ_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val DATE_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    }
}
