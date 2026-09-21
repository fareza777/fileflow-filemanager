package com.fileflow.app.ui.inspect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.analysis.FileTypes
import com.fileflow.app.core.analysis.FuzzyHash
import com.fileflow.app.core.analysis.MerkleFingerprint
import com.fileflow.app.core.analysis.TypeVerdict
import com.fileflow.app.core.model.SortOrder
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.domain.scan.FastDuplicateGroup
import com.fileflow.app.domain.scan.FastDuplicateScanner
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.LoadingState
import com.fileflow.app.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of the three inspections the screen is showing. */
enum class InspectMode { TYPES, DUPLICATES, SIMILAR }

data class TypeRow(
    val path: String,
    val name: String,
    val verdict: TypeVerdict,
)

data class SimilarPair(
    val leftName: String,
    val leftPath: String,
    val rightName: String,
    val rightPath: String,
    val distance: Int,
)

@androidx.compose.runtime.Immutable
data class InspectState(
    val folder: String = "",
    val mode: InspectMode = InspectMode.TYPES,
    val running: Boolean = false,
    val progress: String? = null,
    val types: List<TypeRow> = emptyList(),
    val liars: Int = 0,
    val duplicates: List<FastDuplicateGroup> = emptyList(),
    val similar: List<SimilarPair> = emptyList(),
    val fingerprint: String? = null,
    val scanned: Int = 0,
    val error: String? = null,
)

@HiltViewModel
class InspectViewModel @Inject constructor(
    private val browseRepository: BrowseRepository,
    private val duplicates: FastDuplicateScanner,
) : ViewModel() {

    private val _state = MutableStateFlow(InspectState())
    val state: StateFlow<InspectState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val root = browseRepository.primaryRoot()
            _state.update { it.copy(folder = root) }
        }
    }

    fun setFolder(path: String) {
        _state.update { it.copy(folder = path, types = emptyList(), duplicates = emptyList(), similar = emptyList(), fingerprint = null, liars = 0) }
    }

    fun setMode(mode: InspectMode) {
        _state.update { it.copy(mode = mode) }
    }

    /**
     * Everything here runs on one listing of the chosen folder: three passes
     * over the same files would read each of them three times, and the phone's
     * storage is the slowest part of all three.
     */
    fun run() {
        val folder = _state.value.folder
        if (folder.isBlank() || _state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true, error = null, progress = null) }
            try {
                val items = browseRepository.listItems(folder, includeHidden = false, sort = SortOrder())
                val files = items.filter { !it.isDirectory }
                _state.update { it.copy(scanned = files.size) }

                when (_state.value.mode) {
                    InspectMode.TYPES -> inspectTypes(files.map { it.path to it.name })
                    InspectMode.DUPLICATES -> inspectDuplicates(folder)
                    InspectMode.SIMILAR -> inspectSimilar(files.map { it.path to it.name })
                }

                val node = MerkleFingerprint.of(File(folder))
                _state.update { it.copy(fingerprint = node?.hash, running = false, progress = null) }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(running = false, progress = null, error = error.message ?: "The scan failed")
                }
            }
        }
    }

    private suspend fun inspectTypes(files: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        val rows = mutableListOf<TypeRow>()
        files.forEachIndexed { index, (path, name) ->
            _state.update { it.copy(progress = name) }
            if (index % 25 == 0) {
                // The list fills as it goes: a folder of thousands should not
                // look frozen for ten seconds.
                _state.update { it.copy(types = rows.toList()) }
            }
            val file = File(path)
            val verdict = runCatching { FileTypes.detect(file) }.getOrNull() ?: return@forEachIndexed
            rows += TypeRow(path = path, name = name, verdict = verdict)
        }
        _state.update {
            it.copy(types = rows, liars = rows.count { row -> row.verdict.extensionLies })
        }
    }

    private suspend fun inspectDuplicates(folder: String) {
        _state.update { it.copy(progress = folder) }
        val groups = duplicates.scan(
            roots = listOf(folder),
            onProgress = { scanned, candidates ->
                _state.update { it.copy(progress = "$scanned files · $candidates candidates") }
            },
        )
        _state.update { it.copy(duplicates = groups) }
    }

    /**
     * Pairwise fuzzy distance. Quadratic in the number of files, so the pass is
     * bounded: a folder with ten thousand documents would be fifty million
     * comparisons, and the honest thing is to inspect the newest few hundred
     * rather than to run for a minute with no progress bar.
     */
    private suspend fun inspectSimilar(files: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        val limited = files.take(MAX_SIMILAR_FILES)
        val digests = limited.mapIndexedNotNull { index, (path, name) ->
            _state.update { it.copy(progress = "$name (${index + 1}/${limited.size})") }
            val digest = runCatching { FuzzyHash.digest(File(path)) }.getOrNull()
            if (digest == null) null else Triple(path, name, digest)
        }

        val pairs = mutableListOf<SimilarPair>()
        for (left in digests.indices) {
            for (right in left + 1 until digests.size) {
                val distance = FuzzyHash.distance(digests[left].third, digests[right].third)
                if (distance <= FuzzyHash.SIMILAR_THRESHOLD) {
                    pairs += SimilarPair(
                        leftName = digests[left].second,
                        leftPath = digests[left].first,
                        rightName = digests[right].second,
                        rightPath = digests[right].first,
                        distance = distance,
                    )
                }
            }
        }
        _state.update { it.copy(similar = pairs.sortedBy { pair -> pair.distance }) }
    }

    private companion object {
        const val MAX_SIMILAR_FILES = 400
    }
}

