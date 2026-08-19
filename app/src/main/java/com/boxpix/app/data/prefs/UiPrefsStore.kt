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
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

private val Context.uiPrefsDataStore by preferencesDataStore(name = "ui_prefs")

private val prefsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

enum class SortOrder { NAME, DATE, SIZE }

@kotlinx.serialization.Serializable
data class PinnedDestination(val pathB64: String, val name: String)

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

    /** Sort mode: pinned destination folders, ordered, per provider. */
    fun pinnedDestinations(providerId: String): Flow<List<PinnedDestination>> =
        context.uiPrefsDataStore.data.map { prefs ->
            prefs[stringPreferencesKey("sort_dests_$providerId")]
                ?.let { runCatching { prefsJson.decodeFromString<List<PinnedDestination>>(it) }.getOrNull() }
                .orEmpty()
        }.distinctUntilChanged()

    suspend fun setPinnedDestinations(providerId: String, destinations: List<PinnedDestination>) {
        context.uiPrefsDataStore.edit {
            it[stringPreferencesKey("sort_dests_$providerId")] = prefsJson.encodeToString(destinations)
        }
    }

    /** Sort mode: the editable quick-tag names, ordered, per provider. */
    fun quickTags(providerId: String): Flow<List<String>> =
        context.uiPrefsDataStore.data.map { prefs ->
            prefs[stringPreferencesKey("quick_tags_$providerId")]
                ?.let { runCatching { prefsJson.decodeFromString<List<String>>(it) }.getOrNull() }
                .orEmpty()
        }.distinctUntilChanged()

    suspend fun setQuickTags(providerId: String, tags: List<String>) {
        context.uiPrefsDataStore.edit {
            it[stringPreferencesKey("quick_tags_$providerId")] = prefsJson.encodeToString(tags)
        }
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

        private val KEY_GRID_COLUMNS = intPreferencesKey("grid_columns")
        private val KEY_USE_FAKE = booleanPreferencesKey("use_fake_provider")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
    }
}
