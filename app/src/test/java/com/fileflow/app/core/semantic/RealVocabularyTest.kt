package com.fileflow.app.core.semantic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokenizer against the model's own vocabulary and merge table — the files
 * it is handed at runtime, not a hand-built miniature.
 *
 * [ClipTokenizerTest] proves the algorithm; this proves the *data* survives the
 * trip through the parser. That trip fails without a trace: a file that parses
 * to zero entries does not throw, it just routes every word to `<unk>`, and a
 * text tower handed the same string for every question answers every question
 * the same way. Nothing downstream can tell that apart from a model having
 * nothing to say — the search still returns a vector, still ranks photos, and
 * still comes back empty.
 */
class RealVocabularyTest {

    private val vocab: Map<String, Int> by lazy {
        SemanticEngine.parseVocab(resource("vocab.json"))
    }

    private val tokenizer: ClipTokenizer by lazy {
        ClipTokenizer(vocab, resource("merges.txt").readLines())
    }

    private fun resource(name: String): File {
        val url = checkNotNull(javaClass.getResource("/clip/$name")) {
            "test resource clip/$name is missing"
        }
        return File(url.toURI())
    }

    @Test
    fun `the real vocabulary parses to every token the model knows`() {
        // 49,403 of CLIP's entries carry a plain numeric id. A count anywhere
        // well below that means the pattern missed this file's actual shape,
        // and every word in every query would then become the unknown token.
        assertTrue("parsed ${vocab.size} of the vocabulary", vocab.size >= 49_000)
        assertEquals(49406, vocab["<|startoftext|>"])
        assertEquals(49407, vocab["<|endoftext|>"])
    }

    @Test
    fun `two different questions become two different token sequences`() {
        // The failure this guards is not a crash: it is two unrelated sentences
        // encoding identically, which is what a vocabulary that did not load
        // looks like from the outside.
        val receipt = tokenizer.encode("a photo of a bank transfer receipt").toList()
        val food = tokenizer.encode("a photo of food on a plate").toList()
        assertNotEquals(
            "different text must not tokenise the same",
            receipt,
            food,
        )
    }

    @Test
    fun `ordinary english never falls through to the unknown token`() {
        // Byte-level BPE covers every byte, so a correct vocabulary produces
        // the unknown token for *no* English sentence. Any hit at all is the
        // fingerprint of a vocabulary that did not parse.
        val unknown = vocab["<unk>"] ?: 49408
        val ids = tokenizer.encode("a photo of a bank transfer receipt")
        val missed = ids.count { it == unknown }
        assertEquals("unknown tokens in an ordinary English sentence", 0, missed)
    }

    @Test
    fun `an indonesian query is tokenised by bytes rather than rejected`() {
        // The query reaches the tower in Indonesian when it has no hand-written
        // prompt, and byte-level BPE still has to give it real ids.
        val unknown = vocab["<unk>"] ?: 49408
        val ids = tokenizer.encode("suasana yang aneh sekali")
        assertEquals("unknown tokens in an Indonesian sentence", 0, ids.count { it == unknown })
        assertNotEquals(
            "two Indonesian sentences must not tokenise the same",
            tokenizer.encode("suasana yang aneh sekali").toList(),
            tokenizer.encode("kerja bakti").toList(),
        )
    }
}
