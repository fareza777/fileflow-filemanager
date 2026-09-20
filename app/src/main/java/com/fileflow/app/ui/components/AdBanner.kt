package com.fileflow.app.ui.components

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.fileflow.app.BuildConfig
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Consent has to be gathered before the first ad request inside the EEA, and
 * it has to be resilient: an ad SDK problem must never stop someone from
 * managing their files. Every call here is wrapped, so failure means "no ads"
 * rather than "crash".
 */
object AdsConsent {

    /**
     * Whether an ad request is permitted right now. Starts false so nothing is
     * requested before consent has been resolved; UMP flips it once the answer
     * is known.
     */
    val adsAllowed = androidx.compose.runtime.mutableStateOf(false)

    @Volatile
    private var initialized = false

    fun ensureConsent(activity: Activity, onReady: () -> Unit) {
        if (initialized) {
            onReady()
            return
        }
        val information: ConsentInformation = runCatching {
            UserMessagingPlatform.getConsentInformation(activity)
        }.getOrNull() ?: run {
            // No consent information means no consent, so no ads. This branch
            // used to set the flag to true, which served personalised ads in
            // the EEA with nothing recorded — the opposite of what the comment
            // above promises, and a direct breach of the UMP requirements.
            initialized = true
            adsAllowed.value = false
            onReady()
            return
        }

        val parameters = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .build()

        fun finish() {
            initialized = true
            adsAllowed.value = runCatching { information.canRequestAds() }.getOrDefault(false)
            onReady()
        }

        runCatching {
            information.requestConsentInfoUpdate(
                activity,
                parameters,
                {
                    runCatching {
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                            finish()
                        }
                    }.onFailure { finish() }
                },
                { finish() },
            )
        }.onFailure { finish() }
    }

    fun canRequestAds(activity: Activity): Boolean = runCatching {
        UserMessagingPlatform.getConsentInformation(activity).canRequestAds()
    }.getOrDefault(false)

    /** Needed by the "privacy options" entry point required in some regions. */
    fun showPrivacyOptions(activity: Activity) {
        runCatching {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { }
        }
    }

    fun reset(activity: Activity) {
        runCatching {
            UserMessagingPlatform.getConsentInformation(activity).reset()
            initialized = false
            adsAllowed.value = false
        }
    }
}

/**
 * The single banner slot. Placement belongs to the caller — this exists so no
 * screen ever talks to the ad SDK directly. It renders nothing until consent
 * allows a request.
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    if (!AdsConsent.adsAllowed.value) return
    Box(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                AdView(context).apply {
                    // The banner's own size, not a fixed height: AdMob serves a
                    // 50dp banner on a phone and a 90dp one on a large screen,
                    // and a hard-coded 52dp box cropped the taller one.
                    setAdSize(AdSize.BANNER)
                    adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID
                    runCatching { loadAd(AdRequest.Builder().build()) }
                }
            },
        )
    }
}
