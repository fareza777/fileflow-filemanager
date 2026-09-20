package com.fileflow.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fileflow.app.R
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.data.repo.BrowseRepository
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

data class ViewerState(
    val images: List<FileItem> = emptyList(),
    val index: Int = 0,
    val loading: Boolean = true,
)

@HiltViewModel
class ImageViewerViewModel @Inject constructor(
    private val browseRepository: BrowseRepository,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(ViewerState())
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val folder: String = savedStateHandle.get<String>(ARG_FOLDER) ?: ""
    private val startPath: String = savedStateHandle.get<String>(ARG_PATH) ?: ""

    init {
        viewModelScope.launch {
            val siblings = withContext(Dispatchers.IO) {
                browseRepository.listItems(
                    folder,
                    includeHidden = false,
                    sort = com.fileflow.app.core.model.SortOrder(),
                ).filter { !it.isDirectory && it.category == FileCategory.IMAGES }
            }
            val index = siblings.indexOfFirst { it.path == startPath }.coerceAtLeast(0)
            _state.update { it.copy(images = siblings, index = index, loading = false) }
        }
    }

    companion object {
        const val ARG_FOLDER = "folder"
        const val ARG_PATH = "path"
    }
}

/**
 * A viewer rather than a hand-off.
 *
 * Tapping a photo used to launch the system gallery, which meant leaving the
 * folder — and the app — to look at one file. Swiping straight through the
 * pictures that sit next to each other is what a file manager should do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    onBack: () -> Unit,
    viewModel: ImageViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = state.index,
        pageCount = { state.images.size.coerceAtLeast(1) },
    )

    // The listing arrives after the first frame; jump to the tapped image once
    // it is known.
    LaunchedEffect(state.index, state.images.size) {
        if (state.images.isNotEmpty() && pagerState.currentPage != state.index) {
            pagerState.scrollToPage(state.index)
        }
    }

    val current = state.images.getOrNull(pagerState.currentPage)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                title = {
                    Text(
                        text = current?.name ?: stringResource(R.string.viewer_loading),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (state.images.size > 1) {
                        Text(
                            text = "${pagerState.currentPage + 1}/${state.images.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    current?.let { item ->
                        IconButton(onClick = { FileIntents.share(context, listOf(item)) }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.cd_share))
                        }
                        IconButton(onClick = { FileIntents.openWith(context, item) }) {
                            // Was an Info glyph, which says nothing about what
                            // it does and says it to nobody.
                            Icon(
                                Icons.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.cd_open_with),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(state = pagerState) { page ->
                val item = state.images.getOrNull(page) ?: return@HorizontalPager
                ZoomableImage(item = item)
            }
        }
    }
}

@Composable
private fun ZoomableImage(item: FileItem) {
    val context = LocalContext.current
    var scale by remember(item.path) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.path) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.path) { mutableFloatStateOf(0f) }

    val request = remember(item.path) {
        ImageRequest.Builder(context)
            .data(File(item.path))
            .crossfade(true)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.path) {
                // Hand-rolled instead of detectTransformGestures, which
                // consumes every move unconditionally — including the plain
                // horizontal drags the pager needs to turn the page. Events
                // are only consumed once a pinch starts or the picture is
                // already zoomed in, so at 1x the swipe belongs to the pager
                // and the two gestures stop fighting.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pinching = false
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (zoomChange != 1f) pinching = true
                        if (pinching || scale > 1f) {
                            scale = (scale * zoomChange).coerceIn(1f, 6f)
                            if (scale > 1f) {
                                offsetX += panChange.x
                                offsetY += panChange.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale <= 1f) {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}