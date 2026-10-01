package com.fileflow.app.core.semantic

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the image tower was trained to be handed, and nothing else.
 *
 * CLIP's preprocessing is not a detail. The model learned to place images in a
 * space by looking at pictures that were resized to 224 with the shorter side
 * matched, centre cropped, scaled to 0..1 and normalised with *its* mean and
 * standard deviation. Feed it a stretched image, or one normalised with the
 * ImageNet statistics that are used for classification models, and every vector
 * moves — the search still returns results, they are just worse, which is the
 * hardest kind of bug to notice.
 *
 * Kept as pure functions so the arithmetic can be checked without a device or a
 * model: the tests assert the crop, the padding and the normalised values.
 */
object ClipImagePreprocess {

    const val INPUT_SIDE = 224
    const val RESIZE_SIDE = 256

    /** CLIP's own statistics, from the original release. */
    val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    /**
     * The bitmap as a NCHW float buffer the interpreter can read: channels
     * first, because that is the layout these models were exported with.
     */
    fun toTensor(bitmap: Bitmap): ByteBuffer {
        val scaled = resizeShorterSide(bitmap, RESIZE_SIDE)
        val cropped = cropCentre(scaled, INPUT_SIDE)
        val pixels = IntArray(INPUT_SIDE * INPUT_SIDE)
        cropped.getPixels(pixels, 0, INPUT_SIDE, 0, 0, INPUT_SIDE, INPUT_SIDE)

        val values = normalise(pixels)
        val buffer = ByteBuffer
            .allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
        values.forEach { buffer.putFloat(it) }
        buffer.rewind()
        return buffer
    }

    /**
     * ARGB pixels to the normalised channel-first floats the model takes.
     *
     * Separated from [toTensor] so the arithmetic can be tested on a plain JVM
     * with an array of integers: the resize and the crop need a device, but the
     * part that is most often wrong — the mean, the order of the channels, the
     * scale — does not.
     */
    fun normalise(pixels: IntArray): FloatArray {
        val values = FloatArray(3 * pixels.size)
        for (channel in 0..2) {
            val shift = when (channel) {
                0 -> 16 // red
                1 -> 8 // green
                else -> 0 // blue
            }
            val offset = channel * pixels.size
            for (index in pixels.indices) {
                val value = ((pixels[index] shr shift) and 0xFF) / 255f
                values[offset + index] = (value - MEAN[channel]) / STD[channel]
            }
        }
        return values
    }

    /** Scales so the shorter side is [side], keeping the aspect ratio. */
    fun resizeShorterSide(bitmap: Bitmap, side: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap
        val shorter = min(width, height)
        if (shorter == side) return bitmap

        val scale = side.toFloat() / shorter.toFloat()
        val targetWidth = max(side, (width * scale).roundToInt())
        val targetHeight = max(side, (height * scale).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /** The middle [side] square, which is what CLIP's centre crop takes. */
    fun cropCentre(bitmap: Bitmap, side: Int): Bitmap {
        if (bitmap.width == side && bitmap.height == side) return bitmap
        val x = max(0, (bitmap.width - side) / 2)
        val y = max(0, (bitmap.height - side) / 2)
        val width = min(side, bitmap.width)
        val height = min(side, bitmap.height)
        return Bitmap.createBitmap(bitmap, x, y, width, height)
    }
}

/**
 * One of the two towers.
 *
 * The two model files come from the same family and the repository does not say
 * which is which, so the role is decided by reading the input tensor rather than
 * by trusting a file name: a model that takes 32-bit integers is the text tower,
 * a model that takes floats shaped like an image is the image tower. A file that
 * is neither is refused with a reason, because a tower loaded into the wrong
 * slot produces vectors that are all the same length and meaningless.
 */
enum class TowerRole { IMAGE, TEXT, UNKNOWN }
