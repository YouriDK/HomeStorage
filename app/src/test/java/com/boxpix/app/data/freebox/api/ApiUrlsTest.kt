package com.boxpix.app.data.freebox.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiUrlsTest {

    private val discovery = ApiVersionDto(
        apiVersion = "12.0",
        apiBaseUrl = "/api/",
        apiDomain = "abcd1234.fbxos.fr",
        httpsAvailable = true,
        httpsPort = 3615,
    )

    @Test
    fun `major version drops the minor part`() {
        assertEquals(12, ApiUrls.majorVersion("12.0"))
        assertEquals(4, ApiUrls.majorVersion("4.1"))
    }

    @Test
    fun `lan base over https`() {
        assertEquals(
            "https://mafreebox.freebox.fr/api/v12",
            ApiUrls.lanBase(discovery, https = true),
        )
    }

    @Test
    fun `lan base over http`() {
        assertEquals(
            "http://mafreebox.freebox.fr/api/v12",
            ApiUrls.lanBase(discovery, https = false),
        )
    }

    @Test
    fun `remote base uses api_domain and https_port`() {
        assertEquals(
            "https://abcd1234.fbxos.fr:3615/api/v12",
            ApiUrls.remoteBase(discovery),
        )
    }

    @Test
    fun `base is robust to api_base_url slash variants`() {
        assertEquals(
            "http://mafreebox.freebox.fr/api/v8",
            ApiUrls.apiBase("http", "mafreebox.freebox.fr", null, "/api/", "8.0"),
        )
        assertEquals(
            "http://mafreebox.freebox.fr/api/v8",
            ApiUrls.apiBase("http", "mafreebox.freebox.fr", null, "api", "8.0"),
        )
    }

    @Test
    fun `remote base is null without remote access`() {
        assertNull(ApiUrls.remoteBase(discovery.copy(apiDomain = null)))
        assertNull(ApiUrls.remoteBase(discovery.copy(httpsPort = null)))
        assertNull(ApiUrls.remoteBase(discovery.copy(httpsAvailable = false)))
    }
}
