package com.fileflow.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.fileflow.app.R
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.util.Formatters
import java.io.File

/**
 * The one row shape used everywhere: browser, search, duplicates, large files,
 * trash and the calendar. Keeping it identical is what makes the app feel like
 * one tool rather than six.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: FileItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    subtitle: String? = null,
    thumbnail: Boolean = false,
    accent: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        } else {
            Color.Transparent
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                SelectionDot(selected)
                Spacer(Modifier.width(10.dp))
            }

            FileThumb(item = item, enabled = thumbnail, accent = accent)

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                // Formatting a date is expensive enough that doing it on every
                // unrelated recomposition shows up while flinging a list.
                val line = subtitle ?: remember(item.path, item.sizeBytes, item.lastModified) {
                    defaultSubtitle(item)
                }
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))
            trailing?.invoke()
        }
    }
}

fun defaultSubtitle(item: FileItem): String = if (item.isDirectory) {
    if (item.childCount > 0) "${item.childCount} items" else "Folder"
} else {
    "${Formatters.bytes(item.sizeBytes)} · ${Formatters.relative(item.lastModified)}"
}

/** Folder rows show the child count that the directory listing already paid for. */
@Composable
fun folderSubtitle(item: FileItem): String = when {
    item.childCount == 0 -> stringResource(R.string.common_empty_folder)
    else -> pluralStringResource(R.plurals.item_count, item.childCount, item.childCount)
}

/** What a row can paint for a file, if anything. */
private enum class ThumbKind { NONE, IMAGE, VIDEO }

@Composable
fun FileThumb(
    item: FileItem,
    enabled: Boolean,
    accent: Color? = null,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    /**
     * Decode target in pixels. Rows ask for 256 because they paint a 42dp
     * square; the big tiles on the home screen ask for more. The value is part
     * of the cache key, so a small decode can never be handed to a big view.
     */
    pixels: Int = THUMBNAIL_PIXELS,
    /** Corner radius; larger tiles want a larger radius to read as designed. */
    corner: Int = if (fill) 14 else 12,
) {
    val tint = accent ?: FileVisuals.accentFor(item.category)
    val shape = RoundedCornerShape(corner.dp)
    val iconSize = if (fill) 34.dp else 21.dp
    val side = if (fill) 0.dp else THUMB_SIZE
    val kind = remember(item.path, item.extension, item.sizeBytes, enabled, item.isDirectory) {
        thumbKindOf(item, enabled)
    }

    Box(
        modifier = if (fill) {
            modifier.fillMaxSize().clip(shape).background(tint.copy(alpha = 0.14f))
        } else {
            modifier.size(side).clip(shape).background(tint.copy(alpha = 0.14f))
        },
        contentAlignment = Alignment.Center,
    ) {
        // The glyph is drawn first and always. A thumbnail paints over it when
        // one loads, so the row is never blank while decoding — and when the
        // decode fails, which is routine for a corrupt photo, a codec the phone
        // does not ship, or a clip shorter than the frame we asked for, the row
        // falls back to the file's own icon instead of an empty tinted box.
        Icon(
            imageVector = FileVisuals.iconFor(item.extension, item.isDirectory),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )

        if (kind == ThumbKind.NONE) return@Box

        val context = LocalContext.current
        val request = remember(item.path, kind, pixels) {
            ImageRequest.Builder(context)
                .data(File(item.path))
                .size(pixels)
                .memoryCacheKey("${item.path}#$pixels")
                .apply {
                    // A frame from the middle of the clip beats a generic play
                    // icon for recognising which video is which. The decoder
                    // itself is registered once on the shared loader.
                    if (kind == ThumbKind.VIDEO) videoFrameMillis(VIDEO_FRAME_MILLIS)
                }
                .build()
        }

        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(shape),
        )

        // An outline on top of the picture, not behind it. A photo of a
        // document, a screenshot of a white app, a scan on white paper — they
        // all read as an empty placeholder tile against a light surface without
        // an edge to hold them in.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    shape = shape,
                )
        )
    }
}

/**
 * Asking Coil to decode something it cannot read costs a decode attempt and a
 * cache miss on every recomposition, so the decision is made once here rather
 * than left to the loader.
 */
private fun thumbKindOf(item: FileItem, enabled: Boolean): ThumbKind {
    if (!enabled || item.isDirectory || item.sizeBytes <= 0L) return ThumbKind.NONE
    val extension = item.extension.lowercase()
    return when {
        extension in THUMBNAIL_EXTENSIONS -> ThumbKind.IMAGE
        extension in VIDEO_EXTENSIONS -> ThumbKind.VIDEO
        else -> ThumbKind.NONE
    }
}

private const val THUMBNAIL_PIXELS = 256

/** Row thumbnail edge. Sized to read as a picture, not as a swatch. */
private val THUMB_SIZE = 46.dp

/** Decode target for the large tiles on the home screen. */
const val TILE_THUMBNAIL_PIXELS = 512

private const val VIDEO_FRAME_MILLIS = 2_000L

/**
 * Extensions worth handing to the image decoder. Anything the platform cannot
 * read falls back to the icon layer, so this list can stay generous.
 */
val THUMBNAIL_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "m4v", "mov")

@Composable
fun BadgeRow(
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.take(3).forEach { label ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
