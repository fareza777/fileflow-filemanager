package com.fileflow.app.core.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        // Channels first, not interleaved: getting this wrong swaps every colour.
        val pixels = intArrayOf(argb(255, 0, 0), argb(0, 255, 0))
        val values = ClipImagePreprocess.floats(pixels, side = 2, layout = ImageLayout.CHANNELS_FIRST)

        assertEquals(12, values.size)
        // The red plane is the first four values: the first pixel is red, the
        // second is not.
        assertTrue("red channel of a red pixel", values[0] > values[1])
        // The green plane starts at index 4: the second pixel is green there.
        assertTrue("green channel of a green pixel", values[5] > values[4])
    }

    @Test
    fun `channels last writes three values per pixel instead`() {
        val pixels = intArrayOf(argb(255, 0, 0), argb(0, 255, 0))
        val values = ClipImagePreprocess.floats(pixels, side = 2, layout = ImageLayout.CHANNELS_LAST)

        // The first pixel is the first three values, red first.
        assertTrue(values[0] > values[1])
        assertTrue(values[0] > values[2])
        // The second pixel starts at index 3 and is green in the middle.
        assertTrue(values[4] > values[3])
        assertTrue(values[4] > values[5])
    }

    @Test
    fun `the two layouts are not the same buffer`() {
        // Writing one where the other is wanted produces a vector of the right
        // length that means nothing, which is why the layout is read from the
        // model rather than assumed.
        val pixels = intArrayOf(argb(255, 0, 0), argb(0, 255, 0), argb(0, 0, 255), argb(10, 10, 10))
        val first = ClipImagePreprocess.floats(pixels, 2, ImageLayout.CHANNELS_FIRST)
        val last = ClipImagePreprocess.floats(pixels, 2, ImageLayout.CHANNELS_LAST)
        assertNotEquals(first.toList(), last.toList())
    }

    @Test
    fun `the quantised path keeps the bytes as they are`() {
        val values = ClipImagePreprocess.bytes(intArrayOf(argb(255, 128, 0)), 1, ImageLayout.CHANNELS_LAST)
        assertEquals(3, values.size)
        assertEquals(255.toByte(), values[0])
        assertEquals(128.toByte(), values[1])
        assertEquals(0.toByte(), values[2])
    }

    @Test
    fun `a white pixel normalises to the value CLIP was trained on`() {
        val values = ClipImagePreprocess.floats(intArrayOf(argb(255, 255, 255)), 1, ImageLayout.CHANNELS_FIRST)
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
        val values = ClipImagePreprocess.floats(intArrayOf(argb(0, 0, 0)), 1, ImageLayout.CHANNELS_FIRST)
        values.forEach { assertTrue("expected a negative value, got $it", it < 0f) }
    }

    @Test
    fun `the scale is zero to one, not zero to 255`() {
        // Feeding 0..255 instead of 0..1 is the classic version of this bug and
        // it makes every vector enormous and useless.
        val white = ClipImagePreprocess.floats(intArrayOf(argb(255, 255, 255)), 1, ImageLayout.CHANNELS_FIRST)
        val mid = ClipImagePreprocess.floats(intArrayOf(argb(128, 128, 128)), 1, ImageLayout.CHANNELS_FIRST)
        assertTrue("a white pixel must be small and positive, got ${white[0]}", white[0] < 3f)
        assertTrue("mid grey must be near zero, got ${mid[0]}", kotlin.math.abs(mid[0]) < 0.2f)
    }

    @Test
    fun `alpha is ignored rather than read as colour`() {
        val opaque = ClipImagePreprocess.floats(
            intArrayOf(0xFF shl 24 or (10 shl 16) or (20 shl 8) or 30),
            1,
            ImageLayout.CHANNELS_FIRST,
        )
        val transparent = ClipImagePreprocess.floats(
            intArrayOf(0x00 shl 24 or (10 shl 16) or (20 shl 8) or 30),
            1,
            ImageLayout.CHANNELS_FIRST,
        )
        assertTrue("alpha must not change the values", opaque.contentEquals(transparent))
    }
}

/**
 * Which tower is which.
 *
 * The repository names its files `first` and `last`, and the export does not
 * always keep the tensor names — a text tower can arrive with an input called
 * `args_0` holding floats. That combination is what made the app refuse a model
 * that was perfectly good, so the decision table is tested directly.
 */
class TowerClassificationTest {

    private fun input(name: String, type: String, vararg shape: Int) =
        InputSpec(name = name, dataType = type, shape = shape.toList().toIntArray())

    @Test
    fun `a four dimensional float input is a picture`() {
        assertEquals(
            TowerRole.IMAGE,
            ClipEncoder.classify(listOf(input("serving_default_args_0", "FLOAT32", 1, 3, 224, 224))),
        )
        assertEquals(
            TowerRole.IMAGE,
            ClipEncoder.classify(listOf(input("pixel_values", "FLOAT32", 1, 224, 224, 3))),
        )
    }

