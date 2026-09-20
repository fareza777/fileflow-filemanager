package com.fileflow.app.domain.scan

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One sweep of storage at a time.
 *
 * Walking the filesystem is the most expensive thing this app does, and it was
 * possible for two of them to run at once: on a first launch the search index
 * was rebuilt three seconds in, right as the home screen started its own sweep
 * of the same directories. Two full traversals sharing one flash controller is
 * not twice the work, it is less than half the speed and a warm phone.
 *
 * Background work asks with [tryRun] and steps aside if the storage is busy;
 * work a user is waiting on uses [run].
 */
@Singleton
class StorageWalkGate @Inject constructor() {

    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }

    /** Runs [block], or returns null immediately when a walk is already going. */
    suspend fun <T> tryRun(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
