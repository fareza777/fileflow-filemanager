package com.fileflow.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.ui.theme.LocalAccents

/**
 * One icon per file, chosen so a list can be read by shape alone. Folders get
 * a filled tint of their own because they are the only rows that navigate.
 */
object FileVisuals {

    fun iconFor(extension: String, isDirectory: Boolean): ImageVector {
        if (isDirectory) return Icons.Filled.Folder
        return when (extension.lowercase()) {
            "pdf" -> Icons.Filled.PictureAsPdf
            "doc", "docx", "odt", "rtf", "pages" -> Icons.Filled.Description
            "xls", "xlsx", "ods", "csv", "numbers" -> Icons.Filled.TableChart
            "ppt", "pptx", "odp", "key" -> Icons.Filled.Slideshow
            "txt", "md", "log" -> Icons.AutoMirrored.Filled.Article
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tiff", "svg",
            "dng", "raw", "avif",
            -> Icons.Filled.Image
            "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "wmv", "flv", "mpg", "mpeg",
            -> Icons.Filled.Movie
            "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus", "amr", "wma",
            -> Icons.Filled.MusicNote
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "jar",
            -> Icons.Filled.FolderZip
            "apk", "apks", "xapk", "aab", "apkm" -> Icons.Filled.Android
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "cpp", "h", "cs",
            "go", "rs", "rb", "php", "swift", "sh", "sql", "html", "css", "json", "xml",
            -> Icons.Filled.Code
            else -> Icons.AutoMirrored.Filled.InsertDriveFile
        }
    }

    /**
     * Category colour comes from the theme rather than a fixed table, so it
     * follows wallpaper colours when the user enables them.
     */
    @Composable
    fun accentFor(category: FileCategory): Color = LocalAccents.current.forCategory(category)
}
