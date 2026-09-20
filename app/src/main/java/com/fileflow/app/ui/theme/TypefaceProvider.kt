package com.fileflow.app.ui.theme

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.fileflow.app.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FONT_ASSET = "fonts/plus_jakarta_sans.ttf"

/**
 * Loads the bundled typeface off the main thread.
 *
 * Building a font family opens the asset and reads a variable font's tables.
 * Doing that inside composition cost roughly five seconds of blocked frames on
 * a cold start — the very first thing the user would see. It starts as the
 * system family and swaps once, in the background.
 *
 * The font is optional by design: a build without the asset simply keeps the
 * platform typeface rather than failing.
 */
@Singleton
class TypefaceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {

    // Explicit type: FontFamily.Default is a SystemFontFamily, and inferring
    // from it would pin the state to that subtype.
    private val state = mutableStateOf<FontFamily>(FontFamily.Default)

    val family: State<FontFamily> get() = state

    private var started = false

    fun warmUp() {
        if (started) return
        started = true
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { buildFamily() }
            // Only swap when there is something to swap to, so a missing asset
            // does not trigger a pointless recomposition.
            if (loaded != FontFamily.Default) state.value = loaded
        }
    }

    private fun buildFamily(): FontFamily {
        val available = runCatching {
            context.assets.open(FONT_ASSET).close()
            true
        }.getOrDefault(false)
        if (!available) return FontFamily.Default

        return runCatching {
            FontFamily(
                Font(
                    path = FONT_ASSET,
                    assetManager = context.assets,
                    weight = FontWeight.Normal,
                    variationSettings = FontVariation.Settings(FontVariation.weight(400)),
                ),
                Font(
                    path = FONT_ASSET,
                    assetManager = context.assets,
                    weight = FontWeight.Medium,
                    variationSettings = FontVariation.Settings(FontVariation.weight(500)),
                ),
                Font(
                    path = FONT_ASSET,
                    assetManager = context.assets,
                    weight = FontWeight.SemiBold,
                    variationSettings = FontVariation.Settings(FontVariation.weight(600)),
                ),
                Font(
                    path = FONT_ASSET,
                    assetManager = context.assets,
                    weight = FontWeight.Bold,
                    variationSettings = FontVariation.Settings(FontVariation.weight(700)),
                ),
            )
        }.getOrDefault(FontFamily.Default)
    }
}
