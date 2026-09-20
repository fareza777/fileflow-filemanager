package com.fileflow.app.domain.scan

import com.fileflow.app.core.storage.FileOperator
import com.fileflow.app.core.storage.VolumeRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes `*.fileflow-part` files left behind by an interrupted copy.
 *
 * The partial-write scheme means a destination is never half-written, so the
 * only way one of these survives is a hard process kill. Nothing else in the
 * system will clean them up, and to a user they look like real files taking
 * up real space.
 */
@Singleton
class PartialSweeper @Inject constructor(
    private val operator: FileOperator,
    private val volumes: VolumeRepository,
) {

    suspend fun sweep(): Int = operator.sweepPartials(volumes.roots())
}
