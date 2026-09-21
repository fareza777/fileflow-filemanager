package com.fileflow.app.domain.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.domain.Candidate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One photo, reduced to a 64-bit fingerprint of how it looks. */
@androidx.compose.runtime.Immutable
data class PhotoHash(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** Difference hash: the sign of each horizontal gradient in a 9x8 grid. */
    val difference: Long,
    /** Average hash: each pixel against the mean of the tile. */
    val average: Long,
)

/** Photos that look like each other, kept together. */
@androidx.compose.runtime.Immutable
data class PhotoGroup(
    val photos: List<PhotoHash>,
) {
    /** The best copy: the largest, then the oldest, which is usually the original. */
    val best: PhotoHash
        get() = photos.maxWithOrNull(
            compareBy<PhotoHash> { it.sizeBytes }.thenByDescending { -it.lastModified }
        ) ?: photos.first()

    val wastedBytes: Long get() = photos.sumOf { it.sizeBytes } - best.sizeBytes
}

/**
 * Similar photos, by look rather than by name.
 *
 * Two shots of the same moment are different files by every measure a file
 * manager normally uses: different names, different sizes, different pixels.
 * What they share is their *shape* — the light and dark layout of the image —
 * and that survives being resized to eight by eight. Two 64-bit hashes compared
 * by bit distance then say "the same picture" in a few instructions per pair,
 * which is what makes scanning a camera roll on a phone practical.
 *
 * Both hashes are computed and both must agree: a difference hash is stable
 * against brightness and contrast, an average hash against re-compression, and
 * requiring both keeps a dark photo from matching a bright one.
 */
@Singleton
class SimilarPhotoScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun scan(
        candidates: List<Candidate>,
        threshold: Int = DEFAULT_THRESHOLD,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): List<PhotoGroup> = withContext(Dispatchers.IO) {
        val photos = candidates
            .filter { !it.isDirectory && it.category == FileCategory.IMAGES && it.sizeBytes > 0 }
            .mapIndexedNotNull { index, candidate ->
                currentCoroutineContext().ensureActive()
                onProgress(index, candidates.size)
                hashes(candidate)?.let { candidate to it }
            }

        // Grouping is a union-find over the pairs that are close enough: a
        // photo similar to two others that are not similar to each other
        // belongs to one group, not to two half-groups.
        val parent = IntArray(photos.size) { it }
        fun find(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        fun union(left: Int, right: Int) {
            val a = find(left)
            val b = find(right)
            if (a != b) parent[maxOf(a, b)] = minOf(a, b)
        }

        for (left in photos.indices) {
            currentCoroutineContext().ensureActive()
            for (right in left + 1 until photos.size) {
                val first = photos[left].second
                val second = photos[right].second
                if (distance(first.difference, second.difference) <= threshold &&
                    distance(first.average, second.average) <= threshold
                ) {
                    union(left, right)
                }
            }
        }

        photos.indices
            .groupBy { find(it) }
            .values
            .map { indices -> PhotoGroup(indices.map { photos[it].second }) }
            .filter { it.photos.size > 1 }
            .sortedByDescending { it.wastedBytes }
    }

    private fun hashes(candidate: Candidate): PhotoHash? {
        val bitmap = decodeSmall(candidate.path) ?: return null
        return try {
            PhotoHash(
                path = candidate.path,
                name = candidate.name,
                sizeBytes = candidate.sizeBytes,
                lastModified = candidate.lastModified,
                difference = differenceHash(bitmap),
                average = averageHash(bitmap),
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Nine by eight: one row of pixels more than entries, because a difference
     * hash compares each pixel with the one to its right.
     */
    private fun differenceHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = luminance(scaled.getPixel(x, y))
                val right = luminance(scaled.getPixel(x + 1, y))
                if (left > right) hash = hash or (1L shl bit)
                bit++
            }
        }
        if (scaled != bitmap) scaled.recycle()
        return hash
    }

    private fun averageHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val values = LongArray(64)
        var total = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val value = luminance(scaled.getPixel(x, y))
                values[y * 8 + x] = value
                total += value
            }
        }
        val mean = total / 64
        var hash = 0L
        values.forEachIndexed { index, value ->
            if (value >= mean) hash = hash or (1L shl index)
        }
        if (scaled != bitmap) scaled.recycle()
        return hash
    }

    private fun luminance(color: Int): Long {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        return (red * 299L + green * 587L + blue * 114L) / 1000L
    }

    /** Decodes by sampling, so a 40 MP photo costs a few hundred KB of bitmap. */
    private fun decodeSmall(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= 64) sample *= 2
        return runCatching {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    /** Bit distance between two hashes; 0 is identical, 64 is unrelated. */
    private fun distance(left: Long, right: Long): Int = java.lang.Long.bitCount(left xor right)

    companion object {
        /** Below this, two photos are treated as the same picture. */
        const val DEFAULT_THRESHOLD = 6
    }
}
