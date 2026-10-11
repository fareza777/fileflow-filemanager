package com.fileflow.app.core.semantic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate

/** One input tensor, as the model declares it. */
data class InputSpec(
    val name: String,
    val dataType: String,
    val shape: IntArray,
) {
    val rank: Int get() = shape.size
    val isInteger: Boolean get() = dataType.contains("INT", ignoreCase = true)
    val isFloat: Boolean get() = dataType.contains("FLOAT", ignoreCase = true)

    /** How many values the model expects, for a single example. */
    val valueCount: Int get() = shape.fold(1) { total, size -> total * if (size > 0) size else 1 }

    fun describe(): String =
        "${name.ifBlank { "input" }} ${dataType.shortName()} ${shape.joinToString("×", "[", "]")}"

    private fun String.shortName(): String = when {
        contains("INT64", true) -> "int64"
        contains("INT32", true) -> "int32"
        contains("FLOAT32", true) -> "float32"
        contains("UINT8", true) -> "uint8"
        else -> lowercase()
    }
}

/**
 * What the model turned out to be, and how to feed it.
 *
 * [imageInput] and [textInput] are *indices into [inputs]* rather than roles
 * attached to separate files, because this export carries both towers in one
 * file. They are -1 when the model has no such input, which is what a
 * single-tower file looks like.
 */
data class TowerInfo(
    val role: TowerRole,
    val inputs: List<InputSpec>,
    val outputSize: Int,
    val imageInput: Int = -1,
    val textInput: Int = -1,
) {
    val usable: Boolean get() = role != TowerRole.UNKNOWN && outputSize > 0

    /** The image tensor, or the first input when the model has no image input. */
    val primary: InputSpec? get() = inputs.getOrNull(imageInput) ?: inputs.firstOrNull()

    /** The square an image tower wants, from its own shape. */
    val imageSide: Int
        get() {
            val shape = primary?.shape ?: return ClipImagePreprocess.INPUT_SIDE
            return shape.drop(1).filter { it > 0 }.maxOrNull() ?: ClipImagePreprocess.INPUT_SIDE
        }

    /**
     * Which axis carries the channels.
     *
     * Both layouts are four-dimensional floats, so this is read from the model:
     * a 3 in the second position is channels first, a 3 in the last is channels
     * last. Getting it wrong is not an error, it is a vector that means nothing.
     */
    val layout: ImageLayout
        get() {
            val shape = primary?.shape ?: return ImageLayout.CHANNELS_FIRST
            return when {
                shape.size == 4 && shape[3] == 3 -> ImageLayout.CHANNELS_LAST
                shape.size == 4 && shape[1] == 3 -> ImageLayout.CHANNELS_FIRST
                else -> ImageLayout.CHANNELS_FIRST
            }
        }

    /** True when one file holds both towers, so both go through one interpreter. */
    val dual: Boolean get() = imageInput >= 0 && textInput >= 0

    val inputSummary: String
        get() = inputs.joinToString(" · ") { it.describe() }
}

/**
 * Runs the model.
 *
 * The interpreter is created once and reused: loading a 400 MB model is the
 * expensive part, and doing it per photo would make indexing unusable. The
 * accelerator is tried first and the CPU is the fallback, because NNAPI is
 * faster where it works and refuses politely where it does not.
 *
 * The fallback has to be a *second attempt at building the interpreter*: a
 * refused delegate usually throws when the interpreter is created rather than
 * when the delegate is, and catching only the latter means the CPU path is
 * never reached — the model simply fails to open on exactly the phones least
 * able to afford a failure.
 */
