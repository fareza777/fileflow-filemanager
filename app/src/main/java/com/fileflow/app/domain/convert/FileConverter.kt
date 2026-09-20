package com.fileflow.app.domain.convert

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.util.MimeTypes
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What a file can be turned into. */
enum class ConvertTarget(val extension: String) {
    PNG("png"),
    JPEG("jpg"),
    WEBP("webp"),
    PDF("pdf"),
    /** The audio track of a video, copied out untouched. */
    AUDIO("m4a"),
    ;

    companion object {
        /**
         * Only the conversions that can actually be produced on a phone.
         *
         * This list is the honest answer to "convert this file": re-encoding
         * video needs an encoder stack Android does not expose app-side, and
         * office formats need a document engine. What is left is still the
         * bulk of what people want — image formats, a PDF, and the audio out
         * of a video.
         */
        fun availableFor(extension: String, category: FileCategory): List<ConvertTarget> = when {
            category == FileCategory.IMAGES -> listOf(JPEG, PNG, WEBP, PDF)
            category == FileCategory.VIDEOS -> listOf(AUDIO)
            isText(extension) -> listOf(PDF)
            else -> emptyList()
        }

        fun isText(extension: String): Boolean {
            val normalized = MimeTypes.normalizeExtension(extension)
            return normalized in TEXT_EXTENSIONS
        }

        private val TEXT_EXTENSIONS = setOf(
            "txt", "log", "md", "json", "xml", "yaml", "yml", "csv", "ini", "conf",
            "properties", "kt", "kts", "java", "py", "js", "ts", "html", "css",
            "gradle", "toml", "sh", "bat", "ps1", "sql",
        )
    }
}

data class ConvertOptions(
    /** JPEG and WEBP only; PNG is lossless by definition. */
    val quality: Int = 90,
    /** Longest edge of the result, or null to keep the original size. */
    val maxDimension: Int? = null,
)

data class ConvertedFile(
    val path: String,
    val bytes: Long,
)

/**
 * File conversion, without a native library and without a network call.
 *
 * Everything here is done by the platform: `Bitmap` decodes and encodes the
 * image formats, `PdfDocument` lays out pages, `MediaExtractor` and
 * `MediaMuxer` copy an audio track out of a video container without decoding
 * it — a remux, so a two-hour video becomes an M4A in seconds rather than in
 * a re-encode that would also lose quality.
 *
 * Nothing is deleted and nothing is overwritten: a conversion always writes a
 * new file beside the original, named after it.
 */
@Singleton
class FileConverter @Inject constructor() {

    suspend fun convert(
        source: File,
        target: ConvertTarget,
        destination: File,
        options: ConvertOptions = ConvertOptions(),
        onProgress: (fraction: Float, detail: String) -> Unit = { _, _ -> },
    ): Result<ConvertedFile> = withContext(Dispatchers.IO) {
        runCatching {
            if (!source.isFile) error("That file is gone")
            destination.parentFile?.mkdirs()
            val partial = File(destination.parentFile, destination.name + PARTIAL_SUFFIX)
            if (partial.exists()) partial.delete()

            try {
                when (target) {
                    ConvertTarget.JPEG, ConvertTarget.PNG, ConvertTarget.WEBP ->
                        encodeImage(source, target, options, partial, onProgress)
                    ConvertTarget.PDF -> writePdf(source, options, partial, onProgress)
                    ConvertTarget.AUDIO -> extractAudio(source, partial, onProgress)
                }
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                partial.delete()
                throw cancelled
            } catch (error: Throwable) {
                partial.delete()
                throw error
            }

            onProgress(1f, destination.name)
            ConvertedFile(destination.absolutePath, destination.length())
        }
    }

    // ------------------------------------------------------------- images

    private fun encodeImage(
        source: File,
        target: ConvertTarget,
        options: ConvertOptions,
        output: File,
        onProgress: (Float, String) -> Unit,
    ) {
        onProgress(0.15f, source.name)
        val bitmap = decodeScaled(source, options.maxDimension) ?: error("Could not read that image")
        val format = when (target) {
            ConvertTarget.JPEG -> Bitmap.CompressFormat.JPEG
            ConvertTarget.PNG -> Bitmap.CompressFormat.PNG
            else -> Bitmap.CompressFormat.WEBP
        }
        FileOutputStream(output).use { stream ->
            // PNG ignores the quality argument; the others honour it.
            val quality = if (target == ConvertTarget.PNG) 100 else options.quality.coerceIn(10, 100)
            if (!bitmap.compress(format, quality, stream)) error("Could not write that image")
        }
        bitmap.recycle()
        onProgress(0.9f, output.name)
    }

