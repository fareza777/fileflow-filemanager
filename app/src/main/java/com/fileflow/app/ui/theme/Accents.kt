package com.fileflow.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.fileflow.app.core.model.FileCategory

/**
 * The per-category icon colours, as a value the theme can replace.
 *
 * They used to be eight fixed hex values. That is fine until the user turns on
 * wallpaper colours: the scheme moves and the icons stay put, which reads as an
 * accident. Here the palette can be derived from whatever scheme is active.
 */
@Immutable
data class AccentPalette(
    val images: Color,
    val videos: Color,
    val audio: Color,
    val documents: Color,
    val archives: Color,
    val apps: Color,
    val code: Color,
    val other: Color,
) {
    fun forCategory(category: FileCategory): Color = when (category) {
        FileCategory.IMAGES -> images
        FileCategory.VIDEOS -> videos
        FileCategory.AUDIO -> audio
        FileCategory.DOCUMENTS -> documents
        FileCategory.ARCHIVES -> archives
        FileCategory.APPS -> apps
        FileCategory.CODE -> code
        FileCategory.OTHER -> other
    }
}

val LocalAccents = staticCompositionLocalOf {
    AccentPalette(
        images = AccentImages,
        videos = AccentVideos,
        audio = AccentAudio,
        documents = AccentDocuments,
        archives = AccentArchives,
        apps = AccentApps,
        code = AccentCode,
        other = AccentOther,
    )
}

/** Distinct enough to tell apart at 22dp, without leaving the scheme's family. */
private val HUE_OFFSETS = listOf(0f, 40f, 90f, 150f, 200f, 245f, 290f, 330f)

@Composable
fun rememberAccentPalette(scheme: ColorScheme, dark: Boolean): AccentPalette {
    val primary = scheme.primary
    return remember(primary, dark) {
        val hues = HUE_OFFSETS.map { offset -> rotated(primary, offset, dark) }
        AccentPalette(
            images = hues[0],
            videos = hues[1],
            audio = hues[2],
            documents = hues[3],
            archives = hues[4],
            apps = hues[5],
            code = hues[6],
            other = hues[7],
        )
    }
}

/**
 * Keeps the hue rotation but re-clamps saturation and brightness, so a very
 * pale wallpaper colour still produces icons that are legible on both a light
 * and a dark surface.
 */
private fun rotated(base: Color, degrees: Float, dark: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees) % 360f
    hsv[1] = hsv[1].coerceIn(0.42f, 0.80f)
    hsv[2] = if (dark) hsv[2].coerceIn(0.78f, 0.96f) else hsv[2].coerceIn(0.52f, 0.78f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
