package com.fileflow.app.core.semantic

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val Context.downloadStore by preferencesDataStore(name = "fileflow_model_download")

/** Where a download has got to, and what it is doing. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val asset: String, val done: Long, val total: Long, val attempt: Int) : DownloadState
    data class Finished(val bytes: Long) : DownloadState
    data class Failed(val reason: String) : DownloadState
}

/**
 * What a server's answer means for the bytes already on disk.
 *
 * This is the whole of "resume", and it is a decision table rather than a
 * feature: `206 Partial Content` means the range was honoured and the file
 * continues; `200 OK` means the server ignored the range and is sending the
 * whole object, so the partial file is now wrong and must be replaced; and a
 * file that is already the full size needs no request at all. Getting any of
 * the three wrong is a corrupt 400 MB model that loads and produces nonsense,
 * so it is isolated here and tested.
 */
internal object ResumeDecision {

    enum class Action { APPEND, RESTART, COMPLETE, /** The range was past the end. */ REFUSED }

    fun forResponse(statusCode: Int, alreadyOnDisk: Long, expected: Long): Action = when {
        // 416 is the server saying "there is nothing after that byte". It means
        // the partial file is as long as the file gets — either the download
        // finished last time and was never renamed, or our idea of the size was
        // wrong. Either way, asking again is what turned one refusal into five.
        statusCode == 416 && alreadyOnDisk > 0 -> Action.REFUSED
        expected > 0 && alreadyOnDisk >= expected -> Action.COMPLETE
        statusCode == 206 && alreadyOnDisk > 0 -> Action.APPEND
        // 200 with a partial file: the range was not honoured, so starting over
        // is the only way to end up with a file that is not two halves of
        // different downloads stitched together.
        else -> Action.RESTART
    }

    /**
     * The real length of the file, from what the server said rather than from
     * what we assumed.
     *
     * `X-Linked-Size` is Hugging Face's own answer for a file it stores;
     * `Content-Range: bytes 0-99/1234` is the standard one for a ranged
     * response, and the part after the slash is the whole file. A guess in the
     * code is the last resort, and only for showing progress.
     */
    fun totalSize(
        linkedSize: String?,
        contentRange: String?,
        contentLength: Long,
        appending: Boolean,
        alreadyOnDisk: Long,
        fallback: Long,
    ): Long {
        linkedSize?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        contentRange
            ?.substringAfterLast('/', "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "*" }
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { return it }
        if (contentLength > 0) {
            return if (appending) alreadyOnDisk + contentLength else contentLength
        }
        return fallback
    }
}

/**
 * Downloads the model so that closing the app does not lose the download.
 *
 * Two things make this different from the obvious version:
 *
 * **It resumes.** Every request asks for the bytes after what is already on
 * disk, appends to the existing file, and treats a `206` as "carry on" and a
 * `200` as "the server does not do ranges, start again". A 397 MB file over a
 * phone connection *will* be interrupted, and starting from zero each time means
 * it never finishes: that is exactly the failure the user reported.
 *
 * **It survives.** The bytes done are written to disk as they arrive, so the
 * progress survives the process being killed, and the work itself runs in a
 * foreground service so it keeps going when the app is closed. Reopening the app
 * reads the same numbers back and shows the same progress, and the service
 * carries on from the same byte.
 *
 * A stalled connection fails and is retried with a backoff rather than hanging
 * forever; a 400 MB body with no read timeout is a download that can sit at
 * 99% until the battery dies.
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: SemanticModels,
) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Not zero: a connection that stops delivering must fail so the retry
        // can resume it, instead of waiting forever on a socket that is dead.
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** The next file without one, in the order the tier needs them. */
    suspend fun nextMissing(): SemanticAsset? =
        SemanticAsset.entries.firstOrNull { !models.present(it) }

    suspend fun completed(): Boolean = models.ready()

    /** Reads back what a previous run left behind, so the screen opens honest. */
    suspend fun restoreState() = withContext(Dispatchers.IO) {
        val prefs = context.downloadStore.data.first()
        val name = prefs[KEY_ASSET]
        val done = prefs[KEY_DONE] ?: 0L
        if (name != null && done > 0) {
            val asset = SemanticAsset.entries.firstOrNull { it.fileName == name }
            if (asset != null && !models.present(asset)) {
                _state.update {
                    DownloadState.Running(asset.label, done, asset.approximateBytes, attempt = 0)
                }
            }
        }
    }

    suspend fun cancel() = withContext(Dispatchers.IO) {
        context.downloadStore.edit { prefs ->
            prefs.remove(KEY_ASSET)
            prefs.remove(KEY_DONE)
        }
        _state.update { DownloadState.Idle }
    }

