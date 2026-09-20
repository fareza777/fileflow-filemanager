package com.fileflow.app.domain.transfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.core.storage.StorageAccess
import com.fileflow.app.core.storage.VolumeRepository
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.repo.FileActionsRepository
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Semaphore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How a network address is likely to be reachable from the other device. */
enum class TransferLink { WIFI, HOTSPOT, USB, OTHER }

data class TransferAddress(
    val interfaceName: String,
    val host: String,
    val link: TransferLink,
)

/** One line of the live request log, so the screen can show the work happening. */
data class TransferEvent(
    val at: Long,
    val client: String,
    val summary: String,
    val bytes: Long,
    val failed: Boolean,
)

@androidx.compose.runtime.Immutable
data class TransferServerState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val port: Int = 8080,
    val root: String = "",
    val error: String? = null,
    val requests: Int = 0,
    val bytesOut: Long = 0,
    val bytesIn: Long = 0,
    val active: Int = 0,
    val events: List<TransferEvent> = emptyList(),
)

/**
 * The phone as a file server, for a browser on the other side of the room.
 *
 * A file manager already holds the permission and the paths; what it cannot do
 * is hand a file to a laptop without a cable, an account or a cloud. This is
 * that missing half: an HTTP server on the local network, with a plain page
 * that browses, previews, downloads, uploads, moves and deletes — no app on the
 * PC, no plugins, and nothing leaving the network it is already on.
 *
 * Every change the page makes goes through [FileActionsRepository], the same
 * write path the app itself uses: a file deleted from the browser lands in
 * FileFlow's trash with a journal entry, and a file moved is re-indexed for
 * search. The browser is a second window onto the app, not a second app.
 */
