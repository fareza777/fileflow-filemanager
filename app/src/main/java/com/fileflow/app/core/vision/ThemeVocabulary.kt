package com.fileflow.app.core.vision

/**
 * The bridge between what someone types and what the model can say.
 *
 * The labelling model has a fixed vocabulary of about four hundred English
 * words, published in full, and no Indonesian at all. So "kerja bakti" cannot
 * be a model query — it has to be a *mapping*: the words that mean it. This
 * class is that mapping, written as a table rather than a model, because a
 * table can be read, argued with and corrected, and a wrong entry is a line
 * rather than a mystery.
 *
 * It is deliberately honest about its reach. A label is a category, not a
 * description: "Team, Community, Crowd" will find the group photo of the kerja
 * bakti and also the group photo of a wedding. What makes the search *accurate*
 * is the other half — the text recognised inside the photo — so a receipt with
 * "transfer" printed on it is found exactly, and only the label half is
 * approximate.
 */
object ThemeVocabulary {

    /**
     * Query words to model labels, both lowercase.
     *
     * The label spellings are the ones the model actually emits; anything not
     * in its list would be a query that can never match, so this table is
     * checked against [KNOWN_LABELS] by a test.
     */
    private val THEMES: Map<String, List<String>> = mapOf(
        // Documents and money — the receipts people actually look for.
        "bukti transfer" to listOf("receipt", "money", "paper", "screenshot", "mobile phone", "web page"),
        "transfer" to listOf("receipt", "money", "paper", "screenshot", "mobile phone"),
        "struk" to listOf("receipt", "paper", "money"),
        "nota" to listOf("receipt", "paper", "money"),
        "rekening" to listOf("receipt", "money", "paper"),
        "uang" to listOf("money"),
        "dokumen" to listOf("paper", "receipt", "news", "newspaper", "passport", "screenshot", "web page", "whiteboard", "blackboard", "menu"),
        "tulisan" to listOf("paper", "screenshot", "whiteboard", "blackboard", "news", "newspaper", "poster"),
        "screenshot" to listOf("screenshot", "web page", "mobile phone"),

        // Work and community — the "kerja bakti" case.
        "kerja bakti" to listOf("team", "community", "crew", "crowd", "interaction", "construction", "person", "field"),
        "gotong royong" to listOf("team", "community", "crew", "interaction", "construction", "person"),
        "kerja" to listOf("team", "construction", "crew", "interaction", "product", "presentation"),
        "kantor" to listOf("desk", "computer", "presentation", "room", "team"),
        "sekolah" to listOf("school", "class", "blackboard", "whiteboard", "graduation", "mortarboard"),
        "acara" to listOf("event", "party", "concert", "carnival", "graduation", "marriage", "presentation", "ballroom"),
        "rapat" to listOf("team", "presentation", "interaction", "desk", "computer"),
        "keluarga" to listOf("person", "interaction", "grandparent", "baby", "crowd", "smile"),
        "orang" to listOf("person", "crowd", "interaction", "selfie", "smile", "standing"),
        "selfie" to listOf("selfie", "person", "smile"),

        // Food.
        "makanan" to listOf("food", "cuisine", "meal", "fast food", "lunch", "supper", "bento", "cookware and bakeware", "tableware"),
        "makan" to listOf("eating", "food", "meal", "lunch", "supper", "cuisine"),
        "kue" to listOf("cake", "cookie", "icing", "gelato", "pie"),
        "minuman" to listOf("coffee", "cappuccino", "juice", "cola", "wine", "cup"),

        // Nature and places.
        "alam" to listOf("mountain", "forest", "beach", "lake", "river", "sky", "sunset", "waterfall", "field", "garden", "prairie"),
        "gunung" to listOf("mountain", "cliff", "canyon", "sky", "field"),
        "pantai" to listOf("beach", "sand", "pier", "sunset", "swimming", "sailboat"),
        "kebun" to listOf("garden", "plant", "flower", "farm", "field", "flowerpot"),
        "bunga" to listOf("flower", "petal", "plant", "flora", "garden"),
        "hewan" to listOf("dog", "cat", "bird", "horse", "cattle", "duck", "insect", "pet", "butterfly", "turtle"),
        "kucing" to listOf("cat", "pet", "ragdoll", "sphynx", "pixie-bob"),
        "anjing" to listOf("dog", "pet", "dalmatian", "basset hound"),
        "langit" to listOf("sky", "sunset", "moon", "star", "fog", "aurora"),

        // Transport.
        "kendaraan" to listOf("vehicle", "car", "bus", "train", "motorcycle", "bicycle", "van", "airplane", "boat"),
        "mobil" to listOf("car", "vehicle", "tire", "windshield", "road"),
        "motor" to listOf("motorcycle", "vehicle", "helmet", "road"),
        "sepeda" to listOf("bicycle", "cycling", "wheel", "helmet"),

        // Buildings and interiors.
        "bangunan" to listOf("building", "skyscraper", "tower", "infrastructure", "monument", "bridge", "construction"),
        "rumah" to listOf("building", "roof", "room", "cabinetry", "garden", "wall"),
        "masjid" to listOf("mosque", "temple", "building", "palace", "cathedral"),
        "interior" to listOf("room", "bedroom", "kitchen", "bathroom", "desk", "chair", "couch", "shelf", "cabinetry"),
        "dapur" to listOf("kitchen", "cookware and bakeware", "countertop", "food"),

        // Sport and outdoors.
        "olahraga" to listOf("sports", "competition", "running", "soccer", "badminton", "cycling", "swimming", "marathon", "gymnastics"),
        "liburan" to listOf("vacation", "leisure", "beach", "camping", "picnic", "backpacking", "park", "safari"),
    )

