package com.fileflow.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fileflow.app.core.model.SortDirection
import com.fileflow.app.core.model.SortField
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The palettes offered in Settings. Wallpaper colours are one of the choices
 * rather than a switch of their own: "which colours" is a single question, and
 * a toggle next to a list of themes made it two controls that could disagree.
 */
enum class ColorTheme {
    DEFAULT, DYNAMIC, OCEAN, FOREST, SUNSET, BERRY, SAND;

    companion object {
        fun fromKey(key: String?): ColorTheme =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

enum class RenameConvention {
    KEEP, NATURAL, TITLE_CASE, KEBAB, SNAKE;

    companion object {
        /**
         * KEEP is the default on purpose: out of the box the smart renamer
         * should fix broken names, not restyle names that were already fine.
         */
        fun fromKey(key: String?): RenameConvention =
            entries.firstOrNull { it.name == key } ?: KEEP
    }
}

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorTheme: ColorTheme = ColorTheme.DEFAULT,
    val showHidden: Boolean = false,
    val thumbnails: Boolean = true,
    val sortField: SortField = SortField.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val confirmDelete: Boolean = true,
    val previewRequired: Boolean = true,
    val useTrash: Boolean = true,
    val trashRetentionDays: Int = 30,
    val notifyRuleRuns: Boolean = true,
    val onboardingDone: Boolean = false,
    val renameConvention: RenameConvention = RenameConvention.KEEP,
    val renameDatePrefix: Boolean = false,
    val largeFileThreshold: Long = 100L * 1024 * 1024,
    val gridView: Boolean = false,
    /**
     * The folder the transfer server shares. Kept in settings rather than in the
     * transfer screen because the rest of the app uses it as a destination:
     * "move to the PC folder" is one tap on a selection anywhere.
     */
    val transferFolder: String = "",
) {
    val sortOrder: com.fileflow.app.core.model.SortOrder
        get() = com.fileflow.app.core.model.SortOrder(sortField, sortDirection)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fileflow_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val COLOR_THEME = stringPreferencesKey("color_theme")
        /** Written by the switch the palette picker replaced; read for migration only. */
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val THUMBNAILS = booleanPreferencesKey("thumbnails")
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_DIRECTION = stringPreferencesKey("sort_direction")
        val CONFIRM_DELETE = booleanPreferencesKey("confirm_delete")
        val PREVIEW_REQUIRED = booleanPreferencesKey("preview_required")
        val USE_TRASH = booleanPreferencesKey("use_trash")
        val TRASH_RETENTION = intPreferencesKey("trash_retention_days")
        val NOTIFY_RULES = booleanPreferencesKey("notify_rules")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val RENAME_CONVENTION = stringPreferencesKey("rename_convention")
        val RENAME_DATE_PREFIX = booleanPreferencesKey("rename_date_prefix")
        val LARGE_THRESHOLD = longPreferencesKey("large_threshold")
        val GRID_VIEW = booleanPreferencesKey("grid_view")
        val TRANSFER_FOLDER = stringPreferencesKey("transfer_folder")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            // A user who had wallpaper colours switched on keeps them: the old
            // boolean is the fallback when no palette has been chosen yet.
            colorTheme = prefs[Keys.COLOR_THEME]
                ?.let { runCatching { ColorTheme.valueOf(it) }.getOrNull() }
                ?: if (prefs[Keys.DYNAMIC] == true) ColorTheme.DYNAMIC else ColorTheme.DEFAULT,
            showHidden = prefs[Keys.SHOW_HIDDEN] ?: false,
            thumbnails = prefs[Keys.THUMBNAILS] ?: true,
            sortField = prefs[Keys.SORT_FIELD]?.let {
                runCatching { SortField.valueOf(it) }.getOrNull()
            } ?: SortField.NAME,
            sortDirection = prefs[Keys.SORT_DIRECTION]?.let {
                runCatching { SortDirection.valueOf(it) }.getOrNull()
            } ?: SortDirection.ASCENDING,
            confirmDelete = prefs[Keys.CONFIRM_DELETE] ?: true,
            previewRequired = prefs[Keys.PREVIEW_REQUIRED] ?: true,
            useTrash = prefs[Keys.USE_TRASH] ?: true,
            trashRetentionDays = prefs[Keys.TRASH_RETENTION] ?: 30,
            notifyRuleRuns = prefs[Keys.NOTIFY_RULES] ?: true,
            onboardingDone = prefs[Keys.ONBOARDING] ?: false,
            renameConvention = RenameConvention.fromKey(prefs[Keys.RENAME_CONVENTION]),
            renameDatePrefix = prefs[Keys.RENAME_DATE_PREFIX] ?: false,
            largeFileThreshold = prefs[Keys.LARGE_THRESHOLD] ?: (100L * 1024 * 1024),
            gridView = prefs[Keys.GRID_VIEW] ?: false,
            transferFolder = prefs[Keys.TRANSFER_FOLDER] ?: "",
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = put { it[Keys.THEME] = mode.name }
    suspend fun setColorTheme(theme: ColorTheme) = put { it[Keys.COLOR_THEME] = theme.name }
    suspend fun setShowHidden(enabled: Boolean) = put { it[Keys.SHOW_HIDDEN] = enabled }
    suspend fun setThumbnails(enabled: Boolean) = put { it[Keys.THUMBNAILS] = enabled }
    suspend fun setConfirmDelete(enabled: Boolean) = put { it[Keys.CONFIRM_DELETE] = enabled }
    suspend fun setPreviewRequired(enabled: Boolean) = put { it[Keys.PREVIEW_REQUIRED] = enabled }
    suspend fun setUseTrash(enabled: Boolean) = put { it[Keys.USE_TRASH] = enabled }
    suspend fun setNotifyRuleRuns(enabled: Boolean) = put { it[Keys.NOTIFY_RULES] = enabled }
    suspend fun setOnboardingDone(done: Boolean) = put { it[Keys.ONBOARDING] = done }
    suspend fun setLargeFileThreshold(bytes: Long) = put { it[Keys.LARGE_THRESHOLD] = bytes }
    suspend fun setGridView(grid: Boolean) = put { it[Keys.GRID_VIEW] = grid }
    suspend fun setTransferFolder(path: String) = put { it[Keys.TRANSFER_FOLDER] = path }
    suspend fun setRenameDatePrefix(enabled: Boolean) = put { it[Keys.RENAME_DATE_PREFIX] = enabled }

    suspend fun setTrashRetentionDays(days: Int) =
        put { it[Keys.TRASH_RETENTION] = days.coerceIn(0, 365) }

    suspend fun setRenameConvention(convention: RenameConvention) =
        put { it[Keys.RENAME_CONVENTION] = convention.name }

    suspend fun setSort(field: SortField, direction: SortDirection) = put {
        it[Keys.SORT_FIELD] = field.name
        it[Keys.SORT_DIRECTION] = direction.name
    }

    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
