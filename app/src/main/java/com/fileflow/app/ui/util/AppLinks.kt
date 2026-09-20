package com.fileflow.app.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.fileflow.app.R

/**
 * The privacy policy has to be reachable from inside the app, not only from the
 * store listing — AdMob's own policy and the Play data-safety rules both expect
 * a user to be able to find it without going back to the store.
 *
 * Replace this with the URL you host docs/privacy-policy.md at before release.
 */
const val PRIVACY_POLICY_URL = "https://example.com/fileflow/privacy"

object AppLinks {

    fun openPrivacyPolicy(context: Context) = open(context, PRIVACY_POLICY_URL)

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                context.getString(R.string.message_no_browser),
                Toast.LENGTH_SHORT,
            ).show()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(
                context,
                context.getString(R.string.common_error),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
