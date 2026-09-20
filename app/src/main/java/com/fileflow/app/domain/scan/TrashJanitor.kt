package com.fileflow.app.domain.scan

import com.fileflow.app.data.repo.TrashRepository
import com.fileflow.app.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Retention has to happen without the user opening the trash screen, but it
 * also must never block startup — so it runs on the application scope and
 * fails quietly.
 */
@Singleton
class TrashJanitor @Inject constructor(
    private val trashRepository: TrashRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    fun purgeExpiredAsync() {
        scope.launch {
            runCatching { trashRepository.purgeExpired() }
        }
    }

    suspend fun purgeExpired() {
        runCatching { trashRepository.purgeExpired() }
    }
}
