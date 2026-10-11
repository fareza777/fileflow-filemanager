package com.fileflow.app.core.semantic

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which model file is downloaded cannot be re-derived from this repository's
 * code: the two candidates published upstream have the same size, the same
 * tensor shapes and both towers. Only the pooled token position differs, and
 * choosing the wrong one does not fail — every query still returns a ranked
 * list, it is just nearly the same list each time.
 *
 * Measured with this repository's own vocabulary and tokenizer, feeding both
 * files the same token ids:
 *
 *   cos("a photo of a cat", "a photo of a dog")   _first 0.999   _last 0.948
 *   cos("a photo of a cat", "" )                  _first 0.998   _last 0.679
 *
 * so the URL below is pinned, and the local name is pinned to match it, so a
 * re-download is the only way to change either.
 */
class ModelAssetTest {

    @Test
    fun `the model is the last-token pooling file, not the first-token one`() {
        val url = SemanticAsset.MODEL.url
        assertTrue("pooling must be last-token: $url", url.endsWith("_last.tflite"))
        assertTrue("not the first-token file: $url", !url.endsWith("_first.tflite"))
    }

    @Test
    fun `the local name says which pooling the file has`() {
        // The local name is shorter than the remote one on purpose — it is what
        // the first download happened to call the file — but it must not lose
        // the part that tells the two candidates apart.
        assertTrue(
            "the file on disk must be recognisably the same variant",
            SemanticAsset.MODEL.fileName.endsWith("_last.tflite"),
        )
        assertTrue(
            "a model without a size cannot be checked for completeness",
            SemanticAsset.MODEL.approximateBytes > 0,
        )
    }
}