    /**
     * Decodes at the size that is actually wanted. A 50 MP photo decoded whole
     * and then scaled is 200 MB of bitmap for a result that is a fraction of
     * that — this is the difference between a conversion that works on a phone
     * and one that runs out of memory on the third file.
     */
    private fun decodeScaled(source: File, maxDimension: Int?): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(source.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val limit = maxDimension ?: return android.graphics.BitmapFactory.decodeFile(source.absolutePath)
        val longest = maxOf(width, height)
        if (longest <= limit) return android.graphics.BitmapFactory.decodeFile(source.absolutePath)

        var sample = 1
        while (longest / (sample * 2) >= limit) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = android.graphics.BitmapFactory.decodeFile(source.absolutePath, options) ?: return null
        val scale = limit.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) return decoded
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    // --------------------------------------------------------------- pdf

    private fun writePdf(
        source: File,
        options: ConvertOptions,
        output: File,
        onProgress: (Float, String) -> Unit,
    ) {
        val extension = MimeTypes.normalizeExtension(source.name.substringAfterLast('.', ""))
        val document = PdfDocument()
        try {
            val pages = when {
                ConvertTarget.isText(extension) -> writeTextPages(document, source, onProgress)
                isImage(source) -> writeImagePages(document, source, options, onProgress)
                else -> error("Cannot make a PDF from that file")
            }
            FileOutputStream(output).use { document.writeTo(it) }
            onProgress(0.95f, "$pages pages")
        } finally {
            document.close()
        }
    }

    private fun isImage(file: File): Boolean = MimeTypes.categoryOf(
        MimeTypes.normalizeExtension(file.name.substringAfterLast('.', ""))
    ) == FileCategory.IMAGES

    private fun writeImagePages(
        document: PdfDocument,
        source: File,
        options: ConvertOptions,
        onProgress: (Float, String) -> Unit,
    ): Int {
        val bitmap = decodeScaled(source, options.maxDimension ?: MAX_PDF_IMAGE_EDGE)
            ?: error("Could not read that image")
        try {
            val page = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val content = document.startPage(page)
            val target = RectF(
                MARGIN.toFloat(),
                MARGIN.toFloat(),
                (PAGE_WIDTH - MARGIN).toFloat(),
                (PAGE_HEIGHT - MARGIN).toFloat(),
            )
            val scale = minOf(
                target.width() / bitmap.width,
                target.height() / bitmap.height,
            )
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            val left = target.left + (target.width() - width) / 2f
            val top = target.top + (target.height() - height) / 2f
            content.canvas.drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                RectF(left, top, left + width, top + height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            document.finishPage(content)
            onProgress(0.7f, source.name)
            return 1
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Text pages are drawn by laying the whole document out once and then
     * clipping one page at a time: measuring each page separately would run the
     * line breaker once per page and produce a different break than the screen.
     */
    private fun writeTextPages(
        document: PdfDocument,
        source: File,
        onProgress: (Float, String) -> Unit,
    ): Int {
        if (source.length() > MAX_TEXT_BYTES) error("That file is too large to lay out")
        val text = source.readText(Charsets.UTF_8)
        val paint = TextPaint().apply {
            textSize = 10f
            color = Color.BLACK
            isAntiAlias = true
        }
        val layoutWidth = PAGE_WIDTH - MARGIN * 2
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1f)
            .setIncludePad(false)
            .build()

        val usableHeight = PAGE_HEIGHT - MARGIN * 2
        val totalHeight = layout.height
        val pages = ((totalHeight + usableHeight - 1) / usableHeight).coerceAtLeast(1)

        for (index in 0 until pages) {
            val page = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
            val content = document.startPage(page)
            val canvas = content.canvas
            canvas.save()
            canvas.clipRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT)
            canvas.translate(MARGIN.toFloat(), MARGIN.toFloat() - index * usableHeight)
            layout.draw(canvas)
            canvas.restore()
            document.finishPage(content)
            onProgress(0.1f + 0.8f * (index + 1) / pages, homePage(index + 1, pages))
        }
        return pages
    }

    private fun homePage(page: Int, total: Int): String = "page $page of $total"

    // ------------------------------------------------------------- audio

    /**
     * Copies the audio track out of a video into an M4A. No decoding, no
     * re-encoding: the samples are the same ones that were in the video.
     */
    private fun extractAudio(
        source: File,
        output: File,
        onProgress: (Float, String) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("That video has no audio track")

            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val duration = format.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()

            val bufferSize = format.getIntegerOrDefault(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_BUFFER)
            val buffer = ByteBuffer.allocate(bufferSize.coerceIn(64 * 1024, 4 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
                if (duration > 0) {
                    onProgress(
                        (info.presentationTimeUs.toFloat() / duration).coerceIn(0f, 1f),
                        source.name,
                    )
                }
            }
            muxer.stop()
            onProgress(0.98f, output.name)
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.getLongOrDefault(key: String, fallback: Long): Long =
        if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(fallback) else fallback

    private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    private companion object {
        const val PARTIAL_SUFFIX = ".fileflow-part"
        const val MAX_PDF_IMAGE_EDGE = 2200
        const val MAX_TEXT_BYTES = 4L * 1024 * 1024
        const val DEFAULT_BUFFER = 1024 * 1024

        /** A4 at 72 dpi, the unit PdfDocument works in. */
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 36
    }
}
