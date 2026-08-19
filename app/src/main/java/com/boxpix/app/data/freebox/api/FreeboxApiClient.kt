package com.boxpix.app.data.freebox.api

import android.util.Log
import com.boxpix.app.BuildConfig
import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin typed wrapper over the FreeboxOS HTTP API. Stateless: callers provide the
 * resolved base URL (e.g. "http://mafreebox.freebox.fr/api/v12") and, where needed,
 * the session token. Session lifecycle lives in FreeboxSessionManager.
 *
 * In debug builds every failure is logged with the full URL and the exact cause
 * (tag BoxpixHttp) so a field failure is diagnosable from logcat alone. Tokens
 * and response bodies are never logged.
 */
@Singleton
class FreeboxApiClient @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) {

    /** Unauthenticated discovery; also serves as the LAN reachability probe. */
    suspend fun apiVersion(
        host: String,
        https: Boolean = false,
        timeoutMillis: Long = DISCOVERY_TIMEOUT_MS,
    ): FbxResult<ApiVersionDto> {
        val scheme = if (https) "https" else "http"
        val url = "$scheme://$host/api_version"
        val response = try {
            http.get(url) {
                timeout {
                    requestTimeoutMillis = timeoutMillis
                    connectTimeoutMillis = timeoutMillis
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logTransportFailure(url, e)
            return FbxResult.Err(FreeboxError.Network(e))
        }
        if (!response.status.isSuccess()) {
            logHttpFailure(url, response.status.value, null)
            return FbxResult.Err(FreeboxError.Http(response.status.value))
        }
        return try {
            FbxResult.Ok(json.decodeFromString<ApiVersionDto>(response.bodyAsText()))
        } catch (e: Exception) {
            logTransportFailure(url, e)
            FbxResult.Err(FreeboxError.Network(e))
        }
    }

    suspend fun authorize(base: String, request: AuthorizeRequestDto): FbxResult<AuthorizeResultDto> =
        envelope("$base/login/authorize/") { url ->
            http.post(url) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(AuthorizeRequestDto.serializer(), request))
            }
        }

    suspend fun trackAuthorization(base: String, trackId: Int): FbxResult<TrackAuthorizationResultDto> =
        envelope("$base/login/authorize/$trackId") { url -> http.get(url) }

    suspend fun loginChallenge(base: String): FbxResult<LoginResultDto> =
        envelope("$base/login/") { url -> http.get(url) }

