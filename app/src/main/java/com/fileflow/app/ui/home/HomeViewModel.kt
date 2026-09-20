package com.fileflow.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileflow.app.core.model.FileItem
import com.fileflow.app.core.model.VolumeInfo
import com.fileflow.app.data.db.ShortcutEntity
import com.fileflow.app.data.prefs.SettingsRepository
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.TrashRepository
import com.fileflow.app.domain.scan.InsightsScanner
import com.fileflow.app.domain.scan.QuickInsights
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaceEntry(val name: String, val path: String, val isShortcut: Boolean)

@androidx.compose.runtime.Immutable
data class HomeState(
    val loading: Boolean = true,
    val volumes: List<VolumeInfo> = emptyList(),
    val places: List<PlaceEntry> = emptyList(),
    val recent: List<FileItem> = emptyList(),
    val insights: QuickInsights? = null,
    val scanning: Boolean = false,
    val scannedCount: Int = 0,
    val largeThreshold: Long = 100L * 1024 * 1024,
    val trashCount: Int = 0,
    val trashBytes: Long = 0,
) {
    val primaryVolume: VolumeInfo?
        get() = volumes.firstOrNull { it.isPrimary } ?: volumes.firstOrNull()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val browseRepository: BrowseRepository,
    private val trashRepository: TrashRepository,
    settingsRepository: SettingsRepository,
    private val insightsScanner: InsightsScanner,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var shortcuts: List<ShortcutEntity> = emptyList()
    private var scanRequested = false

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.update { it.copy(largeThreshold = settings.largeFileThreshold) }
            }
        }
        viewModelScope.launch {
            browseRepository.observeShortcuts().collect { list ->
                shortcuts = list
                rebuildPlaces()
            }
        }
        viewModelScope.launch {
            trashRepository.observeCount().collect { count ->
                _state.update { it.copy(trashCount = count) }
            }
        }
        viewModelScope.launch {
            trashRepository.observeSize().collect { bytes ->
                _state.update { it.copy(trashBytes = bytes) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val volumes = browseRepository.volumes()
            val recent = browseRepository.recentFiles(limit = 12)
            _state.update {
                it.copy(
                    loading = false,
                    volumes = volumes,
                    recent = recent,
                )
            }
            rebuildPlaces()
        }
        if (_state.value.insights == null && !_state.value.scanning && !scanRequested) {
            scanRequested = true
            // Let the first frame land: a full storage walk competing with
            // layout is the difference between "instant" and "why is it slow".
            viewModelScope.launch {
                kotlinx.coroutines.delay(FIRST_SCAN_DELAY_MS)
                runScan()
            }
        }
    }

    fun runScan() {
        if (_state.value.scanning) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update { it.copy(scanning = true, scannedCount = 0) }

            // One walk, and the folders people actually use come first, so a
            // usable number lands early without a second sweep to produce it.
            // The previous version swept the hot folders and then swept them
            // again as part of the full device, reading every one of those
            // directories twice.
            val result = runCatching {
                insightsScanner.scan(
                    roots = insightsScanner.walkRoots(),
                    hotRoots = insightsScanner.hotRoots(),
                    largeThreshold = _state.value.largeThreshold,
                    onProgress = { seen -> _state.update { it.copy(scannedCount = seen) } },
                    onHotResult = { hot ->
                        if (hot.scannedFiles > 0) _state.update { it.copy(insights = hot) }
                    },
                )
            }.getOrNull()

            _state.update {
                it.copy(scanning = false, insights = result ?: it.insights)
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = false) }
    }

    fun isShortcut(path: String): Boolean = shortcuts.any { it.path == path }

    fun toggleShortcutForPlace(path: String) {
        viewModelScope.launch {
            if (isShortcut(path)) browseRepository.removeShortcut(path)
            else browseRepository.addShortcut(path)
        }
    }

    private suspend fun rebuildPlaces() {
        val defaults = browseRepository.defaultPlaces()
        val shortcutEntries = shortcuts.map { PlaceEntry(it.name, it.path, true) }
        val defaultEntries = defaults
            .filter { place -> shortcutEntries.none { it.path == place.second } }
            .map { PlaceEntry(it.first, it.second, false) }
        _state.update { it.copy(places = shortcutEntries + defaultEntries) }
    }

    private companion object {
        /**
         * How long to leave the app alone before walking the device.
         *
         * The first seconds after launch belong to the first frames and the
         * entrance animation. Starting a full storage walk into that window is
         * what made opening the app stutter — the walk now waits until the
         * screen has settled, and the metrics fill in a moment later.
         */
        const val FIRST_SCAN_DELAY_MS = 2_600L
    }
}
