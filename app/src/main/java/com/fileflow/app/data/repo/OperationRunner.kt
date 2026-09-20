package com.fileflow.app.data.repo

import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationProgress
import com.fileflow.app.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveOperation(
    val kind: OperationKind,
    val label: String,
    val progress: OperationProgress? = null,
)

/**
 * Runs file operations on the application scope rather than a screen's.
 *
 * A screen's ViewModel is cleared the moment its back stack entry goes away,
 * which used to cancel a copy in flight and leave a half-written file behind
 * with no explanation. Here the work belongs to the process: navigating away
 * merely stops watching it, and coming back re-attaches to the same progress.
 *
 * The caller still awaits the result, so a screen that stays alive updates
 * normally; a screen that leaves is not missed.
 */
@Singleton
class OperationRunner @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _active = MutableStateFlow<ActiveOperation?>(null)
    val active: StateFlow<ActiveOperation?> = _active.asStateFlow()

    private var current: Job? = null

    /**
     * Which run currently owns the progress indicator. Compared by identity, not
     * by value: a superseded operation has to be able to tell that it no longer
     * speaks for the indicator it is about to clear.
     */
    @Volatile
    private var owner: Any? = null

    suspend fun run(
        kind: OperationKind,
        label: String = "",
        body: suspend (onProgress: (OperationProgress) -> Unit) -> Unit,
    ) {
        // A second batch replaces the first: two concurrent bulk operations on
        // the same storage is how users end up with files in the wrong place.
        current?.cancel()

        val token = Any()
        owner = token

        val work = scope.async {
            _active.value = ActiveOperation(kind, label)
            try {
                body { progress ->
                    if (owner === token) {
                        _active.value = ActiveOperation(kind, label, progress)
                    }
                }
            } finally {
                // Cancelling the previous job let its `finally` run *after* the
                // replacement had published its own progress, blanking the
                // indicator of the operation that was actually running. Only the
                // owner may clear it.
                if (owner === token) {
                    _active.value = null
                    owner = null
                }
            }
        }
        current = work

        // Awaiting here links the caller's lifetime to the *result*, not to the
        // work: if the caller goes away, the deferred keeps running.
        work.await()
    }

    fun cancel() {
        val cancelled = current
        // Released before the cancel so the doomed job's `finally` finds it is
        // no longer the owner and leaves the indicator alone.
        current = null
        owner = null
        cancelled?.cancel()
        _active.value = null
    }
}