    suspend fun openSession(base: String, appId: String, password: String): FbxResult<SessionResultDto> =
        envelope("$base/login/session/") { url ->
            http.post(url) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(SessionRequestDto.serializer(), SessionRequestDto(appId, password)))
            }
        }

    suspend fun ls(
        base: String,
        sessionToken: String,
        pathB64: String,
        onlyFolder: Boolean = false,
        removeHidden: Boolean = false,
        countSubFolder: Boolean = false,
    ): FbxResult<List<FileInfoDto>> {
        val url = "$base/fs/ls/$pathB64"
        val outcome = envelopeNullable<JsonElement>(url) {
            http.get(it) {
                header(X_FBX_APP_AUTH, sessionToken)
                if (onlyFolder) parameter("onlyFolder", 1)
                if (removeHidden) parameter("removeHidden", 1)
                if (countSubFolder) parameter("countSubFolder", 1)
            }
        }
        return when (outcome) {
            is FbxResult.Ok -> try {
                FbxResult.Ok(parseFileList(outcome.value))
            } catch (e: Exception) {
                logTransportFailure(url, e)
                FbxResult.Err(FreeboxError.Network(e))
            }
            is FbxResult.Err -> outcome
        }
    }

    /**
     * fs/ls result shape depends on the firmware generation: the documented v4-era
     * form is a bare array of entries, but API v16 (Freebox Pop, observed at the
     * M1 gate) wraps it as {"entries": [...]}. Accept both.
     */
    private fun parseFileList(result: JsonElement?): List<FileInfoDto> = when (result) {
        null, JsonNull -> emptyList()
        is JsonArray -> json.decodeFromJsonElement(fileListSerializer, result)
        is JsonObject -> result["entries"]
            ?.takeIf { it !is JsonNull }
            ?.let { json.decodeFromJsonElement(fileListSerializer, it) }
            ?: emptyList()
        else -> throw SerializationException("Unexpected fs/ls result shape: $result")
    }

    private val fileListSerializer = ListSerializer(FileInfoDto.serializer())

    /**
     * Creates a folder. The documented result is the new encoded path (a bare
     * string), but per the v16 lesson nothing is assumed: callers build their own
     * entry from parent + name, so only the envelope's success matters here.
     */
    suspend fun mkdir(base: String, sessionToken: String, parentB64: String, name: String): FbxResult<Unit> =
        envelopeNullable<JsonElement>("$base/fs/mkdir/") { url ->
            http.post(url) {
                header(X_FBX_APP_AUTH, sessionToken)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(MkdirRequestDto.serializer(), MkdirRequestDto(parentB64, name)))
            }
        }.map { }

    /** Renames in place; `dst` is the new name, not a path. */
    suspend fun rename(base: String, sessionToken: String, pathB64: String, newName: String): FbxResult<Unit> =
        envelopeNullable<JsonElement>("$base/fs/rename/") { url ->
            http.post(url) {
                header(X_FBX_APP_AUTH, sessionToken)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RenameRequestDto.serializer(), RenameRequestDto(pathB64, newName)))
            }
        }.map { }

    /**
     * Starts an async move; returns the task to poll via [fsTask]. The box has no
     * "fail on conflict" mode, so callers must detect conflicts beforehand; "skip"
     * guarantees a race can never overwrite existing data.
     */
    suspend fun mv(
        base: String,
        sessionToken: String,
        filesB64: List<String>,
        destB64: String,
        mode: String = "skip",
    ): FbxResult<FsTaskDto> =
        envelope("$base/fs/mv/") { url ->
            http.post(url) {
                header(X_FBX_APP_AUTH, sessionToken)
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        FileOperationRequestDto.serializer(),
                        FileOperationRequestDto(files = filesB64, dst = destB64, mode = mode),
                    ),
                )
            }
        }

    /** Starts an async permanent deletion; returns the task to poll via [fsTask]. */
    suspend fun rm(base: String, sessionToken: String, filesB64: List<String>): FbxResult<FsTaskDto> =
        envelope("$base/fs/rm/") { url ->
            http.post(url) {
                header(X_FBX_APP_AUTH, sessionToken)
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        FileOperationRequestDto.serializer(),
                        FileOperationRequestDto(files = filesB64),
                    ),
                )
            }
        }

    suspend fun fsTask(base: String, sessionToken: String, taskId: Int): FbxResult<FsTaskDto> =
        envelope("$base/fs/tasks/$taskId") { url ->
            http.get(url) { header(X_FBX_APP_AUTH, sessionToken) }
        }

    /**
     * Uploads [bytes] as [filename] into [dirB64] over the WebSocket upload API
     * (the HTTP upload is deprecated since v4): upload_start JSON → binary
     * frames (each acked) → upload_finalize. force=overwrite so regenerating a
     * sidecar replaces the stale one.
     */
    suspend fun upload(
        base: String,
        sessionToken: String,
        dirB64: String,
        filename: String,
        bytes: ByteArray,
    ): FbxResult<Unit> {
        val wsUrl = base.replaceFirst("http", "ws") + "/ws/upload"
        logWs("upload start: dir=${runCatching { PathCodec.decode(dirB64) }.getOrDefault("?")} file=$filename size=${bytes.size}")
        return try {
            var outcome: FbxResult<Unit> = FbxResult.Err(FreeboxError.Api("upload_incomplete"))
            http.webSocket(urlString = wsUrl, request = { header(X_FBX_APP_AUTH, sessionToken) }) {
                val start = buildJsonObject {
                    put("action", "upload_start")
                    put("request_id", 1)
                    put("size", bytes.size)
                    put("dirname", dirB64)
                    put("filename", filename)
                    put("force", "overwrite")
                }
                send(Frame.Text(start.toString()))
                awaitUploadAck("upload_start")?.let {
                    outcome = FbxResult.Err(it)
                    return@webSocket
                }

                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(offset + UPLOAD_CHUNK_BYTES, bytes.size)
                    send(Frame.Binary(true, bytes.copyOfRange(offset, end)))
                    offset = end
                    awaitUploadAck("upload_data")?.let {
                        outcome = FbxResult.Err(it)
                        return@webSocket
                    }
                }

                send(Frame.Text("""{"action":"upload_finalize","request_id":1}"""))
                awaitUploadAck("upload_finalize")?.let {
                    outcome = FbxResult.Err(it)
                    return@webSocket
                }
                outcome = FbxResult.Ok(Unit)
            }
            logWs("upload outcome for $filename: $outcome")
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logTransportFailure(wsUrl, e)
            FbxResult.Err(FreeboxError.Network(e))
        }
    }

    /** Returns null when the matching ack reports success, the error otherwise. */
    private suspend fun DefaultClientWebSocketSession.awaitUploadAck(action: String): FreeboxError? {
        while (true) {
            val frame = incoming.receive()
            if (frame !is Frame.Text) continue
            val raw = frame.readText()
            logWs("ack (awaiting $action): $raw")
            val ack = try {
                json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                return FreeboxError.Network(e)
            }
            val ackAction = ack["action"]?.jsonPrimitive?.contentOrNull
            if (ackAction != null && ackAction != action) continue
            val success = ack["success"]?.jsonPrimitive?.booleanOrNull ?: true
            return if (success) {
                null
            } else {
                FreeboxError.Api(ack["error_code"]?.jsonPrimitive?.contentOrNull ?: "upload_failed")
            }
        }
    }

    /** Raw file download via /dl/; the box honours Range requests on this endpoint. */
    suspend fun download(
        base: String,
        sessionToken: String,
        pathB64: String,
        range: LongRange? = null,
    ): FbxResult<ByteArray> {
        val url = "$base/dl/$pathB64"
        val response = try {
            http.get(url) {
                header(X_FBX_APP_AUTH, sessionToken)
                if (range != null) header(HttpHeaders.Range, "bytes=${range.first}-${range.last}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logTransportFailure(url, e)
            return FbxResult.Err(FreeboxError.Network(e))
        }
        if (!response.status.isSuccess()) {
            logHttpFailure(url, response.status.value, null)
            return FbxResult.Err(FreeboxError.Http(response.status.value))
        }
        return try {
            FbxResult.Ok(response.body())
        } catch (e: Exception) {
            logTransportFailure(url, e)
            FbxResult.Err(FreeboxError.Network(e))
        }
    }

    /** Executes a request and decodes the standard {success, result, error_code, msg} envelope. */
    private suspend inline fun <reified T> envelope(
        url: String,
        crossinline request: suspend (String) -> HttpResponse,
    ): FbxResult<T> = envelopeNullable<T>(url, request).let { outcome ->
        when (outcome) {
            is FbxResult.Ok -> outcome.value
                ?.let { FbxResult.Ok(it) }
                ?: FbxResult.Err(FreeboxError.Api("missing_result"))
            is FbxResult.Err -> outcome
        }
    }

    /** Like [envelope] but tolerates success answers without a result (e.g. empty listings). */
    private suspend inline fun <reified T> envelopeNullable(
        url: String,
        crossinline request: suspend (String) -> HttpResponse,
    ): FbxResult<T?> {
        val response = try {
            request(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logTransportFailure(url, e)
            return FbxResult.Err(FreeboxError.Network(e))
        }
        val body = try {
            response.bodyAsText()
        } catch (e: Exception) {
            logTransportFailure(url, e)
            return FbxResult.Err(FreeboxError.Network(e))
        }
        val parsed = try {
            json.decodeFromString<FreeboxResponse<T>>(body)
        } catch (e: Exception) {
            // Not a Freebox envelope: report the transport-level failure.
            return if (!response.status.isSuccess()) {
                logHttpFailure(url, response.status.value, null)
                FbxResult.Err(FreeboxError.Http(response.status.value))
            } else {
                logTransportFailure(url, e)
                FbxResult.Err(FreeboxError.Network(e))
            }
        }
        return if (parsed.success) {
            FbxResult.Ok(parsed.result)
        } else {
            logHttpFailure(url, response.status.value, parsed.errorCode)
            FbxResult.Err(FreeboxError.Api(parsed.errorCode ?: "unknown_error", parsed.msg))
        }
    }

    private fun logWs(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, "WS $message")
    }

    private fun logTransportFailure(url: String, cause: Exception) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "FAIL $url -> ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    private fun logHttpFailure(url: String, status: Int, errorCode: String?) {
        if (!BuildConfig.DEBUG) return
        val message = "FAIL $url -> HTTP $status${errorCode?.let { " error_code=$it" }.orEmpty()}"
        if (errorCode == "destination_conflict") {
            // Usually benign: mkdir -p chains and conflict probes expect these.
            Log.i(TAG, message)
        } else {
            Log.e(TAG, message)
        }
    }

    companion object {
        const val X_FBX_APP_AUTH = "X-Fbx-App-Auth"
        const val DISCOVERY_TIMEOUT_MS = 2_500L
        private const val UPLOAD_CHUNK_BYTES = 512 * 1024
        private const val TAG = "BoxpixHttp"
    }
}
