package com.fileflow.app.core.semantic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate

/** What a tower turned out to be, and why it is unusable if it is neither. */
data class TowerInfo(
    val role: TowerRole,
    val inputShape: IntArray,
    val inputDataType: String,
    val outputSize: Int,
) {
    val usable: Boolean get() = role != TowerRole.UNKNOWN && outputSize > 0
}

/**
 * Runs one of the two towers.
 *
 * The interpreter is created once and reused: loading a 400 MB model is the
 * expensive part, and doing it per photo would make indexing unusable. The
 * accelerator is tried first and the CPU is the fallback, because NNAPI is
 * faster where it works and refuses politely where it does not — but a refused
 * delegate that is not handled is a crash on exactly the phones least able to
 * afford one.
 */
class ClipEncoder private constructor(
    private val interpreter: Interpreter,
    val info: TowerInfo,
) {

    private val lock = Any()

    /** A photo, as a vector in the shared space, normalised to unit length. */
    fun embedImage(file: File, side: Int = ClipImagePreprocess.INPUT_SIDE): FloatArray? {
        val bitmap = decode(file) ?: return null
        return try {
            val input = ClipImagePreprocess.toTensor(bitmap)
            val output = Array(1) { FloatArray(info.outputSize) }
            synchronized(lock) {
                interpreter.run(input, output)
            }
            normalise(output[0])
        } catch (_: Throwable) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** A phrase, in the same space. Unit length, so cosine is a dot product. */
    fun embedText(tokens: IntArray): FloatArray? = try {
        val input = ByteBuffer
            .allocateDirect(tokens.size * 4)
            .order(ByteOrder.nativeOrder())
            .apply { tokens.forEach { putInt(it) } }
            .apply { rewind() }
        val output = Array(1) { FloatArray(info.outputSize) }
        synchronized(lock) {
            interpreter.run(input, output)
        }
        normalise(output[0])
    } catch (_: Throwable) {
        null
    }

    fun close() {
        runCatching { interpreter.close() }
    }

    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // The model takes 224 pixels; decoding a 48 megapixel frame to throw
        // 99% of it away is the difference between minutes and hours.
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= ClipImagePreprocess.RESIZE_SIDE * 2) {
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
         * Opens a tower, working out which one it is from the tensor it
         * declares.
         *
         * The two files in the model repository are named `first` and `last`,
         * which says nothing about their roles, and swapping them produces
         * vectors that are the right length and completely meaningless. The
         * input tensor is the only reliable statement of what a model wants:
         * integers are token ids, floats in a four-dimensional shape are pixels.
         */
        fun open(file: File, threads: Int = 4): ClipEncoder? = runCatching {
            val options = Interpreter.Options().setNumThreads(threads)
            // NNAPI is a best-effort accelerator; when it is unsupported the
            // delegate throws at creation and the CPU path is used instead.
            runCatching { options.addDelegate(NnApiDelegate()) }
            val interpreter = Interpreter(file, options)

            val input = interpreter.getInputTensor(0)
            val output = interpreter.getOutputTensor(0)
            val shape = input.shape()
            val type = input.dataType().toString()
            val role = when {
                type.contains("INT", ignoreCase = true) && shape.size >= 2 -> TowerRole.TEXT
                shape.size == 4 -> TowerRole.IMAGE
                else -> TowerRole.UNKNOWN
            }
            val info = TowerInfo(
                role = role,
                inputShape = shape,
                inputDataType = type,
                outputSize = output.shape().lastOrNull() ?: 0,
            )
            if (!info.usable) {
                interpreter.close()
                null
            } else {
                ClipEncoder(interpreter, info)
            }
        }.getOrNull()
    }
}
