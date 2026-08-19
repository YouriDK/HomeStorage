package com.boxpix.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.boxpix.app.data.freebox.api.ApiUrls
import com.boxpix.app.data.freebox.api.ApiVersionDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Non-secret connection metadata and the user's disk/root choice. */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Snapshot(
        val apiDomain: String?,
        val httpsPort: Int?,
        val apiBaseUrl: String?,
        val apiVersion: String?,
        val manualHost: String?,
        val diskName: String?,
        val rootPathB64: String?,
        val rootDisplayPath: String?,
        val boxName: String?,
    ) {
        val hasRoot: Boolean get() = rootPathB64 != null

        fun remoteBase(): String? {
            if (apiDomain == null || httpsPort == null || apiBaseUrl == null || apiVersion == null) return null
            return ApiUrls.apiBase("https", apiDomain, httpsPort, apiBaseUrl, apiVersion)
        }
    }

    val snapshots: Flow<Snapshot> = context.settingsDataStore.data.map { prefs ->
        Snapshot(
            apiDomain = prefs[KEY_API_DOMAIN],
            httpsPort = prefs[KEY_HTTPS_PORT],
            apiBaseUrl = prefs[KEY_API_BASE_URL],
            apiVersion = prefs[KEY_API_VERSION],
            manualHost = prefs[KEY_MANUAL_HOST],
            diskName = prefs[KEY_DISK_NAME],
            rootPathB64 = prefs[KEY_ROOT_PATH_B64],
            rootDisplayPath = prefs[KEY_ROOT_DISPLAY],
            boxName = prefs[KEY_BOX_NAME],
        )
    }

    suspend fun current(): Snapshot = snapshots.first()

    suspend fun saveDiscovery(dto: ApiVersionDto) {
        context.settingsDataStore.edit { prefs ->
            dto.apiDomain?.let { prefs[KEY_API_DOMAIN] = it } ?: prefs.remove(KEY_API_DOMAIN)
            dto.httpsPort?.let { prefs[KEY_HTTPS_PORT] = it } ?: prefs.remove(KEY_HTTPS_PORT)
            prefs[KEY_API_BASE_URL] = dto.apiBaseUrl
            prefs[KEY_API_VERSION] = dto.apiVersion
            dto.deviceName?.let { prefs[KEY_BOX_NAME] = it }
        }
    }

    suspend fun saveManualHost(host: String?) {
        context.settingsDataStore.edit { prefs ->
            if (host.isNullOrBlank() || host == ApiUrls.LAN_HOST) prefs.remove(KEY_MANUAL_HOST)
            else prefs[KEY_MANUAL_HOST] = host
        }
    }

    suspend fun saveRoot(diskName: String, rootPathB64: String, rootDisplayPath: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_DISK_NAME] = diskName
            prefs[KEY_ROOT_PATH_B64] = rootPathB64
            prefs[KEY_ROOT_DISPLAY] = rootDisplayPath
        }
    }

    /** Restores an imported config backup (endpoint metadata; token goes to Keystore). */
    suspend fun saveImported(
        apiDomain: String?,
        httpsPort: Int?,
        apiBaseUrl: String?,
        apiVersion: String?,
        manualHost: String?,
        boxName: String?,
    ) {
        context.settingsDataStore.edit { prefs ->
            apiDomain?.let { prefs[KEY_API_DOMAIN] = it }
            httpsPort?.let { prefs[KEY_HTTPS_PORT] = it }
            apiBaseUrl?.let { prefs[KEY_API_BASE_URL] = it }
            apiVersion?.let { prefs[KEY_API_VERSION] = it }
            manualHost?.let { prefs[KEY_MANUAL_HOST] = it }
            boxName?.let { prefs[KEY_BOX_NAME] = it }
        }
    }

    /** Forgets only the disk/root choice — pairing and endpoint config survive. */
    suspend fun clearRoot() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(KEY_DISK_NAME)
            prefs.remove(KEY_ROOT_PATH_B64)
            prefs.remove(KEY_ROOT_DISPLAY)
        }
    }

    suspend fun clearAll() {
        context.settingsDataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_API_DOMAIN = stringPreferencesKey("api_domain")
        val KEY_HTTPS_PORT = intPreferencesKey("https_port")
        val KEY_API_BASE_URL = stringPreferencesKey("api_base_url")
        val KEY_API_VERSION = stringPreferencesKey("api_version")
        val KEY_MANUAL_HOST = stringPreferencesKey("manual_host")
        val KEY_DISK_NAME = stringPreferencesKey("disk_name")
        val KEY_ROOT_PATH_B64 = stringPreferencesKey("root_path_b64")
        val KEY_ROOT_DISPLAY = stringPreferencesKey("root_display")
        val KEY_BOX_NAME = stringPreferencesKey("box_name")
    }
}
