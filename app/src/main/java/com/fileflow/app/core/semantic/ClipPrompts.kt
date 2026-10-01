package com.fileflow.app.core.semantic

import com.fileflow.app.core.vision.ThemeVocabulary

/**
 * What actually gets sent to the text tower.
 *
 * This is the single biggest accuracy lever in the whole feature, and it is not
 * a model problem. MobileCLIP2 was trained on English captions, so a query
 * typed in Indonesian is a string the text tower has never seen in its life:
 * "kerja bakti" is not a rare phrase, it is an *absent* one. Sending it raw and
 * blaming the model would be blaming the wrong thing.
 *
 * So a query becomes English prompts here, three ways, in order of how much
 * they are trusted:
 *
 * 1. A hand-written prompt for a phrase people actually search for — "kerja
 *    bakti" becomes "people doing community work together", which is a sentence
 *    the model was trained on.
 * 2. Failing that, the labels the phrase maps to in [ThemeVocabulary] become
 *    prompts ("a photo of a team"), which connects the Indonesian table that
 *    already exists to the model's own vocabulary instead of duplicating it.
 * 3. The query itself is always included last, so an English query — or one
 *    whose Indonesian phrasing happens to work — is never made worse.
 *
 * And every prompt is expanded through a few templates, because CLIP's own
 * research found that "a photo of X" retrieves better than "X" alone. The
 * resulting vectors are averaged, which is the standard prompt-ensembling trick
 * and costs a few milliseconds of text-tower time.
 */
object ClipPrompts {

    /** Phrases worth a hand-written prompt, because people search for them. */
    private val HAND_WRITTEN: Map<String, List<String>> = mapOf(
        "kerja bakti" to listOf(
            "a photo of people doing community work together",
            "a photo of a group of neighbours cleaning the street",
            "a photo of people working together outdoors with tools",
        ),
        "gotong royong" to listOf(
            "a photo of people working together to help each other",
            "a photo of a community working together outdoors",
        ),
        "bukti transfer" to listOf(
            "a photo of a bank transfer receipt",
            "a screenshot of a money transfer confirmation",
            "a photo of a payment confirmation on a phone screen",
        ),
        "transfer" to listOf(
            "a photo of a bank transfer receipt",
            "a screenshot of a money transfer confirmation",
        ),
        "struk" to listOf("a photo of a paper receipt", "a photo of a shopping receipt"),
        "nota" to listOf("a photo of a paper receipt", "a photo of a handwritten note"),
        "kwitansi" to listOf("a photo of a payment receipt on paper"),
        "ktp" to listOf("a photo of an identity card", "a photo of a plastic identity card"),
        "screenshot" to listOf("a screenshot of a phone screen", "a screenshot of an app"),
        "tangkapan layar" to listOf("a screenshot of a phone screen"),
        "dokumen" to listOf("a photo of a document on paper", "a scanned document"),
        "rapat" to listOf("a photo of a meeting in an office", "a photo of people at a table working"),
        "kantor" to listOf("a photo of an office room", "a photo of a desk with a computer"),
        "sekolah" to listOf("a photo of a school classroom", "a photo of children at school"),
        "acara" to listOf("a photo of a social event", "a photo of people at a gathering"),
        "pesta" to listOf("a photo of a party with people celebrating"),
        "makanan" to listOf("a photo of food on a plate", "a photo of a meal on a table"),
        "minuman" to listOf("a photo of a drink", "a photo of a glass of water"),
        "kue" to listOf("a photo of a cake", "a photo of a slice of cake on a plate"),
        "alam" to listOf("a photo of nature scenery", "a photo of a natural landscape"),
        "pemandangan" to listOf("a photo of a scenic view", "a photo of a landscape"),
        "gunung" to listOf("a photo of a mountain", "a photo of hills in the distance"),
        "pantai" to listOf("a photo of a beach", "a photo of the sea shore"),
        "kebun" to listOf("a photo of a garden with plants", "a photo of a field of crops"),
        "bunga" to listOf("a photo of flowers", "a photo of a flower close up"),
        "hewan" to listOf("a photo of an animal"),
        "kucing" to listOf("a photo of a cat", "a photo of a kitten at home"),
        "anjing" to listOf("a photo of a dog"),
        "anak" to listOf("a photo of a child", "a photo of children playing"),
        "keluarga" to listOf("a photo of a family together", "a photo of family members posing"),
        "kendaraan" to listOf("a photo of a vehicle", "a photo of traffic on a road"),
        "mobil" to listOf("a photo of a car", "a photo of a car parked on a street"),
        "motor" to listOf("a photo of a motorcycle"),
        "bangunan" to listOf("a photo of a building", "a photo of a building from outside"),
        "rumah" to listOf("a photo of a house", "a photo of the inside of a home"),
        "masjid" to listOf("a photo of a mosque", "a photo of a mosque with a dome"),
        "jalan" to listOf("a photo of a road", "a photo of a street with traffic"),
        "toko" to listOf("a photo of a shop", "a photo of goods in a store"),
        "pasar" to listOf("a photo of a market with stalls", "a photo of vegetables at a market"),
    )

    /** CLIP's templates: a bare noun retrieves visibly worse than a sentence. */
    private val TEMPLATES = listOf(
        "a photo of %s",
        "%s",
        "a picture of %s",
    )

    /** Enough to help, few enough to stay instant. */
    private const val MAX_PROMPTS = 8

    /** The prompts to average for [query]. Never empty for a non-blank query. */
    fun promptsFor(query: String): List<String> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()

        val explicit = HAND_WRITTEN[needle]
            ?: phraseMatch(needle)
        val derived = if (explicit == null) derivedFromLabels(needle) else emptyList()

        val seeds = buildList {
            explicit?.let { addAll(it) }
            addAll(derived)
            // Last, always: an English query already works, and a phrase we have
            // no entry for is no worse off than it was before.
            add(needle)
        }

        return seeds
            .distinct()
            .flatMap { seed ->
                // An already-sentence-shaped seed does not need "a photo of".
                if (seed.split(' ').size > 3) listOf(seed) else TEMPLATES.map { it.format(seed) }
            }
            .distinct()
            .take(MAX_PROMPTS)
    }

    /**
     * Whole words and phrases, never substrings: "malam" must not match "alam",
     * for the same reason it must not in the label table.
     */
    private fun phraseMatch(needle: String): List<String>? {
        val words = needle.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val phrases = buildSet {
            addAll(words)
            words.windowed(2).forEach { add(it.joinToString(" ")) }
            words.windowed(3).forEach { add(it.joinToString(" ")) }
        }
        return HAND_WRITTEN.entries
            .filter { entry -> entry.key in phrases }
            .flatMap { it.value }
            .distinct()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * When there is no prompt written for a phrase, the labels it maps to are
     * the next best thing — and reusing that table keeps one place to fix when
     * a mapping is wrong instead of two.
     */
    private fun derivedFromLabels(needle: String): List<String> =
        ThemeVocabulary.labelsFor(needle)
            .take(4)
            .map { label -> "a photo of $label" }

    /** Whether a query has a hand-written prompt, for the screen to say. */
    fun isCurated(query: String): Boolean {
        val needle = query.trim().lowercase()
        return needle.isNotEmpty() && (HAND_WRITTEN.containsKey(needle) || phraseMatch(needle) != null)
    }
}
