package com.fileflow.app.core.delta

import com.fileflow.app.core.analysis.Chunker
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The delta transfer, over a real socket.
 *
 * The test stands up a server that speaks the same two endpoints the transfer
 * page serves — `/api/manifest` and `/api/chunk` — and pulls a file through it
 * with the real client. What matters is the count: the client must ask for the
 * missing pieces only, and the file that comes out must be identical to the one
 * on the server, byte for byte.
 */
class DeltaClientHttpTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var server: TinyServer? = null

    @After
    fun stopServer() {
        server?.stop()
    }

    private fun text(seed: Int, lines: Int): String {
        val random = java.util.Random(seed.toLong())
        return buildString {
            repeat(lines) {
                append(random.nextInt(1_000_000)).append(' ').append("the same words every time ")
                append(random.nextInt(1000)).append('\n')
            }
        }
    }

    @Test
    fun `a one line change pulls a few pieces instead of the whole file`() = runBlocking {
        val original = text(seed = 11, lines = 6000)
        val remoteFile = File(folder.root, "remote.bin").apply {
            writeText(original.replace(original.lines()[4000], "a different line, and only this one"))
        }
        val localFile = File(folder.root, "local.bin").apply { writeText(original) }

        val server = TinyServer(remoteFile, token = "secret").also { this@DeltaClientHttpTest.server = it }
        val plan = DeltaClient(server.baseUrl, token = "secret", client = OkHttpClient())
            .pull("/remote.bin", localFile)

        assertNotNull("the pull should have completed", plan)
        assertEquals(
            "the local file is now the remote revision",
            remoteFile.readText(),
            localFile.readText(),
        )
        assertEquals(
            "the server sent exactly the missing bytes, no more",
            plan!!.fetchBytes,
            server.servedBytes.get(),
        )
        assertTrue(
            "and far less than the file: ${server.servedBytes.get()} of ${remoteFile.length()}",
            server.servedBytes.get() < remoteFile.length() / 2,
        )
    }

    @Test
    fun `an unchanged file pulls nothing at all`() = runBlocking {
        val content = text(seed = 12, lines = 3000)
        val remoteFile = File(folder.root, "same.bin").apply { writeText(content) }
        val localFile = File(folder.root, "copy.bin").apply { writeText(content) }

        val server = TinyServer(remoteFile, token = "secret").also { this@DeltaClientHttpTest.server = it }
        val plan = DeltaClient(server.baseUrl, token = "secret", client = OkHttpClient())
            .pull("/same.bin", localFile)

        assertNotNull(plan)
        assertTrue("no piece should be needed", plan!!.nothingToDo)
        assertEquals(0L, server.servedBytes.get())
    }

    @Test
    fun `a wrong token gets nothing`() = runBlocking {
        val remoteFile = File(folder.root, "private.bin").apply { writeText(text(seed = 13, lines = 100)) }
        val server = TinyServer(remoteFile, token = "secret").also { this@DeltaClientHttpTest.server = it }
        val localFile = File(folder.root, "local.bin")

        val plan = DeltaClient(server.baseUrl, token = "guessed", client = OkHttpClient())
            .pull("/private.bin", localFile)

        assertEquals(null, plan)
        assertEquals(0L, localFile.length())
    }

    @Test
    fun `a file that is not text comes out identical, and one flipped byte stays one chunk`() = runBlocking {
        // Binary with long runs of identical bytes: the case a rolling hash has
        // to cut through with no word boundaries to help it.
        val random = java.util.Random(21)
        val bytes = ByteArray(700_000)
        var at = 0
        while (at < bytes.size) {
            val run = minOf(random.nextInt(9000) + 500, bytes.size - at)
            if (random.nextBoolean()) {
                java.util.Arrays.fill(bytes, at, at + run, 0x41.toByte())
            } else {
                for (index in at until at + run) bytes[index] = (random.nextInt(256) - 128).toByte()
            }
            at += run
        }
        val remoteFile = File(folder.root, "binary.bin").apply { writeBytes(bytes) }
        val localFile = File(folder.root, "binary-old.bin").apply {
            writeBytes(ByteArray(bytes.size) { index -> if (index == 1_000) 0x7F else bytes[index] })
        }

        val server = TinyServer(remoteFile, token = "t").also { this@DeltaClientHttpTest.server = it }
        val plan = DeltaClient(server.baseUrl, token = "t", client = OkHttpClient())
            .pull("/binary.bin", localFile)

        assertNotNull(plan)
        assertTrue("the bytes match", remoteFile.readBytes().contentEquals(localFile.readBytes()))
        assertTrue(
            "one flipped byte moved ${plan!!.fetchBytes} bytes",
            plan.fetchBytes < remoteFile.length() / 4,
        )
        assertEquals(
            "the chunker accounts for every byte",
            remoteFile.length(),
            Chunker.split(remoteFile).sumOf { it.length.toLong() },
        )
    }

    /**
     * The two endpoints, on a socket, without a framework.
     *
     * Android's unit-test classpath has no HTTP server, and adding one to test
     * two GETs would be a dependency for the sake of the test. This is the
     * smallest thing that speaks the protocol: read the request line, ignore
     * the headers, answer, close.
     */
    private class TinyServer(private val file: File, private val token: String) {

        val servedBytes = java.util.concurrent.atomic.AtomicLong()
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val worker = Thread { accept() }.apply { isDaemon = true; start() }

        val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

        fun stop() {
            runCatching { socket.close() }
        }

        private fun accept() {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: return
                runCatching { handle(client) }
            }
        }

        private fun handle(client: Socket) {
            client.use { connection ->
                val reader = connection.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                // Headers, ending at the first empty line.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val target = requestLine.split(' ').getOrNull(1) ?: return
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "")
                val output = connection.getOutputStream()

                if (query(query, "t") != token) {
                    answer(output, 401, ByteArray(0), "text/plain")
                    return
                }

                when (path) {
                    "/api/manifest" -> {
                        val manifest = runBlocking { DeltaTransfer.manifest(file) }
                        answer(output, 200, manifest.toJson().toByteArray(), "application/json")
                    }

                    "/api/chunk" -> {
                        val offset = query(query, "offset")?.toLongOrNull()
                        val length = query(query, "length")?.toIntOrNull()
                        if (offset == null || length == null) {
                            answer(output, 400, ByteArray(0), "text/plain")
                            return
                        }
                        val bytes = ByteArray(length)
                        RandomAccessFile(file, "r").use {
                            it.seek(offset)
                            it.readFully(bytes)
                        }
                        servedBytes.addAndGet(length.toLong())
                        answer(output, 200, bytes, "application/octet-stream")
                    }

                    else -> answer(output, 404, ByteArray(0), "text/plain")
                }
            }
        }

        private fun query(query: String, key: String): String? =
            query.split('&')
                .mapNotNull { part ->
                    val pair = part.split('=', limit = 2)
                    if (pair.size == 2 && pair[0] == key) URLDecoder.decode(pair[1], "UTF-8") else null
                }
                .firstOrNull()

        private fun answer(output: java.io.OutputStream, code: Int, body: ByteArray, type: String) {
            val header = "HTTP/1.1 $code FileFlow\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(body)
            output.flush()
        }
    }
}