    /**
     * Fetches every missing file, resuming each one, and gives up only after
     * several attempts have failed in a row.
     */
    suspend fun downloadAll() = withContext(Dispatchers.IO) {
        for (asset in SemanticAsset.entries) {
            if (models.present(asset)) continue
            var attempt = 0
            while (attempt < MAX_ATTEMPTS) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                attempt++
                val result = fetch(asset, attempt)
                if (result == null) break // fetched
                if (attempt >= MAX_ATTEMPTS) {
                    _state.update { DownloadState.Failed(result) }
                    return@withContext
                }
                _state.update {
                    DownloadState.Failed("$result — retrying (${attempt + 1}/$MAX_ATTEMPTS)")
                }
                delay(BACKOFF_MILLIS * attempt)
            }
        }
        _state.update { DownloadState.Finished(models.sizeOnDisk()) }
        context.downloadStore.edit { prefs ->
            prefs.remove(KEY_ASSET)
            prefs.remove(KEY_DONE)
        }
    }

    /**
     * One attempt at one file.
     *
     * @return null when the file is complete, or a reason it is not.
     */
    private suspend fun fetch(asset: SemanticAsset, attempt: Int): String? {
        val target = models.file(asset)
        val partial = File(target.parentFile, "${target.name}.part")
        models.ensureDirectory()

        // What is already here is the starting point, not a reason to start over.
        // The "and if it is already complete?" case is answered by the server
        // rather than by a comparison with the number above: it replies 416 when
        // there is nothing past that byte, which is the same answer and does not
        // depend on our arithmetic being right.
        val already = partial.takeIf { it.isFile }?.length() ?: 0L

        val request = Request.Builder()
            .url(asset.url)
            .apply {
                if (already > 0) header("Range", "bytes=$already-")
                // Hugging Face serves through a CDN; without this a resumed
                // request can be answered with a different object.
                header("Accept-Encoding", "identity")
            }
            .build()

        context.downloadStore.edit { prefs ->
            prefs[KEY_ASSET] = asset.fileName
            prefs[KEY_DONE] = already
        }
        _state.update { DownloadState.Running(asset.label, already, asset.approximateBytes, attempt) }

        return try {
            client.newCall(request).execute().use { response ->
                // The length comes from the server, never from the constant in
                // the enum: a guess that is too high makes a finished file look
                // truncated, and then asks for a range past the end of it. It
                // is read *first*, so the decision below is made against the
                // real size rather than against the guess.
                val appending = response.code == 206 && already > 0
                val total = ResumeDecision.totalSize(
                    linkedSize = response.header("X-Linked-Size"),
                    contentRange = response.header("Content-Range"),
                    contentLength = response.body?.contentLength() ?: -1L,
                    appending = appending,
                    alreadyOnDisk = already,
                    fallback = asset.approximateBytes,
                )
                val action = ResumeDecision.forResponse(response.code, already, total)
                // A file that declares its own hash can be checked against it,
                // which is the only way to know that 400 MB arrived intact.
                val expectedHash = response.header("X-Linked-Etag")?.trim('"', ' ')

                if (
                    action == ResumeDecision.Action.REFUSED ||
                    action == ResumeDecision.Action.COMPLETE
                ) {
                    // What is on disk is the whole file: the server says there
                    // is nothing after it (416), or its own size says we
                    // already have it all. Either way it is accepted and then
                    // checked; a wrong size guess is our mistake, not a reason
                    // to download 400 MB again.
                    checkHash(partial, expectedHash)?.let { return it }
                    if (partial.renameTo(target)) return null
                    partial.delete()
                    return "the file was complete but could not be kept"
                }

                if (!response.isSuccessful) return "HTTP ${response.code}"

                val start = if (action == ResumeDecision.Action.APPEND) {
                    already
                } else {
                    partial.delete()
                    0L
                }

                val body = response.body ?: return "empty response"
                var written = start
                RandomAccessFile(partial, "rw").use { file ->
                    file.seek(start)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            file.write(buffer, 0, read)
                            written += read
                            _state.update {
                                DownloadState.Running(asset.label, written, total, attempt)
                            }
                            // Persisted every few megabytes: often enough that a
                            // kill costs seconds, rare enough that it is not a
                            // disk write per chunk.
                            if (written % PERSIST_EVERY < buffer.size) {
                                context.downloadStore.edit { prefs -> prefs[KEY_DONE] = written }
                            }
                        }
                    }
                }
                context.downloadStore.edit { prefs -> prefs[KEY_DONE] = written }

                // Measured against the server's own number, not against the
                // guess in the enum: a guess that was wrong once cost five
                // attempts and an HTTP 416.
                if (total > 0 && written < total * MIN_COMPLETE_RATIO) {
                    // Short body: keep the partial so the next attempt resumes.
                    return "the download stopped early at ${written / (1024 * 1024)} MB of " +
                        "${total / (1024 * 1024)} MB"
                }

                checkHash(partial, expectedHash)?.let { return it }

                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                context.downloadStore.edit { prefs ->
                    prefs.remove(KEY_ASSET)
                    prefs.remove(KEY_DONE)
                }
                null
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            error.message ?: "network error"
        }
    }

    /**
     * The checksum verdict: null when the file is fine (or says nothing about
     * itself), and the reason to discard it when it is not.
     */
    private suspend fun checkHash(partial: File, expectedHash: String?): String? {
        if (expectedHash.isNullOrBlank() || expectedHash.length != 64) return null
        val actual = sha256(partial) ?: return null
        if (actual.equals(expectedHash, ignoreCase = true)) return null
        partial.delete()
        context.downloadStore.edit { prefs ->
            prefs.remove(KEY_ASSET)
            prefs.remove(KEY_DONE)
        }
        return "the file did not match its checksum"
    }

    /** The SHA-256 of a file, hex, or null when it could not be read. */
    private fun sha256(file: File): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(512 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val BACKOFF_MILLIS = 2_000L
        const val PERSIST_EVERY = 4L * 1024 * 1024
        const val MIN_COMPLETE_RATIO = 0.98f

        val KEY_ASSET = stringPreferencesKey("asset")
        val KEY_DONE = longPreferencesKey("done")
    }
}