class ClipEncoder private constructor(
    private val interpreter: Interpreter,
    val info: TowerInfo,
    /** True when this interpreter runs on the accelerator rather than the CPU. */
    val usesDelegate: Boolean = false,
) {

    private val lock = Any()

    /**
     * Why the last run failed, in the interpreter's own words.
     *
     * A run that throws and is turned into "no vector" is indistinguishable
     * from a photo the model cannot read, and the first says the model is
     * broken while the second is a photo to skip. Both were silently the same
     * null, which is how a model that fails on *every* photo looks exactly like
     * a library of unreadable photos: the pass completes, the count stays at
     * zero, and nothing anywhere says why. So the reason is kept here and the
     * caller decides what it means.
     */
    var runFailure: String? = null
        private set

    /**
     * Which output is the image embedding and which is the text one.
     *
     * The export names them `output_0`/`output_1` and nothing says which tower
     * fed which, so it is measured rather than assumed: the same picture is run
     * against two different token sequences, and the output that *changes* is
     * the text one. The image embedding cannot move, because its input did not.
     */
    private val outputs: Pair<Int, Int> by lazy { probeOutputs() }

    /** A photo, as a vector in the shared space, normalised to unit length. */
    fun embedImage(file: File): FloatArray? {
        val bitmap = decode(file) ?: return null
        return try {
            val vector = runImage(imageInput(bitmap))
            vector?.let { normalise(it) }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * A phrase, in the same space.
     *
     * The token ids are written in whatever type the model asked for. This
     * export asks for **int64**; writing int32 into it produces a buffer half
     * the size the tensor wants, which TFLite refuses — and a refused run that
     * is caught as "no vector" looks exactly like a phrase the model has never
     * heard of.
     */
    fun embedText(tokens: IntArray): FloatArray? {
        val spec = textSpec() ?: return null
        val vector = runText(tokensBuffer(spec, tokens))
        return vector?.let { normalise(it) }
    }

    fun close() {
        runCatching { interpreter.close() }
    }

    // ------------------------------------------------------------- internals

    private fun textSpec(): InputSpec? =
        info.inputs.getOrNull(info.textInput) ?: info.primary

    private fun imageSpec(): InputSpec? = info.inputs.getOrNull(info.imageInput)

    /**
     * Feeds a picture and reads the image embedding.
     *
     * A dual-tower file still needs the *other* input filled, even though its
     * result is thrown away: every tensor in a graph has to be fed.
     */
    private fun runImage(image: ByteBuffer): FloatArray? {
        if (!info.dual) {
            return guarded("image") {
                val output = Array(1) { FloatArray(info.outputSize) }
                synchronized(lock) { interpreter.run(image, output) }
                output[0]
            }
        }
        val text = blankTokens()
        val wanted = outputs.first
        val buffers = outputBuffers()
        return guarded("image") {
            synchronized(lock) {
                interpreter.runForMultipleInputsOutputs(arrayOf(image, text), buffers)
            }
            embedding(buffers, wanted)
        }
    }

    private fun runText(text: ByteBuffer): FloatArray? {
        if (!info.dual) {
            return guarded("text") {
                val output = Array(1) { FloatArray(info.outputSize) }
                synchronized(lock) { interpreter.run(text, output) }
                output[0]
            }
        }
        val image = blankImage()
        val wanted = outputs.second
        val buffers = outputBuffers()
        return guarded("text") {
            synchronized(lock) {
                interpreter.runForMultipleInputsOutputs(arrayOf(image, text), buffers)
            }
            embedding(buffers, wanted)
        }
    }

    /**
     * Runs one inference and remembers why it failed, if it did.
     *
     * Returning null is still correct for a single unreadable photo, but the
     * reason is kept so a failure on *every* photo can be told apart from a
     * library of unreadable ones — the difference between "skip this file" and
     * "the model does not work on this phone".
     */
    private fun guarded(tower: String, block: () -> FloatArray?): FloatArray? =
        runCatching { block() }
            .onFailure { error ->
                val detail = "$tower tower: ${error.javaClass.simpleName}: ${error.message}"
                runFailure = detail
                // A snackbar closes and the reason goes with it. A model that
                // refuses to run on this phone is worth one line in logcat,
                // where it survives long enough to be read.
                Log.e(TAG, "$detail\ninputs=${info.inputSummary} dual=${info.dual} " +
                    "outputCount=${interpreter.outputTensorCount} delegate=$usesDelegate", error)
            }
            .getOrNull()

    /**
     * Finds the two embedding outputs, once.
     *
     * Falls back to the positional order when the model exposes fewer than two
     * of them, because a single-tower file has one output and nothing to tell
     * apart.
     */
    private fun probeOutputs(): Pair<Int, Int> {
        val candidates = (0 until interpreter.outputTensorCount).filter { index ->
            val shape = interpreter.getOutputTensor(index).shape()
            shape.size == 2 && shape[0] == 1 && shape[1] == info.outputSize
        }
        if (candidates.size < 2) {
            val only = candidates.firstOrNull() ?: 0
            return only to only
        }

        val side = info.imageSide
        val pixels = IntArray(side * side)
        val image = imageBuffer(pixels)
        val tokens = textSpec() ?: return candidates[0] to candidates[1]
        val first = capture(image, tokensBuffer(tokens, intArrayOf(1, 2, 3)))
        val second = capture(image, tokensBuffer(tokens, intArrayOf(9, 9, 9, 9, 9)))
        if (first == null || second == null) return candidates[0] to candidates[1]

        // The output that moved is the text tower's; the one that held still is
        // the image tower's, because its input was the same picture twice.
        val text = candidates.firstOrNull { index ->
            val left = first[index] ?: return@firstOrNull false
            val right = second[index] ?: return@firstOrNull false
            !left.contentEquals(right)
        }
        val image2 = candidates.firstOrNull { index ->
            val left = first[index] ?: return@firstOrNull false
            val right = second[index] ?: return@firstOrNull false
            left.contentEquals(right)
        }
        val chosen = if (text != null && image2 != null && text != image2) {
            image2 to text
        } else {
            candidates[0] to candidates[1]
        }
        Log.i(TAG, "outputs: candidates=$candidates image=${chosen.first} text=${chosen.second} " +
            "fallback=${text == null || image2 == null}")
        return chosen
    }

    private fun capture(image: ByteBuffer, text: ByteBuffer): Map<Int, FloatArray>? = runCatching {
        val buffers = outputBuffers()
        synchronized(lock) {
            interpreter.runForMultipleInputsOutputs(arrayOf(image, text), buffers)
        }
        // Read back only the float outputs, so the probe compares vectors and
        // never a raw buffer whose byte order is the caller's problem.
        buffers.mapNotNull { (index, slot) ->
            readFloats(index, slot)?.let { index to it }
        }.toMap()
    }.getOrNull()

    /** One float embedding, read back out of an [outputBuffers] slot. */
    private fun embedding(buffers: Map<Int, Any>, index: Int): FloatArray? =
        readFloats(index, buffers[index])

    /**
     * One slot per output tensor, sized from the tensor itself.
     *
     * This model reports three outputs and only two of them are embeddings — the
     * third is a scalar. `runForMultipleInputsOutputs` is handed a *map*, so
     * every output gets a slot rather than leaving the interpreter to fill one
     * the app never allocated for.
     *
     * The slot is a `ByteBuffer`, not a typed array, and that is the whole
     * point. TensorFlowLite checks that a Java array's *rank* matches the
     * tensor's, not just its length: a tensor of `[1, 512]` refuses a flat
     * `FloatArray(512)` with "Cannot copy from a TensorFlowLite tensor … to a
     * Java object with shape [512]", because one is rank 2 and the other is
     * rank 1. A flat array of the right length is therefore wrong in the least
     * obvious way possible — right size, wrong shape.
     *
     * A direct `ByteBuffer` of exactly `numBytes()` sidesteps both checks: it
     * carries no shape to disagree about and no type to disagree about, so it
     * works for a `[1, 512]` float embedding and for a rank-0 scalar alike.
     * [readFloats] turns one back into the vector the rest of the app uses.
     */
    private fun outputBuffers(): MutableMap<Int, Any> {
        val buffers = LinkedHashMap<Int, Any>(interpreter.outputTensorCount)
        for (index in 0 until interpreter.outputTensorCount) {
            val tensor = interpreter.getOutputTensor(index)
            val bytes = tensor.numBytes()
            if (bytes <= 0) continue
            buffers[index] = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        }
        return buffers
    }

    /**
     * A float32 output, read back out of its [ByteBuffer] slot.
     *
     * @return null for an output that is not float32, holds no elements, or
     * whose slot is too small to hold it — all of which would otherwise mean
     * reading past the end of a buffer and calling the result a vector.
     */
    private fun readFloats(index: Int, slot: Any?): FloatArray? {
        val buffer = slot as? ByteBuffer ?: return null
        val tensor = interpreter.getOutputTensor(index)
        if (tensor.dataType() != DataType.FLOAT32) return null
        val count = tensor.numElements()
        if (count <= 0 || buffer.capacity() < count * BYTES_PER_FLOAT) return null
        buffer.rewind()
        return FloatArray(count) { buffer.float }
    }

    private fun imageInput(bitmap: Bitmap): ByteBuffer {
        val spec = imageSpec() ?: info.primary
        val side = info.imageSide
        val pixels = ClipImagePreprocess.pixels(bitmap, side)
        return if (spec?.isFloat == false) {
            ClipImagePreprocess.buffer(ClipImagePreprocess.bytes(pixels, side, info.layout))
        } else {
            ClipImagePreprocess.buffer(ClipImagePreprocess.floats(pixels, side, info.layout))
        }
    }

    private fun imageBuffer(pixels: IntArray): ByteBuffer {
        val spec = imageSpec() ?: return ClipImagePreprocess.buffer(FloatArray(info.imageSide * info.imageSide * 3))
        val side = info.imageSide
        return if (spec.isFloat) {
            ClipImagePreprocess.buffer(ClipImagePreprocess.floats(pixels, side, info.layout))
        } else {
            ClipImagePreprocess.buffer(ClipImagePreprocess.bytes(pixels, side, info.layout))
        }
    }

    /** The picture input, filled with nothing, for a run that only wants text. */
    private fun blankImage(): ByteBuffer = imageBuffer(IntArray(info.imageSide * info.imageSide))

    /** The text input, filled with nothing, for a run that only wants a picture. */
    private fun blankTokens(): ByteBuffer {
        val spec = textSpec() ?: return ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        return tokensBuffer(spec, IntArray(0))
    }

    /**
     * Token ids in the type the tensor declares.
     *
     * `INT64` is eight bytes per token, not four. The length comes from the
     * tensor's own shape so the buffer is exactly the size it wants.
     */
    private fun tokensBuffer(spec: InputSpec, tokens: IntArray): ByteBuffer {
        val count = spec.valueCount.coerceAtLeast(tokens.size)
        val wide = spec.dataType.contains("INT64", ignoreCase = true)
        val buffer = ByteBuffer.allocateDirect(count * (if (wide) 8 else 4))
            .order(ByteOrder.nativeOrder())
        for (index in 0 until count) {
            val value = tokens.getOrElse(index) { 0 }
            if (wide) buffer.putLong(value.toLong()) else buffer.putInt(value)
        }
        buffer.rewind()
        return buffer
    }

    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // The model takes a couple of hundred pixels; decoding a 48 megapixel
        // frame to throw 99% of it away is the difference between minutes and
        // hours.
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= ClipImagePreprocess.INPUT_SIDE * 2) {
            sample *= 2
            longest /= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    private fun normalise(vector: FloatArray): FloatArray {
        var sum = 0f
        vector.forEach { sum += it * it }
        val length = sqrt(sum)
        if (length <= 0f) return vector
        for (index in vector.indices) vector[index] /= length
        return vector
    }

    companion object {

        /**
         * Where a model that refuses to run is described.
         *
         * A snackbar closes and takes the reason with it; a phone that cannot
         * run this model is worth one line that outlives it.
         */
        private const val TAG = "ClipEncoder"

        /** A float32 is four bytes; [readFloats] checks a slot can hold one. */
        private const val BYTES_PER_FLOAT = 4

        /**
         * Opens the model.
         *
         * The accelerator is attempted first and, if the interpreter refuses
         * it, the whole thing is retried on the CPU. A delegate that throws is
         * common on older devices and is not a reason to give up on the model.
         *
         * [cpuOnly] skips the accelerator entirely, which is not a fallback for
         * a refused *construction* — it is a fallback for a refused **run**. A
         * delegate can accept a 400 MB graph when it is built and then fail
         * when the first picture goes through it, and construction-time retry
         * never sees that. [usesDelegate] says which one this encoder is, so the
         * caller can tell the two apart and reopen on the CPU exactly once.
         */
        fun open(file: File, threads: Int = 4, cpuOnly: Boolean = false): ClipEncoder? {
            if (!cpuOnly) {
                val withDelegate = runCatching {
                    val options = Interpreter.Options().setNumThreads(threads)
                    runCatching { options.addDelegate(NnApiDelegate()) }
                    build(Interpreter(file, options), usesDelegate = true)
                }.getOrNull()
                if (withDelegate != null) return withDelegate
            }

            return runCatching {
                build(
                    Interpreter(file, Interpreter.Options().setNumThreads(threads)),
                    usesDelegate = false,
                )
            }.getOrNull()
        }

        private fun build(interpreter: Interpreter, usesDelegate: Boolean): ClipEncoder? {
            val info = inspect(interpreter)
            if (!info.usable) {
                interpreter.close()
                return null
            }
            return ClipEncoder(interpreter, info, usesDelegate)
        }

        /** Reads the interpreter's own declaration of what it wants. */
        fun inspect(interpreter: Interpreter): TowerInfo {
            val inputs = (0 until interpreter.inputTensorCount).map { index ->
                val tensor = interpreter.getInputTensor(index)
                InputSpec(
                    name = tensor.name(),
                    dataType = tensor.dataType().toString(),
                    shape = tensor.shape(),
                )
            }
            val roles = resolveInputs(inputs)

            return TowerInfo(
                role = roles.first,
                inputs = inputs,
                outputSize = embeddingSize(interpreter),
                imageInput = roles.second,
                textInput = roles.third,
            )
        }

        /**
         * The width of an embedding, from whichever output is one.
         *
         * Not from output zero: this model reports three outputs and one of them
         * is a scalar, so the first is not reliably an embedding — and an
         * embedding width of zero makes the whole model "unusable" even though
         * it is perfectly fine. Any two-dimensional output with a width is an
         * embedding; they are all the same width in this family, so the first
         * one found is enough.
         */
        private fun embeddingSize(interpreter: Interpreter): Int {
            for (index in 0 until interpreter.outputTensorCount) {
                val shape = interpreter.getOutputTensor(index).shape()
                if (shape.size == 2 && shape[1] > 1) return shape[1]
            }
            return interpreter.getOutputTensor(0).shape().lastOrNull() ?: 0
        }

        /**
         * Which input takes the picture and which takes the tokens.
         *
         * A four-dimensional tensor is a picture — nothing else about CLIP is.
         * The token input is an integer tensor when the exporter kept the type,
         * and otherwise any remaining low-rank input, because a conversion that
         * casts ids to float leaves nothing else to go on.
         */
        internal fun resolveInputs(inputs: List<InputSpec>): Triple<TowerRole, Int, Int> {
            val image = inputs.indexOfFirst { it.rank == 4 }
            val text = inputs.indexOfFirstIndexed { index, spec ->
                index != image && (spec.isInteger || spec.rank <= 2)
            }
            return when {
                image >= 0 && text >= 0 -> Triple(TowerRole.BOTH, image, text)
                image >= 0 -> Triple(TowerRole.IMAGE, image, -1)
                text >= 0 -> Triple(TowerRole.TEXT, -1, text)
                else -> Triple(classify(inputs), -1, -1)
            }
        }

        /**
         * The decision table for a file that carries only one tower.
         *
         * Kept because a single-tower export is still a valid thing to be
         * handed, and because it is the fallback when nothing can be resolved
         * by shape alone.
         */
        internal fun classify(inputs: List<InputSpec>): TowerRole {
            if (inputs.isEmpty()) return TowerRole.UNKNOWN
            val label = inputs.joinToString(" ") { it.name }.lowercase()

            if (inputs.any { it.rank == 4 }) return TowerRole.IMAGE
            if (TEXT_HINTS.any { label.contains(it) }) return TowerRole.TEXT
            if (IMAGE_HINTS.any { label.contains(it) }) return TowerRole.IMAGE
            if (inputs.any { it.isInteger }) return TowerRole.TEXT
            if (inputs.size > 1) return TowerRole.TEXT
            // A token sequence cast to floats: [1,77] or [1,77,512] is not a
            // picture, and it is the only other thing this model family has.
            if (inputs.any { it.rank <= 3 }) return TowerRole.TEXT
            return TowerRole.UNKNOWN
        }

        private fun List<InputSpec>.indexOfFirstIndexed(predicate: (Int, InputSpec) -> Boolean): Int {
            for (index in indices) if (predicate(index, this[index])) return index
            return -1
        }

        private val TEXT_HINTS = listOf("text", "token", "input_ids", "ids", "caption", "mask")
        private val IMAGE_HINTS = listOf("image", "pixel", "img", "photo")
    }
}