    /**
     * Every label the default model can emit, lowercased.
     *
     * Written out so a theme that names a label the model does not have fails a
     * test instead of failing silently at search time — the failure mode being
     * a query that can never match anything.
     */
    val KNOWN_LABELS: Set<String> = setOf(
        "team", "bonfire", "comics", "himalayan", "iceberg", "bento", "sink", "toy", "statue",
        "cheeseburger", "tractor", "sled", "aquarium", "circus", "sitting", "beard", "bridge",
        "tights", "bird", "rafting", "park", "factory", "graduation", "porcelain", "twig", "petal",
        "cushion", "sunglasses", "infrastructure", "ferris wheel", "pomacentridae", "wetsuit",
        "shetland sheepdog", "brig", "watercolor paint", "competition", "cliff", "badminton",
        "safari", "bicycle", "stadium", "boat", "smile", "surfboard", "fast food", "sunset",
        "hot dog", "shorts", "bus", "bullfighting", "sky", "gerbil", "rock", "interaction",
        "dress", "toe", "bear", "eating", "tower", "brick", "junk", "person", "windsurfing",
        "swimwear", "roller", "camping", "playground", "bathroom", "laugh", "balloon", "concert",
        "prom", "construction", "product", "reef", "picnic", "wreath", "wheelbarrow", "boxer",
        "necklace", "bracelet", "casino", "windshield", "stairs", "computer",
        "cookware and bakeware", "monochrome", "chair", "poster", "bar", "shipwreck", "pier",
        "community", "caving", "cave", "tie", "cabinetry", "underwater", "clown", "nightclub",
        "cycling", "comet", "mortarboard", "track", "christmas", "church", "clock", "dude",
        "cattle", "jungle", "desk", "curling", "cuisine", "cat", "juice", "couscous", "screenshot",
        "crew", "skyline", "stuffed toy", "cookie", "tile", "hanukkah", "crochet", "skateboarder",
        "clipper", "nail", "cola", "cutlery", "menu", "sari", "plush", "pocket", "neon", "icicle",
        "pasteles", "chain", "dance", "dune", "santa claus", "thanksgiving", "tuxedo", "mouth",
        "desert", "dinosaur", "mufti", "fire", "bedroom", "goggles", "dragon", "couch", "sledding",
        "cap", "whiteboard", "hat", "gelato", "cavalier", "beanie", "jersey", "scarf", "vacation",
        "pitch", "blackboard", "deejay", "monument", "bumper", "longboard", "waterfowl", "flesh",
        "net", "icing", "dalmatian", "speedboat", "trunk", "coffee", "soccer", "ragdoll", "food",
        "standing", "fiction", "fruit", "pho", "sparkler", "presentation", "swing", "cairn terrier",
        "forest", "flag", "frigate", "foot", "jacket", "pillow", "bathing", "glacier", "gymnastics",
        "ear", "flora", "shell", "grandparent", "ruins", "eyelash", "bunk bed", "balance",
        "backpacking", "horse", "glitter", "saucer", "hair", "miniature", "crowd", "curtain",
        "icon", "pixie-bob", "herd", "insect", "ice", "bangle", "flap", "jewellery", "knitting",
        "centrepiece", "outerwear", "love", "muscle", "motorcycle", "money", "mosque", "tableware",
        "ballroom", "kayak", "leisure", "receipt", "lake", "lighthouse", "bridle", "leather",
        "horn", "strap", "lego", "scuba diving", "leggings", "pool", "musical instrument",
        "musical", "metal", "moon", "blazer", "marriage", "mobile phone", "militia", "tablecloth",
        "party", "nebula", "news", "newspaper", "piano", "plant", "passport", "penguin", "shikoku",
        "palace", "doily", "polo", "paper", "pop music", "skiff", "pizza", "pet", "quilting",
        "cage", "skateboard", "surfing", "rugby", "lipstick", "river", "race", "rowing", "road",
        "running", "room", "roof", "star", "sports", "shoe", "tubing", "space", "sleep", "skin",
        "swimming", "school", "sushi", "loveseat", "superman", "cool", "skiing", "submarine",
        "song", "class", "skyscraper", "volcano", "television", "rein", "tattoo", "train",
        "handrail", "cup", "vehicle", "handbag", "lampshade", "event", "wine", "wing", "wheel",
        "wakeboarding", "web page", "ranch", "fishing", "heart", "cotton", "cappuccino", "bread",
        "sand", "museum", "helicopter", "mountain", "duck", "soil", "turtle", "crocodile",
        "musician", "sneakers", "wool", "ring", "singer", "carnival", "snowboarding",
        "waterskiing", "wall", "rocket", "countertop", "beach", "rainbow", "branch", "moustache",
        "garden", "gown", "field", "dog", "superhero", "flower", "placemat", "subwoofer",
        "cathedral", "building", "airplane", "fur", "bull", "bench", "temple", "butterfly",
        "model", "marathon", "needlework", "kitchen", "castle", "aurora", "larva", "racing",
        "airliner", "dam", "textile", "groom", "fun", "steaming", "vegetable", "unicycle", "jeans",
        "flowerpot", "drawer", "cake", "armrest", "aviation", "fog", "fireworks", "farm", "seal",
        "shelf", "bangs", "lightning", "van", "sphynx", "tire", "denim", "prairie", "snorkeling",
        "umbrella", "asphalt", "sailboat", "basset hound", "pattern", "supper", "veil", "waterfall",
        "lunch", "odometer", "baby", "glasses", "car", "aircraft", "hand", "rodeo", "canyon",
        "meal", "softball", "alcohol", "bride", "swamp", "pie", "bag", "joker", "supervillain",
        "army", "canoe", "selfie", "rickshaw", "barn", "archery", "aerospace engineering", "storm",
        "helmet",
    )

