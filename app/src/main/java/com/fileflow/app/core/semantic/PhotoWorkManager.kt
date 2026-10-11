package com.fileflow.app.core.semantic

import com.fileflow.app.core.vision.PhotoIndexer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Where the background photo work has got to. */
sealed interface PhotoWorkState {

    /** The two passes: reading text and themes, and reading meaning. */
    enum class Kind { INDEXING, EMBEDDING }

    data object Idle : PhotoWorkState
    data class Running(val kind: Kind, val done: Int, val total: Int, val name: String) : PhotoWorkState
    data class Finished(val kind: Kind, val counted: Int) : PhotoWorkState
    data class Failed(val kind: Kind, val reason: String) : PhotoWorkState
}

/**
 * The two photo-reading passes, as one piece of work a service can own.
 *
 * Both passes used to live in the screen's own scope, which made them the one
 * thing in this app that died when the user left: a first run reads thousands
 * of photos and no one sits and watches it. So the work is here instead — the
 * screen observes [state] and starts or stops the service, exactly like the
 * model download, and the reading continues with the app closed.
 *
 * [runEmbed] loops rather than embedding one batch, because "the newest 500"
 * and "everything" are different features, and the count check at the end of
 * each round is what stops the loop when the only photos left are the ones
 * that cannot be embedded at all.
 */
@Singleton
class PhotoWorkManager @Inject constructor(
    private val indexer: PhotoIndexer,
    private val semantic: SemanticEngine,
) {

    private val _state = MutableStateFlow<PhotoWorkState>(PhotoWorkState.Idle)
    val state: StateFlow<PhotoWorkState> = _state.asStateFlow()

    /** Reads photos that are new or changed into the text-and-label index. */
    suspend fun runIndex(limit: Int = 2000): Int = withContext(Dispatchers.IO) {
        val counted = try {
            indexer.index(limit = limit) { done, total, name ->
                _state.update {
                    PhotoWorkState.Running(PhotoWorkState.Kind.INDEXING, done, total, name)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.update {
                PhotoWorkState.Failed(
                    PhotoWorkState.Kind.INDEXING,
                    error.message ?: "Could not read the photos",
                )
            }
            return@withContext 0
        }
        _state.update { PhotoWorkState.Finished(PhotoWorkState.Kind.INDEXING, counted) }
        counted
    }

    /** Reads photos into the model's space, round after round, until none are left. */
    suspend fun runEmbed(batch: Int = 500): Int = withContext(Dispatchers.IO) {
        val vectorsBefore = semantic.count()
        var rounds = 0
        while (rounds < MAX_ROUNDS) {
            currentCoroutineContext().ensureActive()
            rounds++
            val before = semantic.count()
            val processed = try {
                semantic.embed(limit = batch) { done, total, name ->
                    _state.update {
                        PhotoWorkState.Running(PhotoWorkState.Kind.EMBEDDING, done, total, name)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    PhotoWorkState.Failed(
                        PhotoWorkState.Kind.EMBEDDING,
                        error.message ?: "Could not read the photos by meaning",
                    )
                }
                return@withContext 0
            }
            if (processed == 0) break
            // Photos that cannot be embedded are tried again next round, so a
            // round that adds no new vector means only failures are left.
            if (semantic.count() == before) break
        }
        val counted = semantic.count() - vectorsBefore
        _state.update { PhotoWorkState.Finished(PhotoWorkState.Kind.EMBEDDING, counted) }
        counted
    }

    /** Forgets the current state, so a stop does not leave a stale progress bar. */
    fun reset() {
        _state.update { PhotoWorkState.Idle }
    }

    private companion object {
        /** Rounds of [batch] photos; a ceiling no phone's library can reach. */
        const val MAX_ROUNDS = 400
    }
}
