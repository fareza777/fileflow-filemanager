package com.fileflow.app.core.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a photo is of, in the model's own vocabulary.
 *
 * The labelling model knows about four hundred things — Receipt, Team,
 * Community, Crowd, Money, Mountain, Cake — and a photo comes back with a
 * handful of them. That is coarse compared with a captioning model, and it is
 * much more honest than one: the labels are a fixed, published list, so what
 * "kerja bakti" can match is knowable in advance rather than plausible-sounding
 * guesswork. [ThemeVocabulary] is the mapping, and it is a table the user can
 * read.
 *
 * Same rule as the text recogniser: on-device, model fetched on demand, and a
 * photo that cannot be labelled simply has no labels.
 */
@Singleton
class PhotoThemes @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val labeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                // Below this the labels are noise: a photo of a street comes
                // back with thirty things at 0.3 confidence, which is worse
                // than four at 0.6 when the point is to find the photo again.
                .setConfidenceThreshold(MIN_CONFIDENCE)
                .build()
        )
    }

    /** Up to [limit] label words, strongest first, lowercased. */
    suspend fun labels(file: File, limit: Int = MAX_LABELS): List<String> = withContext(Dispatchers.IO) {
        val bitmap = decode(file) ?: return@withContext emptyList()
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            labeler.process(image).awaitOrNull()
                .orEmpty()
                .sortedByDescending { it.confidence }
                .take(limit)
                .map { it.text.lowercase() }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // The labeller takes a 224-pixel square; more detail is only slower.
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= MAX_EDGE) {
            sample *= 2
            longest /= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    private companion object {
        const val MIN_CONFIDENCE = 0.6f
        const val MAX_LABELS = 8
        const val MAX_EDGE = 800
    }
}