    /**
     * Indonesian names for the labels worth naming.
     *
     * Only the common ones are translated: a chip that says "Bento" in an
     * Indonesian list is fine, a chip that says "Cookware and bakeware" is not
     * worth a translation nobody asked for.
     */
    private val DISPLAY: Map<String, String> = mapOf(
        "receipt" to "Struk / bukti",
        "money" to "Uang",
        "paper" to "Kertas",
        "screenshot" to "Tangkapan layar",
        "mobile phone" to "Ponsel",
        "web page" to "Halaman web",
        "team" to "Tim",
        "community" to "Komunitas",
        "crew" to "Kru",
        "crowd" to "Keramaian",
        "interaction" to "Interaksi",
        "construction" to "Pembangunan",
        "person" to "Orang",
        "selfie" to "Selfie",
        "food" to "Makanan",
        "cuisine" to "Masakan",
        "meal" to "Hidangan",
        "dish" to "Hidangan",
        "fast food" to "Makanan cepat saji",
        "lunch" to "Makan siang",
        "supper" to "Makan malam",
        "cake" to "Kue",
        "coffee" to "Kopi",
        "cappuccino" to "Kopi susu",
        "juice" to "Jus",
        "mountain" to "Gunung",
        "forest" to "Hutan",
        "beach" to "Pantai",
        "lake" to "Danau",
        "river" to "Sungai",
        "sky" to "Langit",
        "sunset" to "Matahari terbenam",
        "waterfall" to "Air terjun",
        "field" to "Ladang",
        "garden" to "Kebun",
        "flower" to "Bunga",
        "flora" to "Tanaman",
        "plant" to "Tanaman",
        "dog" to "Anjing",
        "cat" to "Kucing",
        "bird" to "Burung",
        "horse" to "Kuda",
        "insect" to "Serangga",
        "vehicle" to "Kendaraan",
        "car" to "Mobil",
        "bus" to "Bus",
        "train" to "Kereta",
        "motorcycle" to "Motor",
        "bicycle" to "Sepeda",
        "airplane" to "Pesawat",
        "boat" to "Perahu",
        "building" to "Bangunan",
        "skyscraper" to "Gedung tinggi",
        "tower" to "Menara",
        "house" to "Rumah",
        "room" to "Ruangan",
        "bedroom" to "Kamar",
        "kitchen" to "Dapur",
        "bathroom" to "Kamar mandi",
        "desk" to "Meja kerja",
        "computer" to "Komputer",
        "school" to "Sekolah",
        "class" to "Kelas",
        "blackboard" to "Papan tulis",
        "whiteboard" to "Papan putih",
        "event" to "Acara",
        "party" to "Pesta",
        "concert" to "Konser",
        "graduation" to "Wisuda",
        "marriage" to "Pernikahan",
        "presentation" to "Presentasi",
        "sports" to "Olahraga",
        "competition" to "Perlombaan",
        "running" to "Berlari",
        "soccer" to "Sepak bola",
        "badminton" to "Bulu tangkis",
        "cycling" to "Bersepeda",
        "swimming" to "Berenang",
        "vacation" to "Liburan",
        "camping" to "Berkemah",
        "picnic" to "Piknik",
        "park" to "Taman",
        "baby" to "Bayi",
        "grandparent" to "Kakek-nenek",
        "mosque" to "Masjid",
        "temple" to "Kuil",
        "church" to "Gereja",
        "menu" to "Menu",
        "newspaper" to "Koran",
        "news" to "Berita",
        "passport" to "Paspor",
        "helmet" to "Helm",
    )

