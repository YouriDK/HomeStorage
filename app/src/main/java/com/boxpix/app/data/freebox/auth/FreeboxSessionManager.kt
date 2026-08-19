package com.boxpix.app.data.freebox.auth

import android.util.Log
import com.boxpix.app.BuildConfig
import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.core.isAuthError
import com.boxpix.app.data.freebox.api.FreeboxApiClient
import com.boxpix.app.data.freebox.api.FreeboxCrypto
import com.boxpix.app.data.net.EndpointResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the session_token lifecycle: opens a session on demand (challenge →
 * HMAC-SHA1 → session) and transparently re-authenticates once when a call
 * comes back 403 auth_required/invalid_token.
 */
@Singleton
class FreeboxSessionManager @Inject constructor(
    private val api: FreeboxApiClient,
    private val tokenStore: AppTokenStore,
    private val resolver: EndpointResolver,
) {

    @Volatile
    private var sessionToken: String? = null

    @Volatile
    var permissions: Map<String, Boolean> = emptyMap()
        private set

    private val loginMutex = Mutex()

    /** Runs an authenticated call, retrying exactly once after a fresh login on auth errors. */
    suspend fun <T> withSession(block: suspend (base: String, sessionToken: String) -> FbxResult<T>): FbxResult<T> {
        val endpoint = when (val resolved = resolver.resolve()) {
            is FbxResult.Ok -> resolved.value
            is FbxResult.Err -> return resolved
        }
        val token = sessionToken ?: when (val login = login(endpoint.base)) {
            is FbxResult.Ok -> login.value
            is FbxResult.Err -> return login
        }
        val first = block(endpoint.base, token)
        val error = (first as? FbxResult.Err)?.error ?: return first
        if (!error.isAuthError()) return first

        return when (val login = login(endpoint.base)) {
            is FbxResult.Ok -> block(endpoint.base, login.value)
            is FbxResult.Err -> login
        }
    }

    fun dropSession() {
        sessionToken = null
        permissions = emptyMap()
    }

    /**
     * Base URL + session token for direct streaming (ExoPlayer hits /dl/ itself
     * with the X-Fbx-App-Auth header). Logs in first when no session is open.
     */
    suspend fun streamingAccess(): FbxResult<Pair<String, String>> {
        val endpoint = when (val resolved = resolver.resolve()) {
            is FbxResult.Ok -> resolved.value
            is FbxResult.Err -> return resolved
        }
        val token = sessionToken ?: when (val login = login(endpoint.base)) {
            is FbxResult.Ok -> login.value
            is FbxResult.Err -> return login
        }
        return FbxResult.Ok(endpoint.base to token)
    }

    private suspend fun login(base: String): FbxResult<String> = loginMutex.withLock {
        val appToken = tokenStore.appToken
        if (appToken == null) {
            log("login: no app_token stored — not paired")
            return FbxResult.Err(FreeboxError.NotPaired)
        }

        log("login: requesting challenge on $base")
        val challenge = when (val outcome = api.loginChallenge(base)) {
            is FbxResult.Ok -> outcome.value.challenge
                ?: run {
                    log("login: challenge missing in response")
                    return FbxResult.Err(FreeboxError.Api("missing_challenge"))
                }
            is FbxResult.Err -> {
                log("login: challenge request failed — ${outcome.error}")
                return outcome
            }
        }

        val password = FreeboxCrypto.sessionPassword(appToken, challenge)
        return when (val outcome = api.openSession(base, FreeboxAppIdentity.APP_ID, password)) {
            is FbxResult.Ok -> {
                sessionToken = outcome.value.sessionToken
                permissions = outcome.value.permissions
                log("login: session opened, permissions=${outcome.value.permissions}")
                FbxResult.Ok(outcome.value.sessionToken)
            }
            is FbxResult.Err -> {
                log("login: session open failed — ${outcome.error}")
                outcome
            }
        }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "BoxpixNet"
    }
}
