package com.fileflow.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.fileflow.app.data.prefs.ColorTheme

/** The three accents of a palette, for the swatch in Settings. */
data class PaletteColors(val primary: Color, val secondary: Color, val tertiary: Color)

/**
 * A palette is defined by its three accents and nothing else.
 *
 * Surfaces, containers and every "on" colour are derived from those, which is
 * what makes a new theme four hex values instead of forty — and why the six
 * shipped palettes stay consistent with each other instead of each drifting in
 * its own direction. The derivation follows the shape of Material's tonal
 * palette: pale containers in light mode, deep tinted surfaces in dark mode,
 * accents lifted for dark backgrounds.
 */
private class PaletteSeed(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
)

private val Seeds: Map<ColorTheme, PaletteSeed> = mapOf(
    ColorTheme.OCEAN to PaletteSeed(
        primary = Color(0xFF0B5FCB),
        secondary = Color(0xFF48607A),
        tertiary = Color(0xFF00786F),
    ),
    ColorTheme.FOREST to PaletteSeed(
        primary = Color(0xFF2F6D46),
        secondary = Color(0xFF4E6252),
        tertiary = Color(0xFF7C5800),
    ),
    ColorTheme.SUNSET to PaletteSeed(
        primary = Color(0xFFC3490F),
        secondary = Color(0xFF7B5647),
        tertiary = Color(0xFFA8317C),
    ),
    ColorTheme.BERRY to PaletteSeed(
        primary = Color(0xFFAE2A6C),
        secondary = Color(0xFF6B5570),
        tertiary = Color(0xFF4B57B8),
    ),
    ColorTheme.SAND to PaletteSeed(
        primary = Color(0xFF8A5B12),
        secondary = Color(0xFF6E5D45),
        tertiary = Color(0xFF2F6A5B),
    ),
)

private val InkLight = Color(0xFF191A22)
private val MutedLight = Color(0xFF474751)
private val InkDark = Color(0xFFE4E1EA)
private val MutedDark = Color(0xFFC6C4D0)
private val DarkSurface = Color(0xFF121118)

private val cache = mutableMapOf<Pair<ColorTheme, Boolean>, ColorScheme>()

/**
 * The scheme for a palette, or null for the two entries that are not palettes —
 * the app's own brand colours and the wallpaper-derived ones.
 */
fun paletteScheme(theme: ColorTheme, dark: Boolean): ColorScheme? {
    val seed = Seeds[theme] ?: return null
    return cache.getOrPut(theme to dark) {
        if (dark) darkScheme(seed) else lightScheme(seed)
    }
}

/** The swatch colours for Settings, or null when the answer is the live scheme. */
fun paletteColors(theme: ColorTheme): PaletteColors? = Seeds[theme]?.let {
    PaletteColors(it.primary, it.secondary, it.tertiary)
}

private fun blend(from: Color, to: Color, amount: Float): Color = Color(
    red = from.red + (to.red - from.red) * amount,
    green = from.green + (to.green - from.green) * amount,
    blue = from.blue + (to.blue - from.blue) * amount,
)

/**
 * What sits on top of a filled accent. Computed rather than declared so a
 * palette with a light primary cannot end up with white-on-white text.
 */
private fun contentOn(color: Color): Color =
    if (color.luminance() > 0.5f) InkLight else Color.White

private fun lightScheme(seed: PaletteSeed): ColorScheme {
    val surface = blend(Color.White, seed.primary, 0.035f)
    return lightColorScheme(
        primary = seed.primary,
        onPrimary = contentOn(seed.primary),
        primaryContainer = blend(Color.White, seed.primary, 0.17f),
        onPrimaryContainer = blend(seed.primary, Color.Black, 0.72f),
        secondary = seed.secondary,
        onSecondary = contentOn(seed.secondary),
        secondaryContainer = blend(Color.White, seed.secondary, 0.18f),
        onSecondaryContainer = blend(seed.secondary, Color.Black, 0.7f),
        tertiary = seed.tertiary,
        onTertiary = contentOn(seed.tertiary),
        tertiaryContainer = blend(Color.White, seed.tertiary, 0.18f),
        onTertiaryContainer = blend(seed.tertiary, Color.Black, 0.7f),
        background = surface,
        onBackground = InkLight,
        surface = surface,
        onSurface = InkLight,
        surfaceVariant = blend(Color.White, seed.primary, 0.13f),
        onSurfaceVariant = MutedLight,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = blend(Color.White, seed.primary, 0.055f),
        surfaceContainer = blend(Color.White, seed.primary, 0.085f),
        surfaceContainerHigh = blend(Color.White, seed.primary, 0.115f),
        surfaceContainerHighest = blend(Color.White, seed.primary, 0.15f),
        outline = Color(0xFF767680),
        outlineVariant = blend(Color.White, seed.primary, 0.26f),
        inverseSurface = Color(0xFF2E2F38),
        inverseOnSurface = Color(0xFFF2F0F7),
        inversePrimary = blend(seed.primary, Color.White, 0.55f),
        surfaceTint = seed.primary,
        scrim = Color.Black,
        error = ErrorLight,
        onError = OnErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight,
    )
}

private fun darkScheme(seed: PaletteSeed): ColorScheme {
    val primary = blend(seed.primary, Color.White, 0.55f)
    val secondary = blend(seed.secondary, Color.White, 0.55f)
    val tertiary = blend(seed.tertiary, Color.White, 0.55f)
    return darkColorScheme(
        primary = primary,
        onPrimary = blend(seed.primary, Color.Black, 0.75f),
        primaryContainer = blend(DarkSurface, seed.primary, 0.34f),
        onPrimaryContainer = blend(seed.primary, Color.White, 0.7f),
        secondary = secondary,
        onSecondary = blend(seed.secondary, Color.Black, 0.72f),
        secondaryContainer = blend(DarkSurface, seed.secondary, 0.36f),
        onSecondaryContainer = blend(seed.secondary, Color.White, 0.7f),
        tertiary = tertiary,
        onTertiary = blend(seed.tertiary, Color.Black, 0.72f),
        tertiaryContainer = blend(DarkSurface, seed.tertiary, 0.36f),
        onTertiaryContainer = blend(seed.tertiary, Color.White, 0.7f),
        background = DarkSurface,
        onBackground = InkDark,
        surface = DarkSurface,
        onSurface = InkDark,
        surfaceVariant = blend(DarkSurface, seed.primary, 0.22f),
        onSurfaceVariant = MutedDark,
        surfaceContainerLowest = Color(0xFF0C0B11),
        surfaceContainerLow = blend(DarkSurface, seed.primary, 0.04f),
        surfaceContainer = blend(DarkSurface, seed.primary, 0.08f),
        surfaceContainerHigh = blend(DarkSurface, seed.primary, 0.13f),
        surfaceContainerHighest = blend(DarkSurface, seed.primary, 0.18f),
        outline = Color(0xFF908F9A),
        outlineVariant = blend(DarkSurface, seed.primary, 0.26f),
        inverseSurface = Color(0xFFE4E1EA),
        inverseOnSurface = Color(0xFF2E2F38),
        inversePrimary = seed.primary,
        surfaceTint = primary,
        scrim = Color.Black,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
    )
}
