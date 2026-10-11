package com.fileflow.app.core.semantic

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a model wants its pixels: which axis carries the channels. */
enum class ImageLayout { CHANNELS_FIRST, CHANNELS_LAST }

/**
 * What the image tower was trained to be handed, and nothing else.
 *
 * CLIP's preprocessing is not a detail. The model learned to place images in a
 * space by looking at pictures resized with the shorter side matched, centre
 * cropped, scaled to 0..1 and normalised with *its* mean and standard deviation.
 * Feed it a stretched image, or one normalised with the ImageNet statistics used
 * for classification models, and every vector moves — the search still returns
 * results, they are just worse, which is the hardest kind of bug to notice.
 *
 * Which is also why the *layout* matters: `[1,3,224,224]` is channels first and
 * `[1,224,224,3]` is channels last, both are four-dimensional float tensors, and
 * writing one where the other is wanted produces a vector of the right length
 * that means nothing. The exported model says which it is; this reads it.
 *
 * Kept as pure functions so the arithmetic can be checked without a device or a
 * model: the tests assert the crop, the channel order and the normalised values.
 */
object ClipImagePreprocess {

    const val INPUT_SIDE = 224

    /** CLIP resizes the shorter side to 256 before cropping 224. */
    const val RESIZE_RATIO = 256f / 224f

    /** CLIP's own statistics, from the original release. */
    val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    /** The pixels the model wants: shorter side matched, centre cropped. */
    fun pixels(bitmap: Bitmap, side: Int): IntArray {
        val resized = resizeShorterSide(bitmap, (side * RESIZE_RATIO).roundToInt())
        val cropped = cropCentre(resized, side)
        val out = IntArray(side * side)
        cropped.getPixels(out, 0, side, 0, 0, side, side)
        return out
    }

    /**
     * Normalised floats, in the order the model declares.
     *
     * Separated from the bitmap so the arithmetic can be tested on a plain JVM
     * with an array of integers: the resize and the crop need a device, the part
     * that is most often wrong — the mean, the order of the channels, the scale
     * — does not.
     */
    fun floats(pixels: IntArray, side: Int, layout: ImageLayout): FloatArray {
        val values = FloatArray(3 * side * side)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = ((pixel shr 16) and 0xFF) / 255f
            val green = ((pixel shr 8) and 0xFF) / 255f
            val blue = (pixel and 0xFF) / 255f
            values[offset(0, index, side, layout)] = (red - MEAN[0]) / STD[0]
            values[offset(1, index, side, layout)] = (green - MEAN[1]) / STD[1]
            values[offset(2, index, side, layout)] = (blue - MEAN[2]) / STD[2]
        }
        return values
    }

    /** A quantised model takes the bytes as they are, without normalising. */
    fun bytes(pixels: IntArray, side: Int, layout: ImageLayout): ByteArray {
        val values = ByteArray(3 * side * side)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            values[offset(0, index, side, layout)] = ((pixel shr 16) and 0xFF).toByte()
            values[offset(1, index, side, layout)] = ((pixel shr 8) and 0xFF).toByte()
            values[offset(2, index, side, layout)] = (pixel and 0xFF).toByte()
        }
        return values
    }

    /** Where channel [channel] of pixel [index] sits, for the model's layout. */
    private fun offset(channel: Int, index: Int, side: Int, layout: ImageLayout): Int =
        when (layout) {
            // [1, 3, H, W]: a whole plane per channel.
            ImageLayout.CHANNELS_FIRST -> channel * side * side + index
            // [1, H, W, 3]: three values per pixel.
            ImageLayout.CHANNELS_LAST -> index * 3 + channel
        }

    fun buffer(values: FloatArray): ByteBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).apply {
            values.forEach { putFloat(it) }
            rewind()
        }

    fun buffer(values: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(values.size).order(ByteOrder.nativeOrder()).apply {
            put(values)
            rewind()
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
 * What a model file turned out to hold.
 *
 * [BOTH] is what this export actually is: one file carrying the image tower and
 * the text tower together, which the repository's `first`/`last` naming hides —
 * those two files are near-identical copies of the same dual-tower model, not
 * one tower each. Assuming one tower per file classified both of them as IMAGE
 * and made the engine report that its inputs were not what the app expects.
 *
 * The role is always read from the model itself. A file that matches nothing is
 * described rather than guessed at: a tower fed through the wrong path produces
 * vectors of the right length that mean nothing.
 */
enum class TowerRole { IMAGE, TEXT, BOTH, UNKNOWN }
