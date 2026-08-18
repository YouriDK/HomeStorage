package com.boxpix.app.data.freebox.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every FreeboxOS endpoint answers with this envelope. Errors can arrive either as
 * HTTP 200 + success=false or as an HTTP error status with the same JSON body, so the
 * body is always parsed regardless of status code.
 */
@Serializable
data class FreeboxResponse<T>(
    val success: Boolean,
    val result: T? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val msg: String? = null,
)

/** GET http://mafreebox.freebox.fr/api_version — unauthenticated discovery. */
@Serializable
data class ApiVersionDto(
    @SerialName("api_version") val apiVersion: String,
    @SerialName("api_base_url") val apiBaseUrl: String,
    @SerialName("api_domain") val apiDomain: String? = null,
    @SerialName("https_available") val httpsAvailable: Boolean = false,
    @SerialName("https_port") val httpsPort: Int? = null,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("device_type") val deviceType: String? = null,
    val uid: String? = null,
)

@Serializable
data class AuthorizeRequestDto(
    @SerialName("app_id") val appId: String,
    @SerialName("app_name") val appName: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class AuthorizeResultDto(
    @SerialName("app_token") val appToken: String,
    @SerialName("track_id") val trackId: Int,
)

@Serializable
data class TrackAuthorizationResultDto(
    val status: String,
    val challenge: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_GRANTED = "granted"
        const val STATUS_DENIED = "denied"
        const val STATUS_TIMEOUT = "timeout"
        const val STATUS_UNKNOWN = "unknown"
    }
}

@Serializable
data class LoginResultDto(
    @SerialName("logged_in") val loggedIn: Boolean = false,
    val challenge: String? = null,
)

@Serializable
data class SessionRequestDto(
    @SerialName("app_id") val appId: String,
    val password: String,
)

@Serializable
data class SessionResultDto(
    @SerialName("session_token") val sessionToken: String,
    val challenge: String? = null,
    val permissions: Map<String, Boolean> = emptyMap(),
)

/** Entry of a fs/ls listing. `path` and `target` are base64-encoded by the API. */
@Serializable
data class FileInfoDto(
    val path: String,
    val name: String,
    val type: String,
    val size: Long = 0,
    val modification: Long = 0,
    val mimetype: String? = null,
    val hidden: Boolean = false,
    val link: Boolean = false,
    val index: Int? = null,
    val target: String? = null,
    val foldercount: Int? = null,
    val filecount: Int? = null,
) {
    val isDirectory: Boolean get() = type == "dir"
}
