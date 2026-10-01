package com.fileflow.app.core.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokenizer is the part that fails silently, so it is tested against a
 * vocabulary small enough to hold in the test and exact enough to prove the
 * behaviour: the markers, the padding, the word pattern, the byte table and the
 * merges themselves.
 *
 * A wrong tokenizer does not crash. It produces a vector, and the vector still
 * ranks photos, so the feature looks like a model that is merely mediocre when
 * it is really a model being read the wrong words.
 */
class ClipTokenizerTest {

    private val bos = 100
    private val eos = 101
    private val unknown = 102

    /**
     * The merges that build the word-final marker out of its characters.
     *
     * CLIP's real merge table contains them, and without them the marker is five
     * separate symbols and every word ends in garbage — which is exactly the
     * kind of mistake this file exists to catch, so the test vocabulary has to
     * contain them too.
     */
    private val markerMerges = listOf("< /", "</ w", "</w >")

    /** A hand-built vocabulary: the markers, the bytes of "ab", and the merges. */
    private fun tokenizer(vararg merges: String): ClipTokenizer {
        val vocab = mutableMapOf(
            "<|startoftext|>" to bos,
            "<|endoftext|>" to eos,
            "<unk>" to unknown,
        )
        var next = 200
        // Every byte the table can map, as single-character tokens, which is
        // what the real vocabulary does too.
        ClipTokenizer.byteEncode("abcdefghijklmnopqrstuvwxyz").forEach { char ->
            vocab.putIfAbsent(char.toString(), next++)
        }
        // The marker's own characters, so the merges above have something to
        // start from.
        listOf("<", "/", "w", ">").forEach { vocab.putIfAbsent(it, next++) }
        val ids = vocab.values.max() + 1
        vocab["</w>"] = ids
        vocab["ab"] = ids + 1
        vocab["ab</w>"] = ids + 2
        vocab["abc"] = ids + 3
        return ClipTokenizer(vocab, merges.toList() + markerMerges)
    }

    @Test
    fun `every byte maps to a character, even the control bytes`() {
        // The table has to cover all 256 bytes exactly once: a byte with no
        // entry would drop a character out of a word, and two bytes sharing an
        // entry would make two different words tokenise the same.
        val table = ClipTokenizer.BYTE_TO_UNICODE
        assertEquals("every byte needs an entry", 256, table.size)
        assertEquals("no two bytes may share a character", 256, table.values.toSet().size)
        assertEquals("byte 0 is the first character above the printable range", 256, table[0]!!.code)
        // The space is 'Ġ' in CLIP's vocabulary, which is byte 32 under this
        // table; getting that wrong breaks the first merge of every word.
        assertEquals('\u0120', table[32])
        assertEquals('a', table[97])
    }

    @Test
    fun `a character outside ascii becomes its utf8 bytes, not one character`() {
        // "é" is two bytes in UTF-8 and therefore two tokens' worth of input.
        assertEquals(2, ClipTokenizer.byteEncode("é").length)
        assertEquals(1, ClipTokenizer.byteEncode("a").length)
    }

    @Test
    fun `a word is wrapped in the markers and padded to the window`() {
        // With no merges: bos, a, b, the word-final marker, eos, then zeros.
        val tokens = tokenizer().encode("ab", length = 8)
        assertEquals(8, tokens.size)
        assertEquals(bos, tokens[0])
        assertEquals(eos, tokens[4])
        assertTrue("padding must be zeros", tokens.drop(5).all { it == 0 })
    }

    @Test
    fun `the highest ranked merge is applied first`() {
        // "ab" ranks before "abc", so a-b merges into one token and the pair
        // (ab, c) never exists to be merged again.
        val withBoth = tokenizer("a b", "ab c")
        val tokens = withBoth.encode("abc", length = 6)
        // bos, "ab", then whatever "c" is, then eos.
        assertEquals(4, tokens.count { it != 0 })
    }

    @Test
    fun `text is lower cased and whitespace collapsed`() {
        val lower = tokenizer().encode("AB", length = 8)
        val upper = tokenizer().encode("ab", length = 8)
        assertTrue("case must not change the tokens", lower.contentEquals(upper))

        val spaced = tokenizer().encode("  ab  ", length = 8)
        assertTrue("extra whitespace must not change the tokens", spaced.contentEquals(upper))
    }

    @Test
    fun `an unknown character becomes the unknown token rather than vanishing`() {
        // A disappearing character would silently shift every token after it.
        val tokens = tokenizer().encode("a7b", length = 8)
        assertTrue("expected the unknown token for the digit", tokens.contains(unknown))
    }

    @Test
    fun `the window is never longer than the model's context`() {
        val long = "a".repeat(500)
        val tokens = tokenizer().encode(long)
        assertEquals(ClipTokenizer.CONTEXT_LENGTH, tokens.size)
        assertEquals(eos, tokens[ClipTokenizer.CONTEXT_LENGTH - 1])
    }

    @Test
    fun `a contraction stays one word the way CLIP's pattern says`() {
        // CLIP keeps "'s" attached; splitting it would be a different token
        // sequence from the one the model was trained on.
        val encoded = ClipTokenizer.byteEncode("dog's")
        assertTrue("the apostrophe must survive encoding", encoded.contains("'"))
    }
}
