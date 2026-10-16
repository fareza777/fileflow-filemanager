package com.fileflow.app.ui.settings

import android.app.Activity
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.BuildConfig
import com.fileflow.app.R
import com.fileflow.app.core.storage.StorageAccess
import com.fileflow.app.data.prefs.ColorTheme
import com.fileflow.app.data.prefs.RenameConvention
import com.fileflow.app.data.prefs.SettingsRepository
import com.fileflow.app.data.prefs.ThemeMode
import com.fileflow.app.data.prefs.UserSettings
import com.fileflow.app.data.repo.FileIndexRepository
import com.fileflow.app.ui.components.InlineAction
import com.fileflow.app.ui.components.AdBanner
import com.fileflow.app.ui.components.AdsConsent
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.SectionCard
import com.fileflow.app.ui.theme.PaletteColors
import com.fileflow.app.ui.theme.paletteColors
import com.fileflow.app.ui.rememberCanPostNotifications
import com.fileflow.app.ui.rememberNotificationPermissionRequest
import com.fileflow.app.ui.requestAllFilesAccess
import com.fileflow.app.ui.util.AppLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class SettingsState(
    val settings: UserSettings = UserSettings(),
    val hasAccess: Boolean = true,
    val loaded: Boolean = false,
    val indexedCount: Int = 0,
    val indexing: Boolean = false,
    val indexProgress: Int = 0,
    val indexError: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val storageAccess: StorageAccess,
    private val indexRepository: FileIndexRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _state.update {
                    it.copy(settings = settings, loaded = true, hasAccess = storageAccess.hasAccess())
                }
            }
        }
        viewModelScope.launch {
            indexRepository.observeCount().collect { count ->
                _state.update { it.copy(indexedCount = count) }
            }
        }
    }

    fun rebuildIndex() {
        if (_state.value.indexing) return
        viewModelScope.launch {
            _state.update { it.copy(indexing = true, indexProgress = 0) }
            // A failure used to be caught and turned into a count of zero, which
            // the screen renders as "nothing is indexed" — indistinguishable
            // from a successful build of an empty device.
            val result = runCatching {
                indexRepository.rebuild { done -> _state.update { it.copy(indexProgress = done) } }
            }
            _state.update { it.copy(indexing = false) }
            result.fold(
                onSuccess = { total ->
                    _state.update { it.copy(indexedCount = total, indexError = false) }
                },
                onFailure = {
                    // Shown, not turned into a count of zero: the screen renders
                    // zero as "nothing is indexed", which is indistinguishable
                    // from a successful build of an empty device.
                    _state.update { it.copy(indexError = true) }
                },
            )
        }
    }

    fun refreshAccess() {
        _state.update { it.copy(hasAccess = storageAccess.hasAccess()) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setColorTheme(theme: ColorTheme) {
        viewModelScope.launch { repository.setColorTheme(theme) }
    }

    fun setShowHidden(enabled: Boolean) {
        viewModelScope.launch { repository.setShowHidden(enabled) }
    }

    fun setThumbnails(enabled: Boolean) {
        viewModelScope.launch { repository.setThumbnails(enabled) }
    }

    fun setConfirmDelete(enabled: Boolean) {
        viewModelScope.launch { repository.setConfirmDelete(enabled) }
    }

    fun setPreviewRequired(enabled: Boolean) {
        viewModelScope.launch { repository.setPreviewRequired(enabled) }
    }

    fun setUseTrash(enabled: Boolean) {
        viewModelScope.launch { repository.setUseTrash(enabled) }
    }

    fun setRetention(days: Int) {
        viewModelScope.launch { repository.setTrashRetentionDays(days) }
    }

    fun setNotify(enabled: Boolean) {
        viewModelScope.launch { repository.setNotifyRuleRuns(enabled) }
    }

    fun setRenameConvention(convention: RenameConvention) {
        viewModelScope.launch { repository.setRenameConvention(convention) }
    }

    fun reset() {
        viewModelScope.launch { repository.resetAll() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAbout: () -> Unit,
    onOpenTrash: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val hasAccess = com.fileflow.app.ui.rememberStorageAccess()
    var retention by remember(state.settings.trashRetentionDays) {
        mutableFloatStateOf(state.settings.trashRetentionDays.toFloat())
    }
    var confirmReset by remember { mutableStateOf(false) }
    // Observed rather than read once: the answer changes while the user is away
    // in the system dialog, and the row has to notice when they come back.
    val canNotify = rememberCanPostNotifications()
    val requestNotifications = rememberNotificationPermissionRequest { granted ->
        // Turning the preference back off when the user declines keeps the two
        // facts in step: a switch that reads ON but can never deliver is the
        // exact bug this row now guards against.
        if (!granted) viewModel.setNotify(false)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.padding(top = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_intro_title), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.settings_intro_desc), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(stringResource(R.string.settings_local_badge),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            item {
                MenuCard(
                    title = stringResource(R.string.settings_storage_access),
                    icon = Icons.Filled.FolderOpen,
                    summary = stringResource(if (hasAccess) R.string.settings_storage_granted else R.string.settings_storage_missing),
                    iconTint = if (hasAccess) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                requestAllFilesAccess(context)
                                viewModel.refreshAccess()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                if (hasAccess) R.string.settings_storage_granted
                                else R.string.settings_storage_missing
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hasAccess) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    }
                }
            }

            item {
                MenuCard(
                    title = stringResource(R.string.settings_appearance),
                    icon = Icons.Filled.Palette,
                    summary = labelForTheme(state.settings.themeMode) + " · " + labelForColorTheme(state.settings.colorTheme),
                    initiallyExpanded = true,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.settings_theme),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.settings.themeMode == mode,
                                    onClick = { viewModel.setTheme(mode) },
                                    label = { Text(labelForTheme(mode)) },
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.settings_color_theme),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        PaletteRow(
                            current = state.settings.colorTheme,
                            onSelect = viewModel::setColorTheme,
                        )
                    }
                }
            }

            item {
                MenuCard(
                    title = stringResource(R.string.settings_browsing),
                    icon = Icons.Filled.Tune,
                    summary = stringResource(R.string.settings_browsing_summary),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SwitchRow(
                            title = stringResource(R.string.settings_show_hidden),
                            subtitle = stringResource(R.string.settings_show_hidden_desc),
                            checked = state.settings.showHidden,
                            onChange = viewModel::setShowHidden,
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_thumbnails),
                            checked = state.settings.thumbnails,
                            onChange = viewModel::setThumbnails,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.settings_index),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = when {
                                    state.indexing -> stringResource(
                                        R.string.settings_index_running,
                                        state.indexProgress,
                                    )
                                    state.indexError -> stringResource(R.string.error_index_failed)
                                    state.indexedCount > 0 -> stringResource(
                                        R.string.settings_index_status,
                                        state.indexedCount,
                                    )
                                    else -> stringResource(R.string.settings_index_empty)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.indexError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = stringResource(R.string.settings_index_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = viewModel::rebuildIndex,
                            enabled = !state.indexing,
                        ) {
                            Text(stringResource(R.string.settings_index_rebuild))
                        }
                    }
                }
            }

            item {
                MenuCard(
                    title = stringResource(R.string.settings_safety),
                    icon = Icons.Filled.Shield,
                    summary = stringResource(R.string.settings_safety_summary),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SwitchRow(
                            title = stringResource(R.string.settings_confirm_delete),
                            checked = state.settings.confirmDelete,
                            onChange = viewModel::setConfirmDelete,
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_preview_required),
                            subtitle = stringResource(R.string.settings_preview_required_desc),
                            checked = state.settings.previewRequired,
                            onChange = viewModel::setPreviewRequired,
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_use_trash),
                            checked = state.settings.useTrash,
                            onChange = viewModel::setUseTrash,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.settings_trash_retention),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_days,
                                    retention.toInt(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = retention,
                                onValueChange = { retention = it },
                                onValueChangeFinished = { viewModel.setRetention(retention.toInt()) },
                                enabled = state.settings.useTrash,
                                valueRange = 1f..90f,
                                steps = 88,
                            )
                        }
                        TextButton(onClick = onOpenTrash) {
                            Text(stringResource(R.string.tool_trash))
                        }
                    }
                }
            }

            item {
                MenuCard(
                    title = stringResource(R.string.tool_rename),
                    icon = Icons.Filled.Edit,
                    summary = labelForConvention(state.settings.renameConvention),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RenameConvention.entries.forEach { convention ->
                            FilterChip(
                                selected = state.settings.renameConvention == convention,
                                onClick = { viewModel.setRenameConvention(convention) },
                                label = { Text(labelForConvention(convention)) },
                            )
                        }
                    }
                }
            }

            item {
                MenuCard(
                    title = stringResource(R.string.settings_privacy),
                    icon = Icons.Filled.PrivacyTip,
                    summary = stringResource(R.string.settings_privacy_summary),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // The preference and the permission are two different
                        // things, and only their intersection is real. Showing
                        // the switch as ON while Android drops every notification
                        // was a lie the user had no way to catch.
                        val notifyBlocked = state.settings.notifyRuleRuns && !canNotify
                        SwitchRow(
                            title = stringResource(R.string.settings_notifications),
                            checked = state.settings.notifyRuleRuns && canNotify,
                            onChange = { enabled ->
                                viewModel.setNotify(enabled)
                                if (enabled) requestNotifications()
                            },
                        )
                        if (notifyBlocked) {
                            InlineAction(
                                text = stringResource(R.string.settings_notifications_blocked),
                                onClick = { requestNotifications() },
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_ads_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Google requires a way to change or withdraw ad consent
                        // wherever ads are served; this is that way.
                        if (activity != null) {
                            LinkRow(
                                label = stringResource(R.string.settings_ad_choices),
                                onClick = { AdsConsent.showPrivacyOptions(activity) },
                            )
                        }
                        LinkRow(
                            label = stringResource(R.string.settings_privacy_policy),
                            onClick = { AppLinks.openPrivacyPolicy(context) },
                        )
                    }
                }
            }

            item {
                MenuCard(
                    title = stringResource(R.string.settings_about),
                    icon = Icons.Filled.Info,
                    summary = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onOpenAbout)
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.about_title),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_version,
                                    BuildConfig.VERSION_NAME,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { confirmReset = true }) {
                            Text(
                                text = stringResource(R.string.settings_reset),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item { AdBanner() }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.settings_reset),
            body = stringResource(R.string.settings_reset_confirm),
            confirmLabel = stringResource(R.string.settings_reset),
            onConfirm = {
                viewModel.reset()
                confirmReset = false
            },
            onDismiss = { confirmReset = false },
            destructive = true,
        )
    }
}

