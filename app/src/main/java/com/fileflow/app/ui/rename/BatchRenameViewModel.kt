package com.fileflow.app.ui.rename

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileflow.app.core.storage.CollisionResolver
import com.fileflow.app.core.storage.OperationKind
import com.fileflow.app.core.storage.OperationProgress
import com.fileflow.app.core.util.OutcomeMessages
import com.fileflow.app.core.util.Paths
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.FileActionsRepository
import com.fileflow.app.data.repo.OperationRunner
import com.fileflow.app.data.repo.RenameSummary
import com.fileflow.app.domain.Candidate
import com.fileflow.app.domain.rename.NameTemplate
import com.fileflow.app.ui.util.SelectionHandoff
import com.fileflow.app.ui.util.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One line of the rename preview.
 *
 * [newName] is the name the file will actually end up with, not the pattern's
 * first guess: illegal characters are cleaned and collisions are resolved
 * before the row is built, because [FileActionsRepository.applyRenames] does
 * neither of those things itself.
 */
@Immutable
data class RenamePreviewRow(
    val path: String,
    val currentName: String,
    val newName: String,
    /** The pattern produced nothing usable for this file. */
    val blocked: Boolean = false,
    /** The file already carries this name, so the run leaves it alone. */
    val unchanged: Boolean = false,
)

@Immutable
data class BatchRenameState(
    val pattern: String = "",
    /** The first few rows, in the order they will be renamed. */
    val preview: List<RenamePreviewRow> = emptyList(),
    /** How many files came in with the selection, before any of them were ruled out. */
    val total: Int = 0,
    /** Rows whose name cannot be used as it stands. Any of them refuses the run. */
    val blocked: Int = 0,
    /** Rows that are already named the way the pattern asks, so there is nothing to do. */
    val unchanged: Int = 0,
    /** Placeholders the renderer does not know; they stay in the name as typed. */
    val unknownTokens: List<String> = emptyList(),
    val running: Boolean = false,
    val progress: Float? = null,
    val progressDetail: String? = null,
    val lastGroupId: String? = null,
) {
    /** Files this run would really rename. */
    val renamable: Int get() = (total - blocked - unchanged).coerceAtLeast(0)

    val hasFiles: Boolean get() = total > 0

    val canApply: Boolean get() = !running && blocked == 0 && renamable > 0
}

/**
 * A naming pattern applied to a whole selection.
 *
 * The sibling tool ([SmartRenameScreen]) runs the other way round: it reads
 * every name it can find and suggests better ones. This one starts from a
 * pattern the user writes and answers the only question that matters about it —
 * what exactly will each of my files be called.
 */
