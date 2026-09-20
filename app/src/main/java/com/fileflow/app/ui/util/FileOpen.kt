package com.fileflow.app.ui.util

import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.model.FileItem

/** How a tap on a file should be answered. */
enum class PreviewKind { IMAGE, TEXT, EXTERNAL }

/**
 * One answer to "what happens when I tap this file", shared by the browser, the
 * home feed, the calendar and search.
 *
 * The home feed used to fire an external intent for everything, so tapping a
 * photo threw you out to the gallery app — from a file manager that already has
 * an image viewer built in. Keeping the rule here means the in-app viewers are
 * used wherever they exist and nothing silently drifts between screens.
 */
object FileOpen {

    val TEXT_EXTENSIONS = setOf(
        "txt", "log", "md", "json", "xml", "yaml", "yml", "csv", "ini", "conf", "properties",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "cpp", "h", "hpp", "cs",
        "go", "rs", "rb", "php", "swift", "sh", "bat", "ps1", "sql", "html", "css", "scss",
        "gradle", "toml", "gitignore",
    )

    fun kindOf(item: FileItem): PreviewKind = when {
        item.isDirectory -> PreviewKind.EXTERNAL
        item.category == FileCategory.IMAGES -> PreviewKind.IMAGE
        item.extension.lowercase() in TEXT_EXTENSIONS -> PreviewKind.TEXT
        else -> PreviewKind.EXTERNAL
    }
}
