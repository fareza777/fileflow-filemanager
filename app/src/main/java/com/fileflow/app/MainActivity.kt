package com.fileflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fileflow.app.ui.AppRoot
import com.fileflow.app.ui.RootViewModel
import com.fileflow.app.ui.components.AdsConsent
import com.fileflow.app.ui.theme.FileFlowTheme
import com.fileflow.app.ui.theme.TypefaceProvider
import com.fileflow.app.ui.util.LocalUserSettings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val rootViewModel: RootViewModel by viewModels()

    @Inject lateinit var typefaces: TypefaceProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Starts the typeface read on a background thread before the first
        // frame, instead of stalling composition while it opens a font file.
        typefaces.warmUp()

        // Consent is gathered once, on the first launch, and never blocks the
        // UI: the app is fully usable with ads switched off.
        AdsConsent.ensureConsent(this) { }

        setContent {
            val settings by rootViewModel.settings.collectAsStateWithLifecycle()
            val fontFamily by typefaces.family
            FileFlowTheme(
                themeMode = settings.themeMode,
                colorTheme = settings.colorTheme,
                fontFamily = fontFamily,
            ) {
                CompositionLocalProvider(
                    LocalUserSettings provides settings,
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppRoot(openTransferOnStart = openTransferOnStart)
                    }
                }
            }
        }
    }

    /**
     * Read from the launch intent once. The notification's whole job is to bring
     * the user back to a screen they deliberately left running, so landing on
     * Home and asking them to find it again would miss the point.
     */
    private val openTransferOnStart: Boolean
        get() = intent?.getBooleanExtra(EXTRA_OPEN_TRANSFER, false) == true

    companion object {
        const val EXTRA_OPEN_TRANSFER = "open_transfer"
    }
}
