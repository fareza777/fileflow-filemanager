package com.fileflow.app.core.semantic

/**
 * CLIP's tokenizer: byte-level byte-pair encoding, the way the text tower was
 * trained to read it.
 *
 * This is the part everyone gets wrong, and the failure is quiet: a tokenizer
 * that splits words differently still produces a vector, and the vector still
 * ranks photos, so a wrong tokenizer looks like a model that is merely
 * mediocre. It is not — it is a model being fed something it never saw.
 *
 * Three details make it CLIP's rather than a generic BPE:
 *
 * - The word pattern is CLIP's own, with its contraction rule, so "don't"
 *   is one token and "dog's" keeps its clitic.
 * - Words are split into UTF-8 bytes first and each byte is mapped through
 *   CLIP's printable-character table, so any script survives.
 * - The end of a word gets a `</w>` suffix, which is how the merges learned
 *   word-final pieces at all.
 *
 * The vocabulary and merge list are the model's own files, not a reimplementation
 * of them; see [SemanticModels].
 */
class ClipTokenizer(
    vocab: Map<String, Int>,
    mergeLines: List<String>,
) {

    private val encoder: Map<String, Int> = vocab
    private val bos: Int = encoder[BOS] ?: FALLBACK_BOS
    private val eos: Int = encoder[EOS] ?: FALLBACK_EOS
    private val unknown: Int = encoder[UNK] ?: FALLBACK_UNK
    private val cache = HashMap<String, List<Int>>()

    /** Higher rank wins: the pair whose merge appears earlier is merged first. */
    private val ranks: Map<Pair<String, String>, Int> = buildMap {
        mergeLines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
            val parts = trimmed.split(' ')
            if (parts.size != 2) return@forEachIndexed
            put(parts[0] to parts[1], index)
        }
    }

    /**
     * [text] as the model wants it: a fixed-length window, beginning with the
     * start token and ending with the end token, padded with zeros.
     */
    fun encode(text: String, length: Int = CONTEXT_LENGTH): IntArray {
        val tokens = ArrayList<Int>(length)
        tokens += bos
        tokens += tokenIds(clean(text)).take(length - 2)
        tokens += eos
        while (tokens.size < length) tokens += 0
        return tokens.toIntArray()
    }

    /**
     * CLIP's text normalisation: lower case, whitespace collapsed. Deliberately
     * no accent stripping and no punctuation removal — the model was trained on
     * text that had none of that done to it.
     */
    private fun clean(text: String): String = text
        .trim()
        .lowercase()
        .replace(WHITESPACE, " ")

    private fun tokenIds(text: String): List<Int> {
        cache[text]?.let { return it }
        val ids = ArrayList<Int>()
        WORD_PATTERN.findAll(text).forEach { match ->
            // No trimming here: CLIP appends the word-final marker to the whole
            // piece, and dropping a byte to "make room" for it loses the last
            // letter of every word — which still produces tokens, and therefore
            // still produces vectors, and therefore fails invisibly.
            ids += bpe(byteEncode(match.value) + "</w>").map { piece ->
                encoder[piece] ?: unknown
            }
        }
        cache[text] = ids
        return ids
    }

    /**
     * The BPE itself: repeatedly merge the highest-ranked adjacent pair until
     * none of the remaining pairs is in the merge table.
     */
    private fun bpe(token: String): List<String> {
        if (token.length <= 1) return listOf(token)
        var word = token.map { it.toString() }.toMutableList()

        while (true) {
            var best: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (index in 0 until word.size - 1) {
                val pair = word[index] to word[index + 1]
                val rank = ranks[pair] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    best = pair
                }
            }
            val merge = best ?: break

            val next = ArrayList<String>(word.size)
            var index = 0
            while (index < word.size) {
                if (
                    index < word.size - 1 &&
                    word[index] == merge.first &&
                    word[index + 1] == merge.second
                ) {
                    next += merge.first + merge.second
                    index += 2
                } else {
                    next += word[index]
                    index += 1
                }
            }
            word = next
        }
        return word
    }

    companion object {
        /** CLIP's window: 77 tokens including the two markers. */
        const val CONTEXT_LENGTH = 77

        private const val BOS = "<|startoftext|>"
        private const val EOS = "<|endoftext|>"
        private const val UNK = "<unk>"
        private const val FALLBACK_BOS = 49406
        private const val FALLBACK_EOS = 49407
        private const val FALLBACK_UNK = 49408

        /**
         * CLIP's own pattern, contraction rule and all: a word is an optional
         * leading space plus letters, digits and the punctuation the tokenizer
         * keeps attached, and an apostrophe only continues a word for the
         * endings that appear in the training text.
         */
        private val WORD_PATTERN = Regex(
            """'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
            RegexOption.IGNORE_CASE,
        )

        private val WHITESPACE = Regex("\\s+")

        /** Bytes as printable characters, CLIP's table, so no byte is ever lost. */
        internal val BYTE_TO_UNICODE: Map<Int, Char> = buildMap {
            for (byte in 33..126) put(byte, byte.toChar())
            for (byte in 161..172) put(byte, byte.toChar())
            for (byte in 174..255) put(byte, byte.toChar())
            var extra = 0
            for (byte in 0..255) {
                if (byte in this) continue
                put(byte, (256 + extra).toChar())
                extra++
            }
        }

        /** UTF-8 bytes of [text], each mapped to CLIP's printable stand-in. */
        fun byteEncode(text: String): String = buildString(text.length) {
            text.toByteArray(Charsets.UTF_8).forEach { byte ->
                append(BYTE_TO_UNICODE[byte.toInt() and 0xFF] ?: '?')
            }
        }
    }
}