/**
 * One settings menu, as one card: title on top, its controls below it.
 *
 * The screen used to float a bare header row above each block, which read as
 * fragments of a list rather than one card per menu. Folding the header in —
 * with the same tinted icon badge the rest of the app uses — makes every menu
 * a self-contained card stacked top-down.
 */
@Composable
private fun MenuCard(
    title: String,
    icon: ImageVector,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val description = stringResource(if (expanded) R.string.settings_expanded else R.string.settings_collapsed)
    SectionCard(contentPadding = PaddingValues(12.dp)) {
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .semantics { stateDescription = description }
                    .clickable(role = Role.Button) { expanded = !expanded }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconTint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(summary, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), content = content)
            }
        }
    }
}

@Composable
private fun PaletteRow(
    current: ColorTheme,
    onSelect: (ColorTheme) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ColorTheme.entries
            // Wallpaper colours need Android 12; offering the swatch below that
            // would be a choice that silently does nothing.
            .filter { it != ColorTheme.DYNAMIC || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
            .forEach { theme ->
                PaletteChip(
                    theme = theme,
                    selected = current == theme,
                    onClick = { onSelect(theme) },
                )
            }
    }
}

@Composable
private fun PaletteChip(
    theme: ColorTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The two non-palette entries have no hexes of their own, so the swatch
    // shows what those choices actually produce right now.
    val colors = paletteColors(theme)
        ?: PaletteColors(scheme.primary, scheme.secondary, scheme.tertiary)
    val shape = RoundedCornerShape(18.dp)

    Surface(
        modifier = Modifier
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) {
            scheme.primaryContainer.copy(alpha = 0.6f)
        } else {
            scheme.surfaceContainerLow
        },
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(colors.primary, colors.secondary, colors.tertiary).forEach { dot ->
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(dot),
                    )
                }
            }
            Text(
                text = labelForColorTheme(theme),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun labelForTheme(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
}

@Composable
private fun labelForConvention(convention: RenameConvention): String = when (convention) {
    RenameConvention.KEEP -> stringResource(R.string.rename_conv_keep)
    RenameConvention.NATURAL -> stringResource(R.string.rename_conv_natural)
    RenameConvention.TITLE_CASE -> stringResource(R.string.rename_conv_title)
    RenameConvention.KEBAB -> stringResource(R.string.rename_conv_kebab)
    RenameConvention.SNAKE -> stringResource(R.string.rename_conv_snake)
}

@Composable
private fun labelForColorTheme(theme: ColorTheme): String = when (theme) {
    ColorTheme.DEFAULT -> stringResource(R.string.palette_default)
    ColorTheme.DYNAMIC -> stringResource(R.string.palette_dynamic)
    ColorTheme.OCEAN -> stringResource(R.string.palette_ocean)
    ColorTheme.FOREST -> stringResource(R.string.palette_forest)
    ColorTheme.SUNSET -> stringResource(R.string.palette_sunset)
    ColorTheme.BERRY -> stringResource(R.string.palette_berry)
    ColorTheme.SAND -> stringResource(R.string.palette_sand)
}