    /**
     * Every label any theme points at.
     *
     * Exposed so a test can hold the table to the model's published list: a
     * theme naming a label the model never emits is a query that returns
     * nothing, forever, and silently.
     */
    val themeLabels: Set<String> get() = THEMES.values.flatten().toSet()

    /** Indonesian phrase → the model labels that mean it. */
    fun labelsFor(query: String): List<String> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()

        THEMES[needle]?.let { return it }

        // Whole words and phrases of them, never substrings: matching inside a
        // word made "malam" find the "alam" theme, which is a photo search that
        // answers a question nobody asked.
        val words = needle.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val phrases = buildSet {
            addAll(words)
            words.windowed(2).forEach { add(it.joinToString(" ")) }
            words.windowed(3).forEach { add(it.joinToString(" ")) }
        }
        val fromThemes = THEMES.entries
            .filter { entry -> entry.key in phrases }
            .flatMap { it.value }
            .distinct()
        if (fromThemes.isNotEmpty()) return fromThemes

        // Last resort: the query may already be a label the model emits.
        return KNOWN_LABELS.filter { label -> label == needle }
    }

    /** The names to show for a photo's labels: Indonesian where we have it. */
    fun display(labels: List<String>): List<String> = labels.map { label ->
        DISPLAY[label] ?: label.replaceFirstChar { it.uppercase() }
    }

    /** Themes that would match a label — what the result row says it found. */
    fun themesFor(labels: List<String>): List<String> {
        if (labels.isEmpty()) return emptyList()
        val set = labels.toSet()
        return THEMES.entries
            .filter { entry -> entry.value.any { it in set } }
            .map { it.key }
            .sorted()
    }
}