/**
 * What the files actually are, not what they are called.
 *
 * Three inspections that all answer "can I trust this folder": the true type
 * behind each extension, which files look like copies of each other before a
 * byte-for-byte comparison, and which documents are near-duplicates of each
 * other rather than exact copies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectScreen(
    onBack: () -> Unit,
    viewModel: InspectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    val folderName = state.folder.substringAfterLast('/').ifEmpty { state.folder }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showPicker = true }) {
                        Text(stringResource(R.string.inspect_title))
                        Text(
                            text = folderName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showPicker = true }) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = stringResource(R.string.compress_change_folder),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.mode == InspectMode.TYPES,
                    onClick = { viewModel.setMode(InspectMode.TYPES) },
                    label = { Text(stringResource(R.string.inspect_types)) },
                )
                FilterChip(
                    selected = state.mode == InspectMode.DUPLICATES,
                    onClick = { viewModel.setMode(InspectMode.DUPLICATES) },
                    label = { Text(stringResource(R.string.inspect_duplicates)) },
                )
                FilterChip(
                    selected = state.mode == InspectMode.SIMILAR,
                    onClick = { viewModel.setMode(InspectMode.SIMILAR) },
                    label = { Text(stringResource(R.string.inspect_similar)) },
                )
            }

            Button(
                onClick = viewModel::run,
                enabled = !state.running && state.folder.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (state.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(state.progress ?: stringResource(R.string.inspect_running))
                } else {
                    Icon(Icons.Filled.Troubleshoot, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.inspect_run))
                }
            }

            state.fingerprint?.let { fingerprint ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Fingerprint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.inspect_fingerprint),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = fingerprint.take(32),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            when {
                state.error != null -> EmptyState(
                    icon = Icons.Filled.Policy,
                    title = stringResource(R.string.common_error),
                    body = state.error,
                )

                state.running && state.types.isEmpty() && state.duplicates.isEmpty() && state.similar.isEmpty() ->
                    LoadingState(label = state.progress ?: stringResource(R.string.inspect_running))

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when (state.mode) {
                        InspectMode.TYPES -> {
                            item {
                                SectionHeader(
                                    stringResource(R.string.inspect_types_summary, state.scanned, state.liars)
                                )
                            }
                            items(state.types, key = { it.path }) { row ->
                                TypeRowItem(row)
                            }
                        }

                        InspectMode.DUPLICATES -> {
                            item {
                                SectionHeader(
                                    stringResource(R.string.inspect_duplicates_summary, state.duplicates.size)
                                )
                            }
                            items(state.duplicates, key = { it.fingerprint }) { group ->
                                DuplicateGroupItem(group)
                            }
                        }

                        InspectMode.SIMILAR -> {
                            item {
                                SectionHeader(
                                    stringResource(R.string.inspect_similar_summary, state.similar.size)
                                )
                            }
                            items(state.similar) { pair ->
                                SimilarPairItem(pair)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        FolderPickerSheet(
            startPath = state.folder.ifBlank { "/" },
            title = stringResource(R.string.inspect_pick_folder),
            onDismiss = { showPicker = false },
            onPick = { path ->
                viewModel.setFolder(path)
                showPicker = false
            },
        )
    }
}

@Composable
private fun TypeRowItem(row: TypeRow) {
    val tint = when {
        row.verdict.extensionLies -> MaterialTheme.colorScheme.error
        row.verdict.unknown -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${row.verdict.description} · ${row.verdict.mimeType}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.verdict.extensionLies) {
            Text(
                text = stringResource(
                    R.string.inspect_lies,
                    row.verdict.detectedExtension ?: "?",
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DuplicateGroupItem(group: FastDuplicateGroup) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.07f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.inspect_group, group.files.size),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.inspect_wasted, Formatters.bytes(group.wastedBytes)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        group.files.take(6).forEach { candidate ->
            Text(
                text = candidate.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (group.files.size > 6) {
            Text(
                text = stringResource(R.string.inspect_more, group.files.size - 6),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SimilarPairItem(pair: SimilarPair) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.07f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = pair.leftName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = pair.rightName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.inspect_distance, pair.distance),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
