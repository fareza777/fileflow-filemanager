package com.fileflow.app.core.semantic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the text tower is actually asked.
 *
 * The model was trained on English captions, so an Indonesian phrase typed into
 * the search box is a string it has never seen — not a rare one, an absent one.
 * These tests hold the two halves of that fix: a phrase people search for gets
 * a hand-written English prompt, and anything else still gets something usable
 * rather than the raw Indonesian.
 */
class ClipPromptsTest {

    @Test
    fun `kerja bakti becomes an english sentence, not the indonesian words`() {
        val prompts = ClipPrompts.promptsFor("kerja bakti")
        assertTrue("expected prompts, got $prompts", prompts.isNotEmpty())
        assertTrue(
            "the community-work prompt is missing from $prompts",
            prompts.any { it.contains("community work") },
        )
        // The raw Indonesian may be appended as a last resort, but it must not
        // be the only thing the model is asked.
        assertTrue(
            "at least two thirds of the prompts should be English sentences",
            prompts.count { it.contains("photo") || it.contains("people") } >= 2,
        )
    }

    @Test
    fun `bukti transfer becomes a receipt prompt`() {
        val prompts = ClipPrompts.promptsFor("bukti transfer")
        assertTrue("expected a receipt prompt in $prompts", prompts.any { it.contains("receipt") })
        assertTrue("expected a transfer prompt in $prompts", prompts.any { it.contains("transfer") })
    }

    @Test
    fun `a phrase we have no entry for falls back to the label table`() {
        // "sepeda" has no hand-written prompt; the label table maps it to
        // bicycles, which is a connection the model does understand.
        val prompts = ClipPrompts.promptsFor("sepeda")
        assertTrue("expected a bicycle prompt in $prompts", prompts.any { it.contains("bicycle") })
    }

    @Test
    fun `an english query is passed through unharmed`() {
        val prompts = ClipPrompts.promptsFor("a red car on a road")
        assertTrue("the query itself must survive in $prompts", "a red car on a road" in prompts)
    }

    @Test
    fun `prompts are sentences, because a bare noun retrieves worse`() {
        val prompts = ClipPrompts.promptsFor("kucing")
        assertTrue(
            "expected a templated prompt in $prompts",
            prompts.any { it.startsWith("a photo of") || it.startsWith("a picture of") },
        )
    }

    @Test
    fun `the list is bounded and free of duplicates`() {
        val prompts = ClipPrompts.promptsFor("kerja bakti")
        assertTrue("too many prompts to run per search: ${prompts.size}", prompts.size <= 8)
        assertTrue("duplicates waste the text tower's time", prompts.size == prompts.distinct().size)
    }

    @Test
    fun `an empty query asks nothing`() {
        assertTrue(ClipPrompts.promptsFor("   ").isEmpty())
        assertFalse(ClipPrompts.isCurated("   "))
    }

    @Test
    fun `a word is not a substring of another, here either`() {
        // The same trap the label table had: "malam" contains "alam".
        val night = ClipPrompts.promptsFor("malam")
        val nature = ClipPrompts.promptsFor("alam")
        assertFalse(
            "a night query must not be answered with the nature prompt",
            night.any { it.contains("nature") },
        )
        assertTrue(nature.any { it.contains("nature") || it.contains("landscape") })
    }

    @Test
    fun `the curated flag says which queries were translated`() {
        assertTrue(ClipPrompts.isCurated("kerja bakti"))
        assertTrue(ClipPrompts.isCurated("bukti transfer"))
        // A phrase counts as recognised when one of its words is known: that is
        // the point of matching phrases rather than whole strings.
        assertTrue(ClipPrompts.isCurated("kucing oranye lucu sekali"))
        assertFalse(ClipPrompts.isCurated("suasana yang aneh sekali"))
    }
}

/**
 * Averaging prompt vectors, including the case that would poison every score.
 */
class SemanticAverageTest {

    private fun unit(vararg values: Float): FloatArray {
        var sum = 0f
        values.forEach { sum += it * it }
        val length = kotlin.math.sqrt(sum)
        return FloatArray(values.size) { values[it] / length }
    }

    @Test
    fun `averaging one vector gives that vector back`() {
        val vector = unit(1f, 0f, 0f)
        val mean = SemanticEngine.averageNormalised(listOf(vector))
        assertNotNull(mean)
        assertTrue(mean!!.contentEquals(vector))
    }

    @Test
    fun `the average of two nearby vectors still points between them`() {
        val left = unit(1f, 0f, 0f)
        val right = unit(1f, 1f, 0f)
        val mean = SemanticEngine.averageNormalised(listOf(left, right))!!
        assertTrue("expected a positive x", mean[0] > 0f)
        assertTrue("expected a positive y, between the two", mean[1] > 0f)
        // Unit length, so cosine stays a dot product.
        val length = kotlin.math.sqrt(mean.sumOf { (it * it).toDouble() }).toFloat()
        assertTrue("the mean must be normalised, was $length", kotlin.math.abs(length - 1f) < 0.001f)
    }

    @Test
    fun `vectors that cancel out return null instead of a NaN`() {
        // Two opposite prompts average to zero; a zero length would divide into
        // NaN, and every NaN comparison is false, so the search would return
        // nothing at all rather than something wrong.
        val opposite = unit(-1f, 0f, 0f)
        val vector = unit(1f, 0f, 0f)
        assertNull(SemanticEngine.averageNormalised(listOf(vector, opposite)))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertNull(SemanticEngine.averageNormalised(emptyList()))
    }

    @Test
    fun `vectors of different sizes are refused rather than padded`() {
        assertNull(
            SemanticEngine.averageNormalised(
                listOf(FloatArray(512) { 1f }, FloatArray(768) { 1f }),
            ),
        )
    }
}
