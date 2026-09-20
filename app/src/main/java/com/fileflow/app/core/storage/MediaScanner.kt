package com.fileflow.app.core.storage

import android.content.Context
import android.media.MediaScannerConnection
import com.fileflow.app.core.util.MimeTypes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's media index is what the Gallery, WhatsApp and every music player
 * read. A file manager that copies a photo without telling the index about it
 * looks broken: the file is on disk, but nowhere the user can see it.
 *
 * Only media is announced. Documents and archives are not in the media index,
 * and scanning them would be pointless wake-ups.
 */
@Singleton
class MediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val mediaExtensions = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tiff", "avif", "dng",
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "wmv", "flv", "mpg", "mpeg", "ts",
        "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus", "amr", "wma", "mid", "midi",
    )

    fun announce(paths: List<String>) {
        val media = paths.filter { path ->
            val extension = MimeTypes.extensionOf(path.substringAfterLast('/'))
            extension in mediaExtensions
        }
        if (media.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(context, media.toTypedArray(), null, null)
        }
    }

    fun announce(path: String) = announce(listOf(path))
}
