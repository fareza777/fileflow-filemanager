package com.fileflow.app.core.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preprocessing is where a semantic search goes quietly wrong.
 *
 * A model fed the wrong normalisation still returns vectors and still ranks
 * photos — the results are just worse, in a way nobody can attribute to a
 * cause. So the arithmetic is checked directly: the channel order, the scale,
 * the mean, and the fact that a mid-grey pixel lands where CLIP expects it.
 */
class ClipImagePreprocessTest {

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    @Test
    fun `channels are written in order, all reds first`() {
        // NCHW, not interleaved: getting this wrong swaps every colour.
        val pixels = intArrayOf(argb(255, 0, 0), argb(0, 255, 0))
        val values = ClipImagePreprocess.normalise(pixels)

        assertEquals(6, values.size)
        // First channel is red: the first pixel is red, the second is not.
        assertTrue("red channel of a red pixel", values[0] > values[1])
        // Second channel is green: the second pixel is green, the first is not.
        assertTrue("green channel of a green pixel", values[3] > values[2])
    }

    @Test
    fun `a white pixel normalises to the value CLIP was trained on`() {
        val values = ClipImagePreprocess.normalise(intArrayOf(argb(255, 255, 255)))
        // (1 - mean) / std, per channel, in CLIP's own statistics.
        val expectedRed = (1f - 0.48145466f) / 0.26862954f
        val expectedGreen = (1f - 0.4578275f) / 0.26130258f
        val expectedBlue = (1f - 0.40821073f) / 0.27577711f
        assertEquals(expectedRed, values[0], 0.0001f)
        assertEquals(expectedGreen, values[1], 0.0001f)
        assertEquals(expectedBlue, values[2], 0.0001f)
    }

    @Test
    fun `a black pixel comes out negative, which is the point of the mean`() {
        val values = ClipImagePreprocess.normalise(intArrayOf(argb(0, 0, 0)))
        values.forEach { assertTrue("expected a negative value, got $it", it < 0f) }
    }

    @Test
    fun `the scale is zero to one, not zero to 255`() {
        // Feeding 0..255 instead of 0..1 is the classic version of this bug and
        // it makes every vector enormous and useless.
        val white = ClipImagePreprocess.normalise(intArrayOf(argb(255, 255, 255)))
        val mid = ClipImagePreprocess.normalise(intArrayOf(argb(128, 128, 128)))
        assertTrue("a white pixel must be small and positive, got ${white[0]}", white[0] < 3f)
        assertTrue("mid grey must be near zero, got ${mid[0]}", kotlin.math.abs(mid[0]) < 0.2f)
    }

    @Test
    fun `alpha is ignored rather than read as colour`() {
        val opaque = ClipImagePreprocess.normalise(intArrayOf(0xFF shl 24 or (10 shl 16) or (20 shl 8) or 30))
        val transparent = ClipImagePreprocess.normalise(intArrayOf(0x00 shl 24 or (10 shl 16) or (20 shl 8) or 30))
        assertTrue("alpha must not change the values", opaque.contentEquals(transparent))
    }
}
