package com.boxpix.app.data.net

import android.util.Log
import com.boxpix.app.BuildConfig
import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.core.ok
import com.boxpix.app.data.freebox.api.ApiUrls
import com.boxpix.app.data.freebox.api.FreeboxApiClient
import com.boxpix.app.data.prefs.SettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionMode { LAN, REMOTE }

/**
 * Picks the base URL for API calls: LAN first (quick discovery probe, which also
 * refreshes the stored endpoint metadata), remote *.fbxos.fr as fallback.
 *
 * On LAN the API is reached over plain HTTP at mafreebox.freebox.fr — explicitly
 * supported by the API and confined to that single host by the network security
 * config. Deliberately no HTTPS on LAN: the box's certificate chain (Freebox CA)
 * is only guaranteed for the *.fbxos.fr remote domain, and a TLS variable on LAN
 * is exactly the kind of silent failure the M1 gate cannot afford. Remote access
 * is HTTPS-only, validated against the embedded Freebox Root CAs.
 */
@Singleton
class EndpointResolver @Inject constructor(
    private val api: FreeboxApiClient,
    private val settings: SettingsStore,
) {

    data class Endpoint(val base: String, val mode: ConnectionMode)

    @Volatile
    var current: Endpoint? = null
        private set

    private val mutex = Mutex()

    suspend fun resolve(forceProbe: Boolean = false): FbxResult<Endpoint> = mutex.withLock {
        if (!forceProbe) current?.let { return it.ok() }

        val snapshot = settings.current()
        val lanHost = snapshot.manualHost ?: ApiUrls.LAN_HOST

        when (val discovery = api.apiVersion(lanHost)) {
            is FbxResult.Ok -> {
                settings.saveDiscovery(discovery.value)
                val dto = discovery.value
                val base = ApiUrls.apiBase("http", lanHost, null, dto.apiBaseUrl, dto.apiVersion)
                log("LAN endpoint: $base (api_version=${dto.apiVersion}, https_available=${dto.httpsAvailable})")
                Endpoint(base, ConnectionMode.LAN).also { current = it }.ok()
            }
            is FbxResult.Err -> {
                val remoteBase = snapshot.remoteBase()
                if (remoteBase != null) {
                    log("LAN discovery failed (${discovery.error}), falling back to remote: $remoteBase")
                    Endpoint(remoteBase, ConnectionMode.REMOTE).also { current = it }.ok()
                } else {
                    log("LAN discovery failed (${discovery.error}) and no remote config stored")
                    FbxResult.Err(FreeboxError.BoxNotFound)
                }
            }
        }
    }

    fun invalidate() {
        current = null
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "BoxpixNet"
    }
}
