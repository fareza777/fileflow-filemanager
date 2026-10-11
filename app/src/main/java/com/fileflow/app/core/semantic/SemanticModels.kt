package com.fileflow.app.core.semantic

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pieces the semantic tier needs, and where they come from.
 *
 * MobileCLIP gives an image and a phrase a place in the same space, so "kerja
 * bakti" can find a photo of people working together with no text in it at all
 * — which is the part labels cannot do and the reason this tier exists. It
 * costs hundreds of megabytes, so every file here is a deliberate download the
 * user starts and can undo.
 *
 * The tokenizer files are not optional: the text tower takes token ids, and a
 * tokenizer that does not match the model produces confident nonsense. CLIP's
 * own vocabulary and merge list are the ones the model was trained with, so
 * they are downloaded alongside it rather than approximated.
 */
enum class SemanticAsset(
    val fileName: String,
    val url: String,
    /**
     * Roughly how big this is, for the sentence that asks permission.
     *
     * Deliberately *not* used to decide whether a download finished: the number
     * here is a guess, and a guess that is 15% too high turns a complete file
     * into a "truncated" one, which the download then asks for again past the
     * end of the file — and a server answers that with 416, forever. What counts
     * is what the server says: `X-Linked-Size`, or the total in `Content-Range`.
     */
    val approximateBytes: Long,
    val label: String,
) {
    /**
     * The whole model: a photo or a phrase in, a vector in the same space out.
     *
     * The repository publishes every model twice, as `..._first.tflite` and
     * `..._last.tflite`. Both carry the image tower *and* the text tower — the
     * same two inputs (`args_0` [1,3,256,256] and `args_1` [1,77] int64) and the
     * same two 512-dim outputs — and they are the same size to the byte, which
     * is exactly what makes the wrong one so easy to pick. The only difference
     * is which token position the text tower pools into its vector:
     *
     *   `_first` takes position 0, the start-of-text token;
     *   `_last` takes the end-of-text token, which is what CLIP was trained on.
     *
     * Measured on both files with this repository's own vocabulary, feeding the
     * identical token ids (`cat`, `dog`, an empty prompt and a corrupted id):
     *
     *   cos("a photo of a cat", "a photo of a dog")
     *       _first = 0.999   _last = 0.948
     *   cos("a photo of a cat", "a photo of a cat" with one token changed)
     *       _first = 0.999   _last = 0.910
     *   cos("a photo of a cat", "" )
     *       _first = 0.998   _last = 0.679
     *
     * Under `_first` the text tower answers almost the same vector for every
     * sentence, so every query ranks the photos in almost the same order and
     * the feature looks like it returns the same photo no matter what is typed.
     * The image tower is healthy either way. So the `_last` file is the only
     * one worth downloading, and its local name says so — the old
     * `mobileclip_s2_image.tflite` was the `_first` file and is wrong on disk,
     * which a new name settles instead of a compatibility check.
     */
    MODEL(
        fileName = "mobileclip_s2_last.tflite",
        url = "https://huggingface.co/anton96vice/mobileclip2_tflite/resolve/main/" +
            "mobileclip_s2_datacompdr_last.tflite",
        approximateBytes = 397L * 1024 * 1024,
        label = "MobileCLIP2-S2 (image + text)",
    ),

    /** CLIP's byte-pair vocabulary: token text to id. */
    VOCAB(
        fileName = "clip_vocab.json",
        url = "https://huggingface.co/openai/clip-vit-base-patch32/resolve/main/vocab.json",
        approximateBytes = 1_059_000L,
        label = "CLIP vocabulary",
    ),

    /** CLIP's merge rules, without which the tokenizer splits words wrongly. */
    MERGES(
        fileName = "clip_merges.txt",
        url = "https://huggingface.co/openai/clip-vit-base-patch32/resolve/main/merges.txt",
        approximateBytes = 525_000L,
        label = "CLIP merges",
    ),
    ;

    val megabytes: Long get() = (approximateBytes + 512 * 1024) / (1024 * 1024)
}

/**
 * Where the downloaded pieces live.
 *
 * A folder of its own under the app's files directory, so deleting the model is
 * deleting a folder and there is no state anywhere else to disagree with it.
 */
@Singleton
class SemanticModels internal constructor(
    private val root: File,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "semantic"))

    fun file(asset: SemanticAsset): File = File(root, asset.fileName)

    /** Which pieces are present and plausibly complete. */
    fun installed(): Set<SemanticAsset> = SemanticAsset.entries.filter { present(it) }.toSet()

    fun present(asset: SemanticAsset): Boolean {
        val file = file(asset)
        // A download stopped half-way leaves a file that exists and is useless;
        // the size is the cheapest honest test, and the shape check on load
        // catches anything that passes it and is still wrong.
        return file.isFile && file.length() > asset.approximateBytes / 2
    }

    /** True when the whole tier can run. */
    fun ready(): Boolean = installed().containsAll(SemanticAsset.entries)

    /** Bytes on disk, for the screen that offers to delete it. */
    fun sizeOnDisk(): Long = SemanticAsset.entries.sumOf { asset ->
        file(asset).takeIf { it.isFile }?.length() ?: 0L
    }

    fun delete() {
        root.listFiles()?.forEach { it.delete() }
    }

    fun ensureDirectory(): File = root.apply { mkdirs() }

    init {
        // Files this folder can hold that must not be there.
        //
        // `mobileclip_s2_text.tflite` is the second tower, which used to be a
        // separate download. It is the same model again, so anyone who fetched
        // it is carrying 378 MB that can never be used.
        //
        // `mobileclip_s2_image.tflite` is the same model with the wrong text
        // pooling — see [SemanticAsset.MODEL] — so it is worse than unused: it
        // is a model that runs, ranks and always answers nearly the same photo.
        // Both are removed rather than left to be mistaken for a missing file
        // on the next launch.
        if (root.isDirectory) {
            LEGACY_DUPLICATES.forEach { name -> File(root, name).takeIf { it.isFile }?.delete() }
        }
    }

    private companion object {
        val LEGACY_DUPLICATES = listOf(
            "mobileclip_s2_text.tflite",
            "mobileclip_s2_image.tflite",
        )
    }
}
