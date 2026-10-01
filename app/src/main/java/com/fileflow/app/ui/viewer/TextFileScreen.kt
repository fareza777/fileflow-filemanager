package com.fileflow.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.WrapText
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.ProgressPanel
import com.fileflow.app.ui.util.FileIntents
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

data class TextState(
    val name: String = "",
    val path: String = "",
    val content: String = "",
    val loading: Boolean = true,
    val truncated: Boolean = false,
    val binary: Boolean = false,
)

@HiltViewModel
class TextViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(TextState())
    val state: StateFlow<TextState> = _state.asStateFlow()

    private val path: String = savedStateHandle.get<String>(ARG_PATH) ?: ""

    init {
        viewModelScope.launch {
            val file = File(path)
            val loaded = withContext(Dispatchers.IO) { read(file) }
            _state.update {
                it.copy(
                    name = file.name,
                    path = path,
                    content = loaded.text,
                    truncated = loaded.truncated,
                    binary = loaded.binary,
                    loading = false,
                )
            }
        }
    }

    private data class Loaded(val text: String, val truncated: Boolean, val binary: Boolean)

    /**
     * Reads a prefix rather than the whole file: opening a 400 MB log to look at
     * the first screen of it would be a self-inflicted freeze. NUL bytes are the
     * giveaway that this is not text at all.
     */
    private fun read(file: File): Loaded {
        if (!file.isFile || !file.canRead()) {
            return Loaded("", false, false)
        }
        return runCatching {
            val length = file.length()
            val limit = minOf(length, MAX_BYTES.toLong()).toInt()
            val bytes = ByteArray(limit)
            file.inputStream().use { input ->
                var read = 0
                while (read < limit) {
                    val step = input.read(bytes, read, limit - read)
                    if (step <= 0) break
                    read += step
                }
                if (read < limit) return@runCatching Loaded("", false, true)
            }
            // NUL bytes are the giveaway that this is not text at all.
            val probe = minOf(bytes.size, 4096)
            for (index in 0 until probe) {
                if (bytes[index] == 0.toByte()) return@runCatching Loaded("", false, true)
            }
            val text = runCatching { bytes.toString(Charsets.UTF_8) }
                .getOrElse { bytes.toString(Charsets.ISO_8859_1) }
            Loaded(text, length > limit, false)
        }.getOrElse { Loaded("", false, false) }
    }

    companion object {
        const val ARG_PATH = "path"
        private const val MAX_BYTES = 512 * 1024
    }
}

/**
 * The style file contents are read in.
 *
 * Named rather than inlined because it is the one place in the app that
 * deliberately steps off the type scale: monospace glyphs have a wider advance
 * and a larger optical size than the prose font, so the body size looks bigger
 * here than it does in a list, and a slightly smaller setting with a tighter
 * leading keeps a code file legible without turning three lines into a screen.
 */
private val ViewerText = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    letterSpacing = 0.sp,
)

/**
 * Reading a .txt, .json or .log used to mean handing the file to another app.
 * Seeing it in place is a small screen that removes a whole category of
 * round-trips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextFileScreen(
    onBack: () -> Unit,
    viewModel: TextViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var wrap by rememberSaveable { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.name.ifBlank { stringResource(R.string.viewer_text_title) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (state.content.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.viewer_line_count,
                                    state.content.count { it == '\n' } + 1,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { wrap = !wrap }) {
                        Icon(
                            imageVector = Icons.Filled.WrapText,
                            contentDescription = stringResource(R.string.viewer_wrap),
                            tint = if (wrap) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(
                        onClick = {
                            FileIntents.share(
                                context,
                                listOf(
                                    com.fileflow.app.core.model.FileItem(
                                        path = state.path,
                                        name = state.name,
                                        isDirectory = false,
                                        sizeBytes = 0L,
                                        lastModified = 0L,
                                        extension = state.name.substringAfterLast('.', ""),
                                        mimeType = "text/plain",
                                        category = com.fileflow.app.core.model.FileCategory.DOCUMENTS,
                                        isHidden = false,
                                    )
                                ),
                            )
                        },
                    ) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.cd_share),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> ProgressPanel(
                    title = stringResource(R.string.common_loading),
                    detail = null,
                    fraction = null,
                    modifier = Modifier.padding(16.dp),
                )

                state.binary -> EmptyState(
                    icon = Icons.Filled.WrapText,
                    title = stringResource(R.string.viewer_binary_title),
                    body = stringResource(R.string.viewer_binary_body),
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Text(
                        text = state.content,
                        style = ViewerText,
                        softWrap = wrap,
                        modifier = if (wrap) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.horizontalScroll(rememberScrollState())
                        },
                    )
                    if (state.truncated) {
                        Text(
                            text = stringResource(R.string.viewer_truncated),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 16.dp)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}