package com.boxpix.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.uiPrefsDataStore by preferencesDataStore(name = "ui_prefs")

enum class SortOrder { NAME, DATE, SIZE }

/** UI preferences: grid density, per-folder sort, and the debug fake/real switch. */
@Singleton
class UiPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Album columns (2-4); photo grids use one more, per the design. */
    val gridColumns: Flow<Int> = context.uiPrefsDataStore.data
        .map { (it[KEY_GRID_COLUMNS] ?: DEFAULT_COLUMNS).coerceIn(MIN_COLUMNS, MAX_COLUMNS) }
        .distinctUntilChanged()

    suspend fun setGridColumns(columns: Int) {
        context.uiPrefsDataStore.edit {
            it[KEY_GRID_COLUMNS] = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        }
    }

    fun sortFor(folderPathB64: String): Flow<SortOrder> = context.uiPrefsDataStore.data
        .map { prefs ->
            prefs[sortKey(folderPathB64)]
                ?.let { stored -> SortOrder.entries.firstOrNull { it.name == stored } }
                ?: SortOrder.NAME
        }
        .distinctUntilChanged()

    suspend fun setSortFor(folderPathB64: String, order: SortOrder) {
        context.uiPrefsDataStore.edit { it[sortKey(folderPathB64)] = order.name }
    }

    /**
     * XMP write-through master switch — OFF by default (owner's decision at M6):
     * tags live in Room + tags.json; no media file is rewritten until enabled.
     */
    val xmpWriteEnabled: Flow<Boolean> = context.uiPrefsDataStore.data
        .map { it[KEY_XMP_ENABLED] ?: false }
        .distinctUntilChanged()

    suspend fun setXmpWriteEnabled(enabled: Boolean) {
        context.uiPrefsDataStore.edit { it[KEY_XMP_ENABLED] = enabled }
    }

    /** Theme: SYSTEM (default), LIGHT or DARK. */
    val themeMode: Flow<String> = context.uiPrefsDataStore.data
        .map { it[KEY_THEME] ?: THEME_SYSTEM }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: String) {
        context.uiPrefsDataStore.edit { it[KEY_THEME] = mode }
    }

    /** Accent preset key; "teal" is the design default. */
    val accentPreset: Flow<String> = context.uiPrefsDataStore.data
        .map { it[KEY_ACCENT] ?: ACCENT_DEFAULT }
        .distinctUntilChanged()

    suspend fun setAccentPreset(preset: String) {
        context.uiPrefsDataStore.edit { it[KEY_ACCENT] = preset }
    }

    val appLockEnabled: Flow<Boolean> = context.uiPrefsDataStore.data
        .map { it[KEY_APP_LOCK] ?: false }
        .distinctUntilChanged()

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.uiPrefsDataStore.edit { it[KEY_APP_LOCK] = enabled }
    }

    /** Sort mode's first-open coach marks — shown once. */
    val sortCoachSeen: Flow<Boolean> = context.uiPrefsDataStore.data
        .map { it[KEY_SORT_COACH_SEEN] ?: false }
        .distinctUntilChanged()

    suspend fun setSortCoachSeen() {
        context.uiPrefsDataStore.edit { it[KEY_SORT_COACH_SEEN] = true }
    }

    /** M7: this device is the dedicated night worker. */
    val workerModeEnabled: Flow<Boolean> = context.uiPrefsDataStore.data
        .map { it[KEY_WORKER_MODE] ?: false }
        .distinctUntilChanged()

    suspend fun setWorkerModeEnabled(enabled: Boolean) {
        context.uiPrefsDataStore.edit { it[KEY_WORKER_MODE] = enabled }
    }

    /** Stable per-installation identity, feeds the tags journal's who/what/when. */
    suspend fun deviceId(): String {
        val existing = context.uiPrefsDataStore.data.map { it[KEY_DEVICE_ID] }.first()
        if (existing != null) return existing
        val generated = "${android.os.Build.MODEL.replace(' ', '_')}-${java.util.UUID.randomUUID().toString().take(8)}"
        context.uiPrefsDataStore.edit { it[KEY_DEVICE_ID] = generated }
        return generated
    }

    /** Debug builds read this to pick the provider; release ignores it entirely. */
    val useFakeProvider: Flow<Boolean> = context.uiPrefsDataStore.data
        .map { it[KEY_USE_FAKE] ?: true }
        .distinctUntilChanged()

    suspend fun setUseFakeProvider(useFake: Boolean) {
        context.uiPrefsDataStore.edit { it[KEY_USE_FAKE] = useFake }
    }

    private fun sortKey(folderPathB64: String) = stringPreferencesKey("sort_$folderPathB64")

    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 4
        const val DEFAULT_COLUMNS = 3

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val ACCENT_DEFAULT = "teal"

        private val KEY_GRID_COLUMNS = intPreferencesKey("grid_columns")
        private val KEY_USE_FAKE = booleanPreferencesKey("use_fake_provider")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_XMP_ENABLED = booleanPreferencesKey("xmp_write_enabled")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_ACCENT = stringPreferencesKey("accent_preset")
        private val KEY_APP_LOCK = booleanPreferencesKey("app_lock_enabled")
        private val KEY_WORKER_MODE = booleanPreferencesKey("worker_mode_enabled")
        private val KEY_SORT_COACH_SEEN = booleanPreferencesKey("sort_coach_seen")
    }
}
