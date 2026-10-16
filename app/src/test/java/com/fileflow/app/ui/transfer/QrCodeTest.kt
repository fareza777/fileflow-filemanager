package com.fileflow.app.ui.transfer

import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A QR code that does not scan is worse than no QR code: the user points a
 * camera at it, nothing happens, and there is no error to read.
 *
 * So the encoder is tested by decoding its own output back — the same thing the
 * phone on the other end of the desk will do — rather than by asserting that it
 * returned a square of the requested size.
 */
class QrCodeTest {

    private fun decode(matrix: BitMatrix): String {
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val source = RGBLuminanceSource(width, height, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun `a transfer address survives the round trip`() {
        val url = "http://192.168.1.37:8080"
        val matrix = QrCode.matrix(url)
        assertNotNull(matrix)
        assertEquals(url, decode(matrix!!))
    }

    @Test
    fun `a long tokenised address still reads at the size the screen uses`() {
        // The server appends an access token, so the real URL is much longer
        // than a bare address and has a query string in it.
        val url = "http://192.168.100.214:8080/?token=9f2c7a41b8e05d36&page=1"
        val matrix = QrCode.matrix(url)
        assertNotNull(matrix)
        assertEquals(url, decode(matrix!!))
    }

    @Test
    fun `nothing is offered for an empty address`() {
        assertNull(QrCode.matrix(""))
        assertNull(QrCode.matrix("   "))
    }

    @Test
    fun `a canvas below the floor is refused outright`() {
        assertNull(QrCode.matrix("http://10.0.0.2:8080", size = 8))
        assertNull(QrCode.matrix("http://10.0.0.2:8080", size = QrCode.MIN_SIZE - 1))
    }

    @Test
    fun `the canvas is never grown beyond what was asked for`() {
        // The encoder library's own writer treats the size as a minimum and will
        // return a bigger canvas than requested; this one rounds down to whole
        // modules instead, because the caller is placing it in a layout.
        val matrix = QrCode.matrix("http://10.0.0.2:8080", size = 512)
        assertNotNull(matrix)
        assertTrue("expected at most 512, got ${matrix!!.width}", matrix.width <= 512)
    }

    @Test
    fun `a symbol too dense to scan is refused rather than drawn small`() {
        // This is the failure the density floor exists for. A long address needs
        // more modules than a small canvas can hold at a resolvable size, and a
        // one-pixel-per-module square passes a decoder in a test while failing
        // every camera it meets in real life. The address is still shown as text
        // next to where the code would have been.
        val longUrl = "http://192.168.100.214:8080/?" + "a".repeat(400)
        val boundary = QrCode.MIN_MODULE_PIXELS * (modulesIn(longUrl) + 2 * QUIET_ZONE)
        assertNull("one pixel less than the floor must be refused", QrCode.matrix(longUrl, size = boundary - 1))
        assertNull(QrCode.matrix(longUrl, size = QrCode.MIN_SIZE))
    }

    @Test
    fun `the same address is offered once there is room for it`() {
        // The other side of the floor: what makes a symbol unscannable is the
        // room it is given, not how long the address is.
        val longUrl = "http://192.168.100.214:8080/?" + "a".repeat(400)
        val boundary = QrCode.MIN_MODULE_PIXELS * (modulesIn(longUrl) + 2 * QUIET_ZONE)
        val matrix = QrCode.matrix(longUrl, size = boundary)
        assertNotNull("the size the floor allows must be accepted", matrix)
        assertEquals(longUrl, decode(matrix!!))
    }

    /**
     * The module grid the data itself needs, straight from the encoder.
     *
     * Asked for directly rather than inferred from a rendered size, so the test
     * states the rule — [QrCode.MIN_MODULE_PIXELS] pixels per module plus the
     * quiet zone — instead of a number that happens to be true today.
     */
    private fun modulesIn(value: String): Int = Encoder.encode(
        value,
        ErrorCorrectionLevel.M,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"),
    ).matrix.width

    private companion object {
        /** Must match the margin [QrCode] draws around the symbol. */
        const val QUIET_ZONE = 2
    }

    @Test
    fun `there is a quiet zone around the symbol`() {
        // A code with no margin is a code a scanner cannot find, and the margin
        // is the part it is easiest to lose when the drawing is resized.
        val matrix = QrCode.matrix("http://10.0.0.2:8080")!!
        (0 until matrix.width).forEach { x ->
            assertTrue("top edge must stay clear", !matrix.get(x, 0))
            assertTrue("bottom edge must stay clear", !matrix.get(x, matrix.height - 1))
        }
        (0 until matrix.height).forEach { y ->
            assertTrue("left edge must stay clear", !matrix.get(0, y))
        }
    }

    @Test
    fun `the symbol is square and carries both colours`() {
        val matrix = QrCode.matrix("http://10.0.0.2:8080")!!
        assertEquals(matrix.width, matrix.height)
        var black = 0
        var white = 0
        (0 until matrix.height).forEach { y ->
            (0 until matrix.width).forEach { x ->
                if (matrix.get(x, y)) black++ else white++
            }
        }
        // All one colour would be a square that decodes to nothing.
        assertTrue("expected dark modules, found $black", black > 0)
        assertTrue("expected light modules, found $white", white > 0)
    }
}
