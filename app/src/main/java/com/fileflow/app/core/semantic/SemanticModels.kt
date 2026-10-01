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
    val bytes: Long,
    val label: String,
) {
    /** The bigger tower: a photo in, a vector out. */
    IMAGE_TOWER(
        fileName = "mobileclip_s2_image.tflite",
        url = "https://huggingface.co/anton96vice/mobileclip2_tflite/resolve/main/" +
            "mobileclip_s2_datacompdr_first.tflite",
        bytes = 397L * 1024 * 1024,
        label = "Image encoder (MobileCLIP2-S2)",
    ),

    /** The other half: a phrase in, a vector in the same space out. */
    TEXT_TOWER(
        fileName = "mobileclip_s2_text.tflite",
        url = "https://huggingface.co/anton96vice/mobileclip2_tflite/resolve/main/" +
            "mobileclip_s2_datacompdr_last.tflite",
        bytes = 397L * 1024 * 1024,
        label = "Text encoder (MobileCLIP2-S2)",
    ),

    /** CLIP's byte-pair vocabulary: token text to id. */
    VOCAB(
        fileName = "clip_vocab.json",
        url = "https://huggingface.co/openai/clip-vit-base-patch32/resolve/main/vocab.json",
        bytes = 1_059_000L,
        label = "CLIP vocabulary",
    ),

    /** CLIP's merge rules, without which the tokenizer splits words wrongly. */
    MERGES(
        fileName = "clip_merges.txt",
        url = "https://huggingface.co/openai/clip-vit-base-patch32/resolve/main/merges.txt",
        bytes = 525_000L,
        label = "CLIP merges",
    ),
    ;

    val megabytes: Long get() = (bytes + 512 * 1024) / (1024 * 1024)
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
        return file.isFile && file.length() > asset.bytes / 2
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
}
