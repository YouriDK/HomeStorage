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

    /** Backup mirror (owner's request): destination root on the second disk. */
    val backupRoot: Flow<Pair<String, String>?> = context.uiPrefsDataStore.data
        .map { prefs ->
            val b64 = prefs[KEY_BACKUP_ROOT_B64]
            val display = prefs[KEY_BACKUP_ROOT_DISPLAY]
            if (b64 != null && display != null) b64 to display else null
        }
        .distinctUntilChanged()

    suspend fun setBackupRoot(pathB64: String?, displayPath: String?) {
        context.uiPrefsDataStore.edit {
            if (pathB64 == null || displayPath == null) {
                it.remove(KEY_BACKUP_ROOT_B64)
                it.remove(KEY_BACKUP_ROOT_DISPLAY)
            } else {
                it[KEY_BACKUP_ROOT_B64] = pathB64
                it[KEY_BACKUP_ROOT_DISPLAY] = displayPath
            }
        }
    }

    /** Backup mirror source: fixed and chosen explicitly — never follows the app root. */
    val backupSource: Flow<Pair<String, String>?> = context.uiPrefsDataStore.data
        .map { prefs ->
            val b64 = prefs[KEY_BACKUP_SOURCE_B64]
            val display = prefs[KEY_BACKUP_SOURCE_DISPLAY]
            if (b64 != null && display != null) b64 to display else null
        }
        .distinctUntilChanged()

    suspend fun setBackupSource(pathB64: String?, displayPath: String?) {
        context.uiPrefsDataStore.edit {
            if (pathB64 == null || displayPath == null) {
                it.remove(KEY_BACKUP_SOURCE_B64)
                it.remove(KEY_BACKUP_SOURCE_DISPLAY)
            } else {
                it[KEY_BACKUP_SOURCE_B64] = pathB64
                it[KEY_BACKUP_SOURCE_DISPLAY] = displayPath
            }
        }
    }

    /** Local hour of day before which the scheduled backup pass waits; -1 = any time. */
    val backupEarliestHour: Flow<Int> = context.uiPrefsDataStore.data
        .map { it[KEY_BACKUP_EARLIEST_HOUR] ?: -1 }
        .distinctUntilChanged()

    suspend fun setBackupEarliestHour(hour: Int) {
        context.uiPrefsDataStore.edit { it[KEY_BACKUP_EARLIEST_HOUR] = hour.coerceIn(-1, 23) }
    }

    /** Per-pass worker switches (owner control), ON by default. */
    fun workerPassEnabled(pass: String): Flow<Boolean> = context.uiPrefsDataStore.data
        .map { it[booleanPreferencesKey("worker_pass_$pass")] ?: true }
        .distinctUntilChanged()

    suspend fun setWorkerPassEnabled(pass: String, enabled: Boolean) {
        context.uiPrefsDataStore.edit { it[booleanPreferencesKey("worker_pass_$pass")] = enabled }
    }

    /** Worker wake-up cadence in minutes (15 by default). */
    val workerCycleMinutes: Flow<Long> = context.uiPrefsDataStore.data
        .map { it[KEY_WORKER_CYCLE_MINUTES] ?: 15L }
        .distinctUntilChanged()

    suspend fun setWorkerCycleMinutes(minutes: Long) {
        context.uiPrefsDataStore.edit { it[KEY_WORKER_CYCLE_MINUTES] = minutes }
    }

    /** Backup cadence in days — 7 by default (owner's weekly wish). */
    val backupIntervalDays: Flow<Long> = context.uiPrefsDataStore.data
        .map { it[KEY_BACKUP_INTERVAL_DAYS] ?: 7L }
        .distinctUntilChanged()

    suspend fun setBackupIntervalDays(days: Long) {
        context.uiPrefsDataStore.edit { it[KEY_BACKUP_INTERVAL_DAYS] = days }
    }

    val lastBackupAtEpochSeconds: Flow<Long?> = context.uiPrefsDataStore.data
        .map { it[KEY_LAST_BACKUP_AT] }
        .distinctUntilChanged()

    suspend fun setLastBackupAt(epochSeconds: Long) {
        context.uiPrefsDataStore.edit { it[KEY_LAST_BACKUP_AT] = epochSeconds }
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
        private val KEY_BACKUP_ROOT_B64 = stringPreferencesKey("backup_root_b64")
        private val KEY_BACKUP_ROOT_DISPLAY = stringPreferencesKey("backup_root_display")
        private val KEY_BACKUP_SOURCE_B64 = stringPreferencesKey("backup_source_b64")
        private val KEY_BACKUP_SOURCE_DISPLAY = stringPreferencesKey("backup_source_display")
        private val KEY_BACKUP_EARLIEST_HOUR = intPreferencesKey("backup_earliest_hour")
        private val KEY_LAST_BACKUP_AT = androidx.datastore.preferences.core.longPreferencesKey("last_backup_at")
        private val KEY_BACKUP_INTERVAL_DAYS = androidx.datastore.preferences.core.longPreferencesKey("backup_interval_days")
        private val KEY_WORKER_CYCLE_MINUTES = androidx.datastore.preferences.core.longPreferencesKey("worker_cycle_minutes")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_ACCENT = stringPreferencesKey("accent_preset")
        private val KEY_APP_LOCK = booleanPreferencesKey("app_lock_enabled")
        private val KEY_WORKER_MODE = booleanPreferencesKey("worker_mode_enabled")
        private val KEY_SORT_COACH_SEEN = booleanPreferencesKey("sort_coach_seen")
    }
}
