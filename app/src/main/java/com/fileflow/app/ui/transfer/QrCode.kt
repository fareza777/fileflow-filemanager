package com.fileflow.app.ui.transfer

import android.graphics.Bitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * The transfer address, as something a camera can read.
 *
 * Typing `http://192.168.1.37:8080` on a laptop is the kind of small friction
 * that makes a feature feel unfinished, and it is also where it goes wrong: a
 * mistyped digit is a connection that never opens, with nothing on screen to
 * say which character was wrong. A QR code removes the transcription entirely.
 *
 * The symbol is built as whole modules at a whole number of pixels each, and
 * drawn without filtering. That is not a style choice. A QR code is a grid of
 * hard edges, the decoder needs those edges in the right places, and both of the
 * ways this normally goes wrong are fixed here: a symbol drawn at a scale the
 * screen cannot resolve, and a symbol resampled by the image pipeline after it
 * was drawn. Either one produces a square that looks correct and does not scan.
 *
 * The encoder is kept free of Android types so it can be tested by decoding its
 * own output on the JVM — a pretty square nobody can read is exactly the failure
 * this cannot afford.
 */
object QrCode {

    /** Quiet zone in modules. Below 2 the symbol gets hard to find in a viewfinder. */
    private const val MARGIN_MODULES = 2

    /**
     * A symbol as a grid of modules, or null when it cannot be scanned.
     *
     * Medium error correction: a phone screen can be scratched, dimmed or partly
     * covered by a finger, and the cheaper levels stop working in a dim room —
     * this is not a code that needs to survive a printing press.
     *
     * [size] is the number of pixels the symbol will be drawn in, so it decides
     * the answer to the only question that matters here: is each module big
     * enough for a camera to see? A long address needs more modules, a smaller
     * canvas holds fewer whole pixels per module, and past a point the honest
     * result is no code at all — a dense square that a phone lens cannot resolve
     * is worse than the address written out in full, which is always shown next
     * to it.
     */
    fun matrix(value: String, size: Int = DEFAULT_SIZE): BitMatrix? {
        if (value.isBlank() || size < MIN_SIZE) return null
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val encoded = runCatching { Encoder.encode(value, ErrorCorrectionLevel.M, hints) }.getOrNull()
            ?: return null

        // The module grid without its quiet zone, straight from the encoder:
        // this is the size the data actually needs, and no canvas can change it.
        val modules = encoded.matrix.width
        val total = modules + MARGIN_MODULES * 2
        val scale = size / total
        if (scale < MIN_MODULE_PIXELS) return null

        // Drawn by hand rather than by QRCodeWriter, because the writer treats
        // the requested size as a minimum and will happily hand back a symbol at
        // one pixel per module. Whole modules at a whole number of pixels each is
        // the only rendering a camera can be relied on to resolve.
        val matrix = BitMatrix(total * scale, total * scale)
        val offset = MARGIN_MODULES * scale
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                if (encoded.matrix.get(x, y).toInt() != 0) {
                    matrix.setRegion(offset + x * scale, offset + y * scale, scale, scale)
                }
            }
        }
        return matrix
    }

    /**
     * The same grid as a bitmap, black on white.
     *
     * Drawn pixel by pixel rather than scaled from a smaller bitmap: an upscaled
     * QR is the smoothed-edge case above, and a symbol is a few thousand writes,
     * once, while a screen is open.
     */
    fun bitmap(value: String, size: Int = DEFAULT_SIZE): Bitmap? {
        val matrix = matrix(value, size) ?: return null
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x, y)) BLACK else WHITE
            }
        }
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }.getOrNull()
    }

    /**
     * The canvas the symbol is built for.
     *
     * 512 is what a 40-character address needs to keep its modules comfortably
     * above the density floor; the screen then sizes the box from the bitmap it
     * gets back, so a symbol that comes out smaller is still drawn one to one.
     */
    const val DEFAULT_SIZE = 512

    /** Below this the canvas is not worth encoding into at all. */
    const val MIN_SIZE = 64

    /**
     * How many screen pixels each module must get.
     *
     * Below three, a camera is being asked to resolve detail it usually cannot —
     * the symbol survives a perfect decoder in a test and fails on the desk the
     * app is used from, which is the wrong way round.
     */
    const val MIN_MODULE_PIXELS = 3

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
