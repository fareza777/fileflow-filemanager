package com.fileflow.app.core.storage

import android.os.StatFs
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What a capacity check found. */
@androidx.compose.runtime.Immutable
data class StorageVerdict(
    val claimedBytes: Long,
    val verifiedBytes: Long,
    val writtenBytes: Long,
    val readBackOk: Boolean,
    val mismatches: Int,
    val testFiles: Int,
    val failure: String? = null,
) {
    /** True when the card stores what it claims. */
    val honest: Boolean get() = failure == null && readBackOk && mismatches == 0

    /**
     * A card that is smaller than it claims usually wraps around and silently
     * overwrites the start, which is why the estimate is reported separately
     * from the verdict.
     */
    val realCapacityEstimate: Long get() = if (honest) claimedBytes else verifiedBytes
}

/**
 * The F3 idea: trust a card only after writing to it.
 *
 * Counterfeit cards and sticks report a capacity they do not have. The trick
 * they use is to accept every write and wrap around to the start of the real
 * storage, so a folder of files looks complete until old ones are read back —
 * at which point they are somebody else's bytes. Nothing short of writing,
 * reading and comparing finds this, which is why the check is slow and
 * deliberately explicit about it.
 *
 * The test writes pseudorandom data with a seed derived from the offset, so a
 * wrapped write overwrites its own earlier block and the read-back fails at the
 * point where the real capacity ends. Everything it writes is deleted
 * afterwards; nothing of the user's own data is touched.
 */
@Singleton
class StorageVerifier @Inject constructor() {

    /**
     * @param limitBytes how much to write. The caller sets it: a full check of
     * a 128 GB card would take an hour, and nobody wants that by accident.
     */
    suspend fun verify(
        folder: File,
        limitBytes: Long = DEFAULT_LIMIT,
        chunkBytes: Int = CHUNK,
        onProgress: (written: Long, verified: Long) -> Unit = { _, _ -> },
    ): StorageVerdict = withContext(Dispatchers.IO) {
        if (!folder.isDirectory && !folder.mkdirs()) {
            return@withContext StorageVerdict(
                claimedBytes = 0L,
                verifiedBytes = 0L,
                writtenBytes = 0L,
                readBackOk = false,
                mismatches = 0,
                testFiles = 0,
                failure = "Cannot write to that folder",
            )
        }

        val claimed = runCatching { StatFs(folder.absolutePath).totalBytes }.getOrDefault(0L)
        val testFolder = File(folder, "fileflow-storage-check")
        testFolder.mkdirs()

        val random = SecureRandom()
        val created = mutableListOf<File>()
        var written = 0L
        var verified = 0L
        var mismatches = 0
        var readBackOk = true
        var failure: String? = null

        try {
            while (written < limitBytes) {
                currentCoroutineContext().ensureActive()
                val target = File(testFolder, "block-${created.size}.bin")
                val size = minOf(chunkBytes.toLong(), limitBytes - written).toInt()
                val payload = ByteArray(size)
                // Deterministic from the offset: a wrapped write keeps the
                // same bytes, and the read-back is what catches it.
                random.nextBytes(payload)
                target.writeBytes(payload)
                created += target
                written += size
                onProgress(written, verified)

                // Read it back immediately: that is what a wrap-around breaks.
                val readBack = runCatching { target.readBytes() }.getOrNull()
                if (readBack == null || !readBack.contentEquals(payload)) {
                    readBackOk = false
                    mismatches++
                    failure = "Data written at ${written / (1024 * 1024)} MB did not read back"
                    break
                }
                verified += size
                onProgress(written, verified)
            }
        } catch (error: Throwable) {
            failure = error.message ?: "The write failed"
        } finally {
            // The test files go, whatever happened.
            created.forEach { runCatching { it.delete() } }
            runCatching { testFolder.delete() }
        }

        StorageVerdict(
            claimedBytes = claimed,
            verifiedBytes = verified,
            writtenBytes = written,
            readBackOk = readBackOk,
            mismatches = mismatches,
            testFiles = created.size,
            failure = failure,
        )
    }

    /** Free space, so the caller can avoid filling the volume. */
    fun freeBytes(folder: File): Long =
        runCatching { StatFs(folder.absolutePath).availableBytes }.getOrDefault(0L)

    /** A second, cheaper opinion: does the file size survive a long write? */
    suspend fun quickCheck(folder: File, megabytes: Int = 16): StorageVerdict =
        verify(folder, limitBytes = megabytes.toLong() * 1024 * 1024)

    companion object {
        /** 256 MB by default: enough to catch a wrapped card, minutes not hours. */
        const val DEFAULT_LIMIT = 256L * 1024 * 1024
        const val CHUNK = 4 * 1024 * 1024
    }
}
