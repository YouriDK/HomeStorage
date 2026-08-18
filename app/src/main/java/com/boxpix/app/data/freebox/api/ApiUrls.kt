package com.boxpix.app.data.freebox.api

object ApiUrls {

    const val LAN_HOST = "mafreebox.freebox.fr"

    /** "12.0" → 12. The URL only carries the major version. */
    fun majorVersion(apiVersion: String): Int =
        apiVersion.substringBefore('.').toInt()

    /**
     * Base URL of all API calls, without trailing slash, e.g.
     * "https://mafreebox.freebox.fr/api/v12" or "https://abcd.fbxos.fr:3615/api/v12".
     */
    fun apiBase(scheme: String, host: String, port: Int?, apiBaseUrl: String, apiVersion: String): String {
        val portPart = if (port != null) ":$port" else ""
        val basePath = apiBaseUrl.trim('/')
        return "$scheme://$host$portPart/$basePath/v${majorVersion(apiVersion)}"
    }

    fun lanBase(discovery: ApiVersionDto, https: Boolean): String =
        apiBase(
            scheme = if (https) "https" else "http",
            host = LAN_HOST,
            port = null,
            apiBaseUrl = discovery.apiBaseUrl,
            apiVersion = discovery.apiVersion,
        )

    /** Null when the box has no remote access configured. */
    fun remoteBase(discovery: ApiVersionDto): String? {
        val domain = discovery.apiDomain ?: return null
        val port = discovery.httpsPort ?: return null
        if (!discovery.httpsAvailable) return null
        return apiBase("https", domain, port, discovery.apiBaseUrl, discovery.apiVersion)
    }
}
