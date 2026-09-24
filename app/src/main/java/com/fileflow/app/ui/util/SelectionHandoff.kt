package com.fileflow.app.ui.util

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A hand-off of selected files from one screen to another.
 *
 * "Convert these" starts on the recent list and happens on the convert screen,
 * and the list can hold hundreds of paths — too many and too long to fold into
 * a navigation route, where a long string risks the transaction limits and
 * turns up in logcat. Instead the selection is left here, the route carries
 * nothing, and the screen that opens takes it once and clears it.
 *
 * One-shot by design: a screen that is reopened later must not silently act on
 * a selection the user made in a previous session, so [take] empties it.
 */
@Singleton
class SelectionHandoff @Inject constructor() {

    private val _pending = MutableStateFlow<List<String>>(emptyList())

    /** Whether something is waiting, so a screen can tell "empty" from "unset". */
    val pending: StateFlow<List<String>> = _pending.asStateFlow()

    fun put(paths: List<String>) {
        _pending.value = paths.distinct()
    }

    fun take(): List<String> {
        val current = _pending.value
        _pending.value = emptyList()
        return current
    }
}
