package com.fileflow.app.core.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The words inside a photo.
 *
 * This is the half of photo search that can be *accurate*, and it is worth
 * being clear about why: a transfer receipt, a screenshot, a whiteboard, a
 * form, a sign — the thing the user is searching for is written in the picture.
 * Reading it and indexing it answers "find my transfer proof" exactly, with no
 * guessing about what the picture is *of*.
 *
 * The recogniser comes from ML Kit and runs on the device, which is the only
 * acceptable place for it: these are the user's receipts and screenshots, and
 * sending them anywhere would be a different product. The model is fetched on
 * demand (see [VisionModels]) so the app does not carry 4 MB of it for someone
 * who never searches a photo.
 */
@Singleton
class PhotoText @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * The text in [file], or null when there is none to read.
     *
     * The image is downscaled first. A modern phone photo is 12 megapixels of
     * detail that OCR cannot use — ML Kit wants each character to be 16 to 24
     * pixels tall, and a receipt photographed from arm's length reaches that at
     * around 1600 pixels on the long edge. Feeding it the full frame costs
     * memory and time for no accuracy, and on a 48 MP photo it is the
     * difference between a phone that stays responsive and one that does not.
     */
    suspend fun read(file: File): String? = withContext(Dispatchers.IO) {
        val bitmap = decode(file) ?: return@withContext null
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val text = recognizer.process(image).awaitOrNull()?.text
            text?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            // A photo that cannot be read is a photo with no text, not a crash.
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
    }.getOrNull()

    /** Power-of-two downscale until the long edge is near [MAX_EDGE]. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_EDGE) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    private companion object {
        const val MAX_EDGE = 1600
    }
}

/**
 * Bridges a Play Services task into a coroutine.
 *
 * Written here rather than pulling in another library for one call, and it
 * treats every failure as "no result" because that is what it means for the
 * caller: an image that could not be read is an image with nothing to index.
 */
internal suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
    addOnFailureListener { if (cont.isActive) cont.resume(null) }
    addOnCanceledListener { if (cont.isActive) cont.resume(null) }
}