@Singleton
class HttpFileServer @Inject constructor(
    private val volumes: VolumeRepository,
    private val storageAccess: StorageAccess,
    private val actions: FileActionsRepository,
) {

    private val _state = MutableStateFlow(TransferServerState())
    val state: StateFlow<TransferServerState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = Semaphore(MAX_CONNECTIONS)

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    /**
     * The page is behind a token, and the token is in the address the app shows.
     *
     * A file server on a shared network is open to everyone on it, and this one
     * can delete and move files — a café Wi-Fi should not be able to do either.
     * The token is generated per run, sent as a cookie by the page so downloads
     * and thumbnails keep working, and never written to disk.
     */
    private var token: String = ""

    /** Whether a server can be started at all; the service asks before it does. */
    val isRunning: Boolean get() = serverSocket != null

    /** The address to open, token included. */
    fun pageUrl(host: String): String = "http://$host:${_state.value.port}/?t=$token"

    /** Every address another device could try, the likely one first. */
    fun addresses(): List<TransferAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    .map { address ->
                        TransferAddress(
                            interfaceName = nif.name,
                            host = address.hostAddress.orEmpty(),
                            link = linkOf(nif.name),
                        )
                    }
            }
            .filter { it.host.isNotBlank() }
            .sortedBy { it.link.ordinal }
    }.getOrDefault(emptyList())

    fun start(root: String, port: Int = DEFAULT_PORT): Boolean {
        stop()
        val chosenPort = if (port in 1024..65535) port else DEFAULT_PORT
        return runCatching {
            val socket = ServerSocket(chosenPort).apply { reuseAddress = true }
            serverSocket = socket
            token = randomToken()
            _state.update {
                it.copy(
                    running = true,
                    starting = false,
                    port = socket.localPort,
                    root = root,
                    error = null,
                )
            }
            acceptJob = scope.launch { acceptLoop(socket) }
            true
        }.getOrElse { error ->
            _state.update {
                it.copy(
                    running = false,
                    starting = false,
                    error = error.message ?: "Could not open the port",
                )
            }
            false
        }
    }

    private fun randomToken(): String =
        (1..TOKEN_LENGTH).map { TOKEN_ALPHABET.random() }.joinToString("")

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _state.update { it.copy(running = false, active = 0) }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive && !socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            scope.launch { serve(client) }
        }
    }

    private suspend fun serve(client: Socket) {
        if (!connections.tryAcquire()) {
            runCatching { client.close() }
            return
        }
        _state.update { it.copy(active = it.active + 1, requests = it.requests + 1) }
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream(), BUFFER)
            val output = BufferedOutputStream(client.getOutputStream(), BUFFER)
            handle(client, input, output)
            output.flush()
        } catch (error: Throwable) {
            // A browser that closes the tab mid-download is normal, not a bug.
            android.util.Log.d(TAG, "request ended: ${error.message}")
        } finally {
            _state.update { it.copy(active = (it.active - 1).coerceAtLeast(0)) }
            connections.release()
            runCatching { client.close() }
        }
    }

    private suspend fun handle(client: Socket, input: InputStream, output: OutputStream) {
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = readHeaders(input)

        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))

        if (!authorised(query, headers)) {
            writeText(
                output,
                401,
                "Open the link shown in FileFlow on this network.",
            )
            logEvent(
                client = client.inetAddress?.hostAddress.orEmpty(),
                summary = "refused (no token)",
                failed = true,
            )
            return
        }

        var bytes = 0L
        var failed = false
        val summary = when {
            method == "GET" && (path == "/" || path.isEmpty()) -> {
                listDirectory(query, output)
                "browsing ${query["path"]?.let(::nameOf)?.ifEmpty { "storage" } ?: "storage"}"
            }
            method == "GET" && path == "/api/download" -> {
                bytes = download(query, headers["range"], output)
                "sent ${nameOf(query["path"])}"
            }
            method == "GET" && path == "/api/thumb" -> {
                bytes = thumbnail(query, output)
                if (bytes == 0L) "" else "preview ${nameOf(query["path"])}"
            }
            method == "GET" && path == "/api/folders" -> {
                listFolders(query, output)
                "folders in ${nameOf(query["path"])}"
            }
            method == "PUT" && path == "/api/upload" -> {
                bytes = upload(query, headers, input, output)
                "received ${query["name"].orEmpty()}"
            }
            method == "POST" && path == "/api/delete" -> {
                val outcome = mutate(input, output) { paths -> deleteFiles(paths) }
                failed = outcome.failed > 0
                bytes = 0
                "deleted ${outcome.done}" + if (outcome.failed > 0) " (${outcome.failed} failed)" else ""
            }
            method == "POST" && path == "/api/move" -> {
                val destination = query["dest"]?.let { resolve(it) }
                if (destination == null || !destination.isDirectory) {
                    writeText(output, 400, "Bad destination")
                    "move refused"
                } else {
                    val outcome = mutate(input, output) { paths ->
                        moveFiles(paths, destination.absolutePath)
                    }
                    failed = outcome.failed > 0
                    bytes = 0
                    "moved ${outcome.done} to ${destination.name}" +
                        if (outcome.failed > 0) " (${outcome.failed} failed)" else ""
                }
            }
            method == "GET" && path == "/favicon.ico" -> {
                writeText(output, 204, "")
                "icon"
            }
            else -> {
                writeText(output, 404, "Not found")
                failed = true
                "unknown request"
            }
        }

        if (summary.isNotEmpty()) {
            logEvent(
                client = client.inetAddress?.hostAddress.orEmpty(),
                summary = summary,
                bytes = bytes,
                failed = failed,
            )
        }
    }

    // ------------------------------------------------------------ browsing

    private fun listDirectory(query: Map<String, String>, output: OutputStream) {
        val current = resolve(query["path"] ?: _state.value.root)
        if (current == null || !current.isDirectory) {
            writeText(output, 403, "That folder is not shared")
            return
        }

        val entries = runCatching { current.listFiles()?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { !storageAccess.isRestrictedPath(it.absolutePath) }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })

        val html = buildString {
            append(pageHead(current))
            append("<main><section class=\"card\">")
            append("<form id=\"drop\" class=\"drop\">")
            append("<input id=\"pick\" type=\"file\" multiple hidden>")
            append("<p><strong>Send files to this folder</strong></p>")
            append("<p class=\"hint\">Tap to choose, or drop files here from the computer.</p>")
            append("<button type=\"button\" id=\"choose\">Choose files</button>")
            append("<div class=\"bar\"><div id=\"fill\"></div></div>")
            append("<p id=\"status\" class=\"hint\"></p>")
            append("</form></section>")

            append("<section class=\"card\"><div class=\"head\">")
            append("<label class=\"check\"><input type=\"checkbox\" id=\"all\"><span>Select all</span></label>")
            append("<span class=\"spacer\"></span>")
            append("<span class=\"hint\" id=\"count\"></span>")
            append("</div><table>")
            if (entries.isEmpty()) {
                append("<tr><td class=\"hint\">This folder is empty.</td></tr>")
            }
            entries.forEach { entry ->
                val encoded = urlEncode(entry.absolutePath)
                val size = if (entry.isDirectory) "" else Formatters.bytes(entry.length())
                val date = DATE_FORMAT.format(Date(entry.lastModified()))
                val pick = "<input type=\"checkbox\" class=\"pick\" value=\"$encoded\">"
                if (entry.isDirectory) {
                    append(
                        "<tr><td class=\"sel\">$pick</td>" +
                            "<td class=\"thumb\"><span class=\"ico\">&#128193;</span></td>" +
                            "<td class=\"name\"><a href=\"/?path=$encoded\">${escape(entry.name)}</a></td>" +
                            "<td class=\"meta\">$date</td><td class=\"meta\"></td></tr>"
                    )
                } else {
                    val preview = if (isPreviewable(entry.name)) {
                        "<img loading=\"lazy\" src=\"/api/thumb?path=$encoded\" alt=\"\">"
                    } else {
                        "<span class=\"ico\">${glyphFor(entry.name)}</span>"
                    }
                    append(
                        "<tr><td class=\"sel\">$pick</td>" +
                            "<td class=\"thumb\">$preview</td>" +
                            "<td class=\"name\"><a href=\"/api/download?path=$encoded\">${escape(entry.name)}</a></td>" +
                            "<td class=\"meta\">$date</td><td class=\"meta\">$size</td>" +
                            "<td class=\"act\"><a class=\"get\" href=\"/api/download?path=$encoded\">Download</a></td></tr>"
                    )
                }
            }
            append("</table></section></main>")

            // The action bar behaves like the one in the app: it only exists
            // while something is selected.
            append("<div id=\"bar\" class=\"actionbar\">")
            append("<span id=\"barCount\" class=\"count\"></span>")
            append("<button type=\"button\" id=\"move\">Move to…</button>")
            append("<button type=\"button\" id=\"remove\" class=\"danger\">Delete</button>")
            append("</div>")

            append("<div id=\"modal\" class=\"modal\"><div class=\"sheet\">")
            append("<h2>Move to folder</h2>")
            append("<p class=\"hint\" id=\"dest\"></p>")
            append("<div id=\"folders\" class=\"folders\"></div>")
            append("<div class=\"sheetActions\">")
            append("<button type=\"button\" id=\"cancel\">Cancel</button>")
            append("<button type=\"button\" id=\"confirm\">Move here</button>")
            append("</div></div></div>")

            append(pageScript(current.absolutePath))
            append("</body></html>")
        }
        writeHtml(output, 200, html)
    }

    private fun pageHead(current: File): String {
        val root = File(_state.value.root)
        val trail = buildString {
            append("<a href=\"/?path=${urlEncode(root.absolutePath)}\">${escape(root.name.ifEmpty { "Storage" })}</a>")
            val relative = current.absolutePath.removePrefix(root.absolutePath).trim('/')
            if (relative.isNotEmpty()) {
                var accumulated = root.absolutePath
                relative.split('/').forEach { segment ->
                    accumulated = "$accumulated/$segment"
                    append("<span class=\"sep\">/</span>")
                    append("<a href=\"/?path=${urlEncode(accumulated)}\">${escape(segment)}</a>")
                }
            }
        }
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>FileFlow · ${escape(current.name)}</title>
            <style>$STYLE</style></head><body>
            <header><div class="brand">FileFlow</div>
            <div class="device">${escape(deviceName)}</div>
            <nav class="trail">$trail</nav></header>
        """.trimIndent()
    }

    private fun pageScript(directory: String): String = """
        <script>
        const dir = "${jsEscape(directory)}";
        const pick = document.getElementById('pick');
        const fill = document.getElementById('fill');
        const status = document.getElementById('status');
        const boxes = () => [...document.querySelectorAll('.pick')];
        const selected = () => boxes().filter(b => b.checked).map(b => b.value);
        const bar = document.getElementById('bar');
        const barCount = document.getElementById('barCount');
        const all = document.getElementById('all');

        document.getElementById('choose').onclick = () => pick.click();
        pick.onchange = () => send([...pick.files]);
        const drop = document.getElementById('drop');
        ['dragenter','dragover'].forEach(e => drop.addEventListener(e, ev => {
          ev.preventDefault(); drop.classList.add('over');
        }));
        ['dragleave','drop'].forEach(e => drop.addEventListener(e, ev => {
          ev.preventDefault(); drop.classList.remove('over');
        }));
        drop.addEventListener('drop', ev => send([...ev.dataTransfer.files]));

        async function send(files) {
          if (!files.length) return;
          for (let i = 0; i < files.length; i++) {
            const file = files[i];
            status.textContent = 'Sending ' + (i + 1) + ' of ' + files.length + ': ' + file.name;
            await new Promise((resolve, reject) => {
              const xhr = new XMLHttpRequest();
              const url = '/api/upload?dir=' + encodeURIComponent(dir) +
                          '&name=' + encodeURIComponent(file.name);
              xhr.open('PUT', url);
              xhr.upload.onprogress = e => {
                const overall = (i + (e.loaded / Math.max(e.total, 1))) / files.length;
                fill.style.width = (overall * 100).toFixed(1) + '%';
              };
              xhr.onload = () => xhr.status < 300 ? resolve() : reject(new Error(xhr.responseText));
              xhr.onerror = () => reject(new Error('network error'));
              xhr.send(file);
            }).catch(err => { status.textContent = 'Failed: ' + err.message; });
          }
          fill.style.width = '100%';
          status.textContent = 'Done — reloading';
          location.reload();
        }

        function refresh() {
          const count = selected().length;
          bar.classList.toggle('show', count > 0);
          barCount.textContent = count === 1 ? '1 selected' : count + ' selected';
          if (all) all.checked = count > 0 && count === boxes().length;
        }
        boxes().forEach(b => b.addEventListener('change', refresh));
        if (all) all.addEventListener('change', () => {
          boxes().forEach(b => b.checked = all.checked);
          refresh();
        });
        refresh();

        async function post(url, paths) {
          const body = paths.map(p => encodeURIComponent(p)).join('\n');
          const res = await fetch(url, { method: 'POST', body: body });
          const text = await res.text();
          if (!res.ok) throw new Error(text);
          return text;
        }

        document.getElementById('remove').onclick = async () => {
          const paths = selected();
          if (!paths.length) return;
          if (!confirm('Move ' + paths.length + ' item(s) to FileFlow Trash?')) return;
          barCount.textContent = 'Deleting…';
          try {
            await post('/api/delete', paths);
            location.reload();
          } catch (err) {
            alert('Delete failed: ' + err.message);
            refresh();
          }
        };

        // ------------------------------------------------ move dialog
        const modal = document.getElementById('modal');
        const folders = document.getElementById('folders');
        const destLabel = document.getElementById('dest');
        let dest = dir;

        async function openModal() {
          modal.classList.add('show');
          await browse(dir);
        }
        async function browse(path) {
          dest = path;
          destLabel.textContent = path;
          folders.innerHTML = '<p class="hint">Loading…</p>';
          try {
            const res = await fetch('/api/folders?path=' + encodeURIComponent(path));
            const data = await res.json();
            let html = '';
            if (data.parent) html += '<button type="button" class="folder" data-path="' +
              encodeURIComponent(data.parent) + '">&#8593; ..</button>';
            html += data.folders.map(f =>
              '<button type="button" class="folder" data-path="' +
              encodeURIComponent(f.path) + '">&#128193; ' + f.name + '</button>').join('');
            folders.innerHTML = html || '<p class="hint">No subfolders here.</p>';
            folders.querySelectorAll('.folder').forEach(button => {
              button.onclick = () => browse(decodeURIComponent(button.dataset.path));
            });
          } catch (err) {
            folders.innerHTML = '<p class="hint">Could not list folders.</p>';
          }
        }
        document.getElementById('move').onclick = openModal;
        document.getElementById('cancel').onclick = () => modal.classList.remove('show');
        document.getElementById('confirm').onclick = async () => {
          const paths = selected();
          if (!paths.length) return;
          barCount.textContent = 'Moving…';
          modal.classList.remove('show');
          try {
            await post('/api/move?dest=' + encodeURIComponent(dest), paths);
            location.reload();
          } catch (err) {
            alert('Move failed: ' + err.message);
            refresh();
          }
        };
        </script>
    """.trimIndent()

    // ------------------------------------------------------------ transfer

    /** @return the number of bytes actually sent. */
    private fun download(query: Map<String, String>, rangeHeader: String?, output: OutputStream): Long {
        val file = resolve(query["path"])?.takeIf { it.isFile }
        if (file == null) {
            writeText(output, 404, "No such file")
            return 0
        }
        val length = file.length()
        val range = parseRange(rangeHeader, length)
        val start = range?.first ?: 0L
        val end = range?.second ?: (length - 1)
        val span = (end - start + 1).coerceAtLeast(0L)

        val headers = buildString {
            append("HTTP/1.1 ${if (range != null) "206 Partial Content" else "200 OK"}\r\n")
            append("Content-Type: ${contentTypeOf(file.name)}\r\n")
            append("Content-Length: $span\r\n")
            // Without ranges a browser cannot seek inside a video it is
            // streaming, and a dropped download has to start over.
            append("Accept-Ranges: bytes\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/$length\r\n")
            append("Content-Disposition: attachment; filename=\"${file.name.replace("\"", "")}\"\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.ISO_8859_1))

        var sent = 0L
        FileInputStream(file).use { stream ->
            stream.skip(start)
            val buffer = ByteArray(BUFFER)
            var remaining = span
            while (remaining > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
                sent += read
            }
        }
        output.flush()
        _state.update { it.copy(bytesOut = it.bytesOut + sent) }
        return sent
    }

    /**
     * A small JPEG for the listing. Photos are the one thing a file list cannot
     * describe in words, and the browser cache means a directory gets decoded
     * once rather than on every reload.
     */
    private fun thumbnail(query: Map<String, String>, output: OutputStream): Long {
        val file = resolve(query["path"])?.takeIf { it.isFile }
        if (file == null || !isImage(file.name)) {
            // A browser asking for a preview of something that has none gets an
            // answer rather than a dropped connection.
            writeText(output, 404, "No preview")
            return 0
        }
        val bytes = runCatching { decodeThumbnail(file) }.getOrNull()
        if (bytes == null) {
            writeText(output, 404, "No preview")
            return 0
        }

        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: private, max-age=120\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
        return bytes.size.toLong()
    }

    private fun decodeThumbnail(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= THUMB_EDGE) sample *= 2

        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val scaled = if (maxOf(bitmap.width, bitmap.height) > THUMB_EDGE) {
            val scale = THUMB_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it != bitmap) bitmap.recycle() }
        } else {
            bitmap
        }
        return ByteArrayOutputStream().use { stream ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 82, stream)) {
                scaled.recycle()
                return null
            }
            scaled.recycle()
            stream.toByteArray()
        }
    }

    /** @return the number of bytes actually stored. */
    private fun upload(
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
    ): Long {
        val directory = resolve(query["dir"])
        val name = query["name"].orEmpty().substringAfterLast('/').substringAfterLast('\\')
        if (directory == null || !directory.isDirectory || name.isBlank() || name == "." || name == "..") {
            writeText(output, 400, "Bad destination")
            return 0
        }
        val length = headers["content-length"]?.toLongOrNull()
        if (length == null || length < 0) {
            writeText(output, 411, "Length required")
            return 0
        }

        val target = uniqueTarget(File(directory, name))
        val partial = File(directory, "${target.name}.fileflow-part")
        var received = 0L
        try {
            FileOutputStream(partial).use { stream ->
                val buffer = ByteArray(BUFFER)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    stream.write(buffer, 0, read)
                    remaining -= read
                    received += read
                }
            }
            if (received < length) {
                partial.delete()
                writeText(output, 400, "Incomplete upload")
                return received
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
        } catch (error: Throwable) {
            partial.delete()
            writeText(output, 500, "Upload failed")
            return received
        }

        _state.update { it.copy(bytesIn = it.bytesIn + received) }
        writeText(output, 201, "Saved ${target.name}")
        return received
    }

    private suspend fun deleteFiles(paths: List<String>): Outcome {
        val targets = paths.mapNotNull { resolve(it)?.absolutePath }
        if (targets.isEmpty()) return Outcome(0, 0)
        val report = runCatching { actions.delete(targets, permanent = false) }.getOrNull()
            ?: return Outcome(0, targets.size)
        return Outcome(report.succeeded, report.failed)
    }

    private suspend fun moveFiles(paths: List<String>, destination: String): Outcome {
        val targets = paths.mapNotNull { resolve(it)?.absolutePath }
            .filter { it.substringBeforeLast('/') != destination }
        if (targets.isEmpty()) return Outcome(0, 0)
        val report = runCatching {
            actions.move(targets, destination, ConflictPolicy.RENAME)
        }.getOrNull() ?: return Outcome(0, targets.size)
        return Outcome(report.succeeded, report.failed)
    }

    private data class Outcome(val done: Int, val failed: Int)

    /**
     * Reads a newline-separated, URL-encoded list of paths, runs [block], and
     * answers with a one-line summary the page shows as-is.
     */
    private suspend fun mutate(
        input: InputStream,
        output: OutputStream,
        block: suspend (List<String>) -> Outcome,
    ): Outcome {
        val body = readBody(input, MAX_ACTION_BYTES)
        val paths = body.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { decode(it) }
        if (paths.isEmpty()) {
            writeText(output, 400, "Nothing selected")
            return Outcome(0, 0)
        }
        val outcome = block(paths)
        val text = if (outcome.failed == 0) {
            "${outcome.done} done"
        } else {
            "${outcome.done} done, ${outcome.failed} failed"
        }
        writeText(output, 200, text)
        return outcome
    }

    private fun listFolders(query: Map<String, String>, output: OutputStream) {
        val root = File(_state.value.root).canonicalFile
        val current = resolve(query["path"] ?: _state.value.root)
        if (current == null || !current.isDirectory) {
            writeText(output, 403, "That folder is not shared")
            return
        }
        val folders = runCatching { current.listFiles()?.filter { it.isDirectory } }
            .getOrNull()
            .orEmpty()
            .filter { !storageAccess.isRestrictedPath(it.absolutePath) }
            .sortedBy { it.name.lowercase() }
            .joinToString(",") { folder ->
                """{"name":"${jsonEscape(folder.name)}","path":"${jsonEscape(folder.absolutePath)}"}"""
            }
        val parent = current.parentFile
            ?.takeIf { it.canonicalFile.path.startsWith(root.path) }
            ?.absolutePath
        writeJson(
            output,
            """{"path":"${jsonEscape(current.absolutePath)}",""" +
                """"parent":${parent?.let { "\"${jsonEscape(it)}\"" } ?: "null"},""" +
                """"folders":[$folders]}""",
        )
    }

    /**
     * An upload never overwrites: the file it would land on is one the user
     * already had, and the browser cannot be asked which one they meant.
     */
    private fun uniqueTarget(target: File): File {
        if (!target.exists()) return target
        val base = target.name.substringBeforeLast('.', target.name)
        val extension = target.name.substringAfterLast('.', "")
        var index = 2
        while (index < 10_000) {
            val candidate = File(
                target.parentFile,
                if (extension.isEmpty()) "$base ($index)" else "$base ($index).$extension",
            )
            if (!candidate.exists()) return candidate
            index++
        }
        return target
    }

    // ------------------------------------------------------------ plumbing

    /**
     * A request path is resolved against the shared root and refused when it
     * escapes it — the same rule the zip extractor applies, for the same reason.
     */
    private fun resolve(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val root = File(_state.value.root).canonicalFile
        val candidate = runCatching {
            (if (path.startsWith("/")) File(path) else File(root, path)).canonicalFile
        }.getOrNull() ?: return null
        val inside = candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)
        if (!inside) return null
        if (storageAccess.isRestrictedPath(candidate.absolutePath)) return null
        return candidate
    }

    /**
     * A `Range: bytes=…` header, the one feature that makes a browser behave
     * like a file manager rather than a download button: seekable video,
     * resumable downloads, and no re-sending a gigabyte that already arrived.
     */
    private fun parseRange(header: String?, length: Long): Pair<Long, Long>? {
        val value = header?.trim()?.removePrefix("bytes=") ?: return null
        val start = value.substringBefore('-').trim().toLongOrNull() ?: return null
        if (start >= length || start < 0) return null
        val end = value.substringAfter('-', "").trim().toLongOrNull() ?: (length - 1)
        return start to end.coerceIn(start, length - 1)
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            total += line.length
            if (total > MAX_HEADER_BYTES) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim().lowercase(Locale.US)
            headers[key] = line.substring(separator + 1).trim()
        }
        return headers
    }

    private fun readBody(input: InputStream, limit: Int): String {
        val buffer = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val count = input.read(buffer, read, limit - read)
            if (count <= 0) break
            read += count
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder(96)
        while (true) {
            val byte = input.read()
            if (byte < 0) return buffer.toString().takeIf { it.isNotEmpty() }
            if (byte == '\n'.code) return buffer.toString().trimEnd('\r')
            if (buffer.length > MAX_LINE_CHARS) return null
            buffer.append(byte.toChar())
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
        }.toMap()
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun writeText(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ${if (code < 300) "OK" else "Error"}\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    private fun writeJson(output: OutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    /**
     * The token, from the address on the first visit and from a cookie after
     * that — a download is its own navigation, and re-typing a token for every
     * photo would make the whole thing unusable.
     */
    private fun authorised(query: Map<String, String>, headers: Map<String, String>): Boolean {
        if (token.isEmpty()) return false
        if (query["t"] == token) return true
        val cookie = headers["cookie"] ?: return false
        return cookie.split(';').any { part ->
            val value = part.trim().removePrefix(COOKIE_NAME + "=")
            value == token && part.trim().startsWith(COOKIE_NAME)
        }
    }

    private fun writeHtml(output: OutputStream, code: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Set-Cookie: $COOKIE_NAME=$token; Path=/; SameSite=Lax\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    private fun logEvent(client: String, summary: String, bytes: Long = 0, failed: Boolean) {
        _state.update { current ->
            val event = TransferEvent(
                at = System.currentTimeMillis(),
                client = client,
                summary = summary,
                bytes = bytes,
                failed = failed,
            )
            current.copy(events = (listOf(event) + current.events).take(MAX_LOG))
        }
    }

    private fun linkOf(name: String): TransferLink = when {
        name.startsWith("ap") || name.contains("swlan") || name.contains("softap") -> TransferLink.HOTSPOT
        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("eth") -> TransferLink.USB
        name.startsWith("wlan") || name.startsWith("wifi") -> TransferLink.WIFI
        else -> TransferLink.OTHER
    }

    private fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    private fun isPreviewable(name: String): Boolean = isImage(name)

    private fun contentTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "txt", "log", "md" -> "text/plain; charset=utf-8"
        "json" -> "application/json"
        "html", "htm" -> "text/html; charset=utf-8"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }

    private fun glyphFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "mkv", "webm", "mov", "3gp", "m4v" -> "&#127909;"
        "mp3", "m4a", "wav", "ogg", "opus", "flac" -> "&#127925;"
        "pdf", "doc", "docx", "txt", "md", "xls", "xlsx", "ppt", "pptx" -> "&#128196;"
        "zip", "rar", "7z", "tar", "gz" -> "&#128230;"
        "apk" -> "&#128241;"
        else -> "&#128459;"
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }

    private fun jsonEscape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }

    private fun jsEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c")

    private fun nameOf(path: String?): String =
        path?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { path } ?: ""

    companion object {
        const val DEFAULT_PORT = 8080

        /** Shown on the page, so two phones on one network are told apart. */
        val deviceName: String = Build.MODEL ?: "Android"

        private const val TAG = "FileFlow"
        private const val BUFFER = 64 * 1024

        /**
         * Browsers open six connections per host on HTTP/1.1, and a listing full
         * of thumbnails can push past that. Refusing the extras was closing
         * images mid-load, so the ceiling sits above what a browser will ask for
         * rather than exactly at it.
         */
        private const val MAX_CONNECTIONS = 16
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_LINE_CHARS = 2_048
        private const val MAX_LOG = 40
        private const val MAX_ACTION_BYTES = 512 * 1024
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val THUMB_EDGE = 220

        /** The link is long enough to type once, short enough to read out loud. */
        private const val TOKEN_LENGTH = 8
        private const val TOKEN_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
        private const val COOKIE_NAME = "ff_link"

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

        private val DATE_FORMAT = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

        /**
         * The page the browser gets. Written by hand and inlined: a transferred
         * file should never depend on a CDN, a font or a script the phone would
         * have to fetch from the internet while the laptop waits.
         */
        private val STYLE = """
            :root { color-scheme: light dark; --bg:#f6f5fb; --card:#fff; --ink:#1a1a24;
                    --muted:#5c5b67; --line:#e2e0ea; --accent:#5546d6; --danger:#b3261e; }
            @media (prefers-color-scheme: dark) {
              :root { --bg:#121118; --card:#1c1b23; --ink:#e6e3ec; --muted:#a5a2b0;
                      --line:#2e2c38; --accent:#b3acec; --danger:#ffb4ab; }
            }
            * { box-sizing:border-box; }
            body { margin:0; background:var(--bg); color:var(--ink);
                   font:15px/1.5 -apple-system, system-ui, "Segoe UI", Roboto, sans-serif; }
            header { padding:20px 20px 8px; }
            .brand { font-weight:700; letter-spacing:.02em; font-size:20px; display:inline-block; }
            .device { color:var(--muted); font-size:13px; margin-left:8px; display:inline-block; }
            .trail { margin-top:6px; color:var(--muted); word-break:break-all; }
            .trail a { color:var(--accent); text-decoration:none; }
            .sep { margin:0 6px; opacity:.5; }
            main { padding:12px 20px 120px; display:flex; flex-direction:column; gap:16px; }
            .card { background:var(--card); border:1px solid var(--line); border-radius:18px;
                    padding:16px; overflow:hidden; }
            .drop { text-align:center; }
            .drop.over { border-color:var(--accent); }
            .hint { color:var(--muted); font-size:13px; margin:6px 0; }
            button { background:var(--accent); color:#fff; border:0; border-radius:999px;
                     padding:10px 20px; font-size:15px; cursor:pointer; }
            button.danger { background:var(--danger); }
            .bar { height:6px; background:var(--line); border-radius:999px; margin:14px 0 4px;
                   overflow:hidden; }
            .bar > div { height:100%; width:0; background:var(--accent); transition:width .2s; }
            .head { display:flex; align-items:center; gap:12px; margin-bottom:8px; }
            .head .spacer { flex:1; }
            .check { display:flex; align-items:center; gap:8px; color:var(--muted); font-size:14px; }
            table { width:100%; border-collapse:collapse; }
            td { padding:10px 8px; border-bottom:1px solid var(--line); vertical-align:middle; }
            tr:last-child td { border-bottom:0; }
            .sel { width:28px; }
            .thumb { width:56px; }
            .thumb img { width:48px; height:48px; object-fit:cover; border-radius:10px;
                         display:block; background:var(--line); }
            .thumb .ico { font-size:22px; }
            .name a { color:var(--ink); text-decoration:none; font-weight:500;
                      word-break:break-word; }
            .name a:hover { color:var(--accent); }
            .meta { color:var(--muted); font-size:13px; white-space:nowrap; text-align:right; }
            .act { text-align:right; }
            .get { color:var(--accent); text-decoration:none; font-weight:600; font-size:13px; }
            .actionbar { position:fixed; left:0; right:0; bottom:0; display:none;
                         align-items:center; gap:12px; padding:14px 20px;
                         background:var(--card); border-top:1px solid var(--line); }
            .actionbar.show { display:flex; }
            .actionbar .count { flex:1; font-weight:600; }
            .modal { position:fixed; inset:0; background:rgba(0,0,0,.45); display:none;
                     align-items:flex-end; justify-content:center; }
            .modal.show { display:flex; }
            .sheet { background:var(--card); width:100%; max-width:560px;
                     border-radius:22px 22px 0 0; padding:20px; }
            .sheet h2 { margin:0 0 4px; font-size:17px; }
            .folders { max-height:46vh; overflow:auto; display:flex; flex-direction:column;
                       gap:6px; margin:12px 0; }
            .folder { background:transparent; color:var(--ink); border:1px solid var(--line);
                      border-radius:12px; padding:10px 14px; text-align:left; font-size:14px; }
            .sheetActions { display:flex; justify-content:flex-end; gap:10px; }
        """.trimIndent()
    }
}
