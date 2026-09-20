package com.fileflow.app.ui.util

import androidx.compose.runtime.staticCompositionLocalOf
import com.fileflow.app.data.prefs.UserSettings

/**
 * Settings that screens need to honour but have no business loading themselves.
 *
 * These were previously toggles that only their own Settings row read: turning
 * "move deletions to trash" off changed nothing, and turning "ask before
 * deleting" off changed nothing. Three switches that lied. Providing the
 * settings to the whole tree is what lets each screen actually behave the way
 * the switch claims.
 */
val LocalUserSettings = staticCompositionLocalOf { UserSettings() }