    @Test
    fun `a token sequence cast to floats is still text`() {
        // This is the case that failed: the exporter cast the ids to float32 and
        // named the input args_0, so nothing about it said "tokens", and the
        // app refused a model that was fine.
        assertEquals(
            TowerRole.TEXT,
            ClipEncoder.classify(listOf(input("serving_default_args_0", "FLOAT32", 1, 77))),
        )
        assertEquals(
            TowerRole.TEXT,
            ClipEncoder.classify(listOf(input("args_0", "FLOAT32", 1, 77, 512))),
        )
    }

    @Test
    fun `integer inputs are token ids, whatever they are called`() {
        assertEquals(TowerRole.TEXT, ClipEncoder.classify(listOf(input("args_0", "INT32", 1, 77))))
    }

    @Test
    fun `names are believed when they are there`() {
        assertEquals(TowerRole.TEXT, ClipEncoder.classify(listOf(input("input_ids", "INT32", 1, 77))))
    }

    @Test
    fun `a pair of inputs is a text tower with a mask`() {
        assertEquals(
            TowerRole.TEXT,
            ClipEncoder.classify(
                listOf(
                    input("ids", "FLOAT32", 1, 77),
                    input("mask", "FLOAT32", 1, 77),
                )
            ),
        )
    }

    @Test
    fun `something unrecognisable says so instead of guessing`() {
        assertEquals(TowerRole.UNKNOWN, ClipEncoder.classify(emptyList()))
    }

    @Test
    fun `a model carrying both towers is read as both, not as one of them`() {
        // This is the shape the shipped model really has. Treating it as two
        // single-tower files made both of them classify as IMAGE and the engine
        // reported "the model's inputs are not what this app expects".
        val (role, image, text) = ClipEncoder.resolveInputs(
            listOf(
                input("serving_default_args_0", "FLOAT32", 1, 3, 256, 256),
                input("serving_default_args_1", "INT64", 1, 77),
            ),
        )
        assertEquals(TowerRole.BOTH, role)
        assertEquals(0, image)
        assertEquals(1, text)
    }

    @Test
    fun `an int64 token input is still tokens, and is not mistaken for the picture`() {
        val (role, image, text) = ClipEncoder.resolveInputs(
            listOf(
                input("x", "FLOAT32", 1, 3, 224, 224),
                input("args_1", "INT64", 1, 77),
            ),
        )
        assertEquals(TowerRole.BOTH, role)
        assertEquals(0, image)
        assertEquals(1, text)
    }

    @Test
    fun `a single tower file still resolves to one role`() {
        val (imageRole, image, text) = ClipEncoder.resolveInputs(
            listOf(input("pixel_values", "FLOAT32", 1, 3, 224, 224)),
        )
        assertEquals(TowerRole.IMAGE, imageRole)
        assertEquals(0, image)
        assertEquals(-1, text)

        val (textRole, textImage, textInput) = ClipEncoder.resolveInputs(
            listOf(input("input_ids", "INT32", 1, 77)),
        )
        assertEquals(TowerRole.TEXT, textRole)
        assertEquals(-1, textImage)
        assertEquals(0, textInput)
    }

    @Test
    fun `a dual model reports itself as dual so both paths use one interpreter`() {
        val info = TowerInfo(
            role = TowerRole.BOTH,
            inputs = listOf(
                input("args_0", "FLOAT32", 1, 3, 256, 256),
                input("args_1", "INT64", 1, 77),
            ),
            outputSize = 512,
            imageInput = 0,
            textInput = 1,
        )
        assertTrue("a dual model must be usable", info.usable)
        assertTrue("a dual model must be recognised as dual", info.dual)
        // The picture side still drives the size and the layout.
        assertEquals(256, info.imageSide)
        assertEquals(ImageLayout.CHANNELS_FIRST, info.layout)
    }

    @Test
    fun `an int64 tensor is described as int64, not as a generic integer`() {
        // The byte width is decided from this string: int64 tokens written as
        // int32 are a buffer half the size the tensor wants.
        assertTrue(input("args_1", "INT64", 1, 77).describe().contains("int64"))
    }

    @Test
    fun `the layout and the side are read from the shape, not assumed`() {
        val channelsFirst = TowerInfo(
            role = TowerRole.IMAGE,
            inputs = listOf(input("x", "FLOAT32", 1, 3, 256, 256)),
            outputSize = 512,
        )
        val channelsLast = TowerInfo(
            role = TowerRole.IMAGE,
            inputs = listOf(input("x", "FLOAT32", 1, 224, 224, 3)),
            outputSize = 512,
        )
        assertEquals(ImageLayout.CHANNELS_FIRST, channelsFirst.layout)
        assertEquals(ImageLayout.CHANNELS_LAST, channelsLast.layout)
        assertEquals(256, channelsFirst.imageSide)
        assertEquals(224, channelsLast.imageSide)
    }
}