@HiltViewModel
class BatchRenameViewModel @Inject constructor(
    private val actions: FileActionsRepository,
    private val browseRepository: BrowseRepository,
    private val operationRunner: OperationRunner,
    private val outcome: OutcomeMessages,
    private val handoff: SelectionHandoff,
) : ViewModel() {

    private val _state = MutableStateFlow(BatchRenameState(pattern = DEFAULT_PATTERN))
    val state: StateFlow<BatchRenameState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** The files as they exist on disk right now, in the order they were selected. */
    private var items: List<Candidate> = emptyList()

    /**
     * Every resolved row, including the ones past [PREVIEW_LIMIT]. The cap is a
     * display decision; the run and the counts need all of them.
     */
    private var resolved: List<RenamePreviewRow> = emptyList()

    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            // The selection is picked somewhere else and left here, the same way
            // the convert and compress screens receive theirs. A path that has
            // gone since it was selected simply drops out rather than being
            // renamed into a failure.
            val handed = handoff.take()
            items = withContext(Dispatchers.IO) {
                handed.mapNotNull { browseRepository.toCandidate(it) }
            }
            resolve()
        }
    }

    fun setPattern(pattern: String) {
        _state.update {
            it.copy(pattern = pattern, unknownTokens = NameTemplate.unknownTokens(pattern))
        }
        // One job at a time: a long pattern typed quickly used to leave each
        // keystroke resolving the whole selection into a state update that
        // arrived after the later ones.
        previewJob?.cancel()
        previewJob = viewModelScope.launch { resolve() }
    }

    /** Adds a placeholder to the pattern, keeping the words apart. */
    fun appendToken(token: String) {
        val current = _state.value.pattern
        setPattern(if (current.isBlank() || current.endsWith(" ")) current + token else "$current $token")
    }

    fun apply() {
        val snapshot = _state.value
        if (snapshot.running) return

        // Two ways a batch is refused whole rather than in part. A name the
        // system will not take, and a name that is not a change — both are
        // counted in the preview, and a run that renamed some of its files
        // while reporting "12 failed" would leave the user unable to tell
        // which is which.
        if (snapshot.blocked > 0) return
        val renames = resolved.filterNot { it.unchanged }.map { it.path to it.newName }
        if (renames.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(running = true, progress = 0f, progressDetail = null) }
            try {
                var summary: RenameSummary? = null
                operationRunner.run(OperationKind.RENAME) { progress ->
                    summary = actions.applyRenames(renames) { done, total ->
                        progress(
                            OperationProgress(
                                current = done,
                                total = total,
                                currentName = renames.getOrNull(done)?.second.orEmpty(),
                                bytesDone = 0L,
                                bytesTotal = 0L,
                            )
                        )
                    }
                }
                val result = summary
                if (result == null) {
                    _state.update { it.copy(running = false, progress = null) }
                    return@launch
                }
                _state.update { it.copy(running = false, progress = null, progressDetail = null) }
                if (result.succeeded > 0) _state.update { it.copy(lastGroupId = result.groupId) }
                _messages.send(
                    UiMessage.Done(
                        text = outcome.renamed(result.succeeded, result.failed),
                        // Only the names that really moved are worth walking
                        // back; the failed ones never left their old name.
                        undoGroup = result.groupId.takeIf { result.succeeded > 0 },
                    )
                )
                // The files on disk have different names now, so the preview is
                // rebuilt from them instead of from what they were called when
                // the pattern was written.
                reload()
            } catch (_: CancellationException) {
                _state.update { it.copy(running = false, progress = null) }
            }
        }
    }

    fun undo() {
        val group = _state.value.lastGroupId ?: return
        // Cleared before the work starts, not after it finishes: the snackbar's
        // action stays tappable while the reversal runs, and a second tap would
        // otherwise start undoing a group that is already being undone.
        _state.update { it.copy(lastGroupId = null) }
        viewModelScope.launch {
            val result = actions.undoGroup(group)
            _messages.send(UiMessage.Info(outcome.reversed(result.restored, result.failed, result.skipped)))
            reload()
        }
    }

    /**
     * Reads the files back from disk and recomputes the preview.
     *
     * A row that was renamed a moment ago must not still promise the change it
     * already made, and a row that failed must still offer it.
     */
    private suspend fun reload() {
        // Only rows that were going to be renamed have a second path worth
        // looking at. A blocked row has no name at all — "the folder plus an
        // empty name" is the folder itself, which would turn a file into a
        // directory in this list — and an unchanged row never moved.
        val renamed = resolved
            .filterNot { it.blocked || it.unchanged }
            .associate { row ->
                row.path to File(row.path.substringBeforeLast('/'), row.newName).absolutePath
            }
        val previous = items
        items = withContext(Dispatchers.IO) {
            previous.mapNotNull { item ->
                val target = renamed[item.path]
                val atTarget = target?.takeIf { File(it).exists() }
                browseRepository.toCandidate(atTarget ?: item.path)
            }
        }
        resolve()
    }

    /**
     * Works out the exact final name for every file, then publishes the counts
     * and the first [PREVIEW_LIMIT] rows.
     *
     * The names are settled here, once, and the run applies exactly what was
     * shown. They have to be, because [FileActionsRepository.applyRenames] does
     * not deduplicate: each rename goes straight to `FileOperator.rename`,
     * which fails outright when the target name is already taken. Two files
     * rendering to the same name would rename the first, fail the second, and
     * the preview would have promised otherwise.
     */
    private suspend fun resolve() {
        val pattern = _state.value.pattern
        val batch = items
        val resolution = withContext(Dispatchers.IO) {
            // One claimed-name set per folder. Two files in different folders
            // may share a name; two files in the same folder may not, and the
            // resolver keys by folder to keep those two facts apart.
            val claimed = HashMap<String, MutableSet<String>>()
            val rows = ArrayList<RenamePreviewRow>(batch.size)
            var blocked = 0
            var unchanged = 0

            batch.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                val folder = item.path.substringBeforeLast('/', "")
                val rendered = NameTemplate.render(
                    pattern = pattern,
                    name = item.name,
                    extension = item.extension,
                    lastModified = item.lastModified,
                    category = item.category.key,
                    parentName = folder.substringAfterLast('/'),
                    // One-based: {n} reads as a position in the batch, and 01 is
                    // a better first name than 00.
                    sequence = index + 1,
                )

                // An empty rendering is the one failure the sanitiser cannot
                // repair — asked for a name it answers "untitled", which is a
                // name nobody asked for.
                if (rendered.isBlank()) {
                    blocked++
                    rows += RenamePreviewRow(
                        path = item.path,
                        currentName = item.name,
                        newName = "",
                        blocked = true,
                    )
                    return@forEachIndexed
                }

                val preferred = withExtension(Paths.sanitizeFileName(rendered), item.extension)
                if (!Paths.isValidFileName(preferred)) {
                    blocked++
                    rows += RenamePreviewRow(
                        path = item.path,
                        currentName = item.name,
                        newName = preferred,
                        blocked = true,
                    )
                    return@forEachIndexed
                }

                // A file keeping the name it already has is not a rename. Left
                // in the batch it would be reported as a failure, because
                // `FileOperator.rename` refuses "the name is unchanged". The
                // comparison ignores case as well: on the card filesystems most
                // phones use, the target of a case-only rename already exists —
                // as the file itself — and the rename is refused there too.
                val isOwnName = preferred.equals(item.name, ignoreCase = true)
                val name = if (isOwnName) {
                    preferred
                } else {
                    CollisionResolver.unique(
                        directory = File(folder),
                        name = preferred,
                        claimed = claimed.getOrPut(folder) { HashSet() },
                        // The file's own name is not in its way. Everything
                        // else that is on disk is, because the batch runs in
                        // this order and the names ahead of it are still held
                        // when its turn comes.
                        exists = { candidate ->
                            candidate.exists() && candidate.absolutePath != item.path
                        },
                    )
                }

                if (isOwnName) {
                    // Reserved explicitly, since the resolver never saw it.
                    claimed.getOrPut(folder) { HashSet() }
                        .add(CollisionResolver.key(folder, name))
                }

                // The resolved name can also come back as the file's own name
                // from the other side: a file already called "photo (2).jpg"
                // asking for "photo.jpg" when that name is taken is left alone.
                if (isOwnName || name == item.name) {
                    unchanged++
                    rows += RenamePreviewRow(item.path, item.name, item.name, unchanged = true)
                } else {
                    rows += RenamePreviewRow(item.path, item.name, name)
                }
            }
            Resolution(rows, blocked, unchanged)
        }

        resolved = resolution.rows
        _state.update {
            it.copy(
                preview = resolution.rows.take(PREVIEW_LIMIT),
                total = batch.size,
                blocked = resolution.blocked,
                unchanged = resolution.unchanged,
            )
        }
    }

    /**
     * A pattern renders the stem of a name; the file keeps its own extension
     * unless the pattern already spells it out. Shared with the rule engine's
     * rename action, so a pattern written in one place reads the same in the
     * other.
     */
    private fun withExtension(rendered: String, extension: String): String =
        if (extension.isNotEmpty() && !rendered.endsWith(".$extension", ignoreCase = true)) {
            "$rendered.$extension"
        } else {
            rendered
        }

    private data class Resolution(
        val rows: List<RenamePreviewRow>,
        val blocked: Int,
        val unchanged: Int,
    )

    private companion object {
        /**
         * The field starts filled in rather than empty: a pattern that keeps
         * the original words and only adds a number is a real batch rename the
         * moment it is read, and nothing about the file's own name is thrown
         * away while the user is still deciding.
         */
        const val DEFAULT_PATTERN = "{name}-{n}"

        /** Nobody reads past a handful of rows to judge a pattern. */
        const val PREVIEW_LIMIT = 8
    }
}
