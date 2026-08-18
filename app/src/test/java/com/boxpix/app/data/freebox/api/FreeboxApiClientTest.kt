package com.boxpix.app.data.freebox.api

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.core.isAuthError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class FreeboxApiClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): FreeboxApiClient {
        val http = HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(HttpTimeout)
        }
        return FreeboxApiClient(http, json)
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun `apiVersion parses the bare discovery payload`() = runTest {
        val api = client {
            respondJson(
                """
                {"uid":"abc","device_name":"Freebox Server","api_version":"12.0",
                 "api_base_url":"/api/","device_type":"FreeboxPop","api_domain":"xyz.fbxos.fr",
                 "https_available":true,"https_port":3615,"box_model":"fbxgw7-r1"}
                """.trimIndent(),
            )
        }
        val result = api.apiVersion("mafreebox.freebox.fr")
        val dto = (result as FbxResult.Ok).value
        assertEquals("12.0", dto.apiVersion)
        assertEquals("xyz.fbxos.fr", dto.apiDomain)
        assertEquals(3615, dto.httpsPort)
    }

    @Test
    fun `ls parses entries and ignores unknown fields`() = runTest {
        val api = client { request ->
            assertEquals("session-token", request.headers[FreeboxApiClient.X_FBX_APP_AUTH])
            respondJson(
                """
                {"success":true,"result":[
                  {"path":"RnJlZWJveC9WTXM=","name":"VMs","type":"dir","size":0,
                   "modification":1720000000,"index":0,"link":false,"hidden":false,
                   "mimetype":"application/x-empty","some_new_field":42},
                  {"path":"cGhvdG8uanBn","name":"photo.jpg","type":"file","size":123456,
                   "modification":1720000001,"mimetype":"image/jpeg"}
                ]}
                """.trimIndent(),
            )
        }
        val result = api.ls("https://mafreebox.freebox.fr/api/v12", "session-token", PathCodec.ROOT)
        val entries = (result as FbxResult.Ok).value
        assertEquals(2, entries.size)
        assertTrue(entries[0].isDirectory)
        assertEquals("photo.jpg", entries[1].name)
        assertEquals(123456L, entries[1].size)
    }

    @Test
    fun `ls tolerates a success answer without result`() = runTest {
        val api = client { respondJson("""{"success":true}""") }
        val result = api.ls("base", "token", PathCodec.ROOT)
        assertEquals(emptyList<FileInfoDto>(), (result as FbxResult.Ok).value)
    }

    // API v16 (Freebox Pop, observed at the M1 gate) wraps the listing in an object.
    @Test
    fun `ls parses the v16 entries-wrapped shape`() = runTest {
        val api = client {
            respondJson(
                """
                {"success":true,"result":{"entries":[
                  {"path":"RGlzcXVlIDE=","name":"Disque 1","type":"dir","index":0,
                   "link":false,"modification":1720000000,"hidden":false},
                  {"path":"RnJlZWJveA==","name":"Freebox","type":"dir","index":1}
                ],"path":"Lw=="}}
                """.trimIndent(),
            )
        }
        val entries = (api.ls("base", "token", PathCodec.ROOT) as FbxResult.Ok).value
        assertEquals(listOf("Disque 1", "Freebox"), entries.map { it.name })
        assertTrue(entries.all { it.isDirectory })
    }

    @Test
    fun `ls treats a v16 result without entries as empty`() = runTest {
        val api = client { respondJson("""{"success":true,"result":{"path":"Lw=="}}""") }
        val result = api.ls("base", "token", PathCodec.ROOT)
        assertEquals(emptyList<FileInfoDto>(), (result as FbxResult.Ok).value)
    }

    @Test
    fun `a 403 envelope surfaces the api error code`() = runTest {
        val api = client {
            respondJson(
                """{"success":false,"error_code":"invalid_token","msg":"Invalid session token"}""",
                HttpStatusCode.Forbidden,
            )
        }
        val result = api.ls("base", "stale-token", PathCodec.ROOT)
        val error = (result as FbxResult.Err).error
        assertEquals("invalid_token", (error as FreeboxError.Api).code)
        assertTrue(error.isAuthError())
    }

    @Test
    fun `a non-envelope http error maps to Http`() = runTest {
        val api = client { respond("Bad Gateway", HttpStatusCode.BadGateway) }
        val result = api.ls("base", "token", PathCodec.ROOT)
        assertEquals(FreeboxError.Http(502), (result as FbxResult.Err).error)
    }

    @Test
    fun `authorize posts the app identity`() = runTest {
        val api = client { request ->
            val body = String(request.body.toByteArray())
            assertTrue(body.contains("\"app_id\":\"com.boxpix.app\""))
            assertTrue(body.contains("\"device_name\":\"Pixel\""))
            respondJson("""{"success":true,"result":{"app_token":"tok","track_id":7}}""")
        }
        val request = AuthorizeRequestDto("com.boxpix.app", "Boxpix", "0.1.0", "Pixel")
        val result = api.authorize("base", request)
        val granted = (result as FbxResult.Ok).value
        assertEquals("tok", granted.appToken)
        assertEquals(7, granted.trackId)
    }

    @Test
    fun `openSession parses token and permissions`() = runTest {
        val api = client {
            respondJson(
                """
                {"success":true,"result":{"session_token":"st","challenge":"next",
                 "permissions":{"explorer":true,"settings":false}}}
                """.trimIndent(),
            )
        }
        val result = api.openSession("base", "com.boxpix.app", "hex-password")
        val session = (result as FbxResult.Ok).value
        assertEquals("st", session.sessionToken)
        assertEquals(mapOf("explorer" to true, "settings" to false), session.permissions)
    }

    @Test
    fun `trackAuthorization exposes the raw status`() = runTest {
        val api = client {
            respondJson("""{"success":true,"result":{"status":"granted","challenge":"ch"}}""")
        }
        val result = api.trackAuthorization("base", 7)
        assertEquals(
            TrackAuthorizationResultDto.STATUS_GRANTED,
            (result as FbxResult.Ok).value.status,
        )
    }

    /**
     * M1 gate regression guard: every post-pairing call must be composed from the
     * SAME base (api_base_url + major version from /api_version), exactly as the
     * documentation specifies — e.g. http://mafreebox.freebox.fr/api/v8/login/session/.
     */
    @Test
    fun `all calls compose their url from the same base`() = runTest {
        val base = "http://mafreebox.freebox.fr/api/v8"
        val seen = mutableListOf<String>()
        val api = client { request ->
            seen += request.url.toString()
            respondJson(
                when {
                    request.url.encodedPath.contains("/fs/ls/") -> """{"success":true,"result":[]}"""
                    request.url.encodedPath.endsWith("/login/authorize/") ->
                        """{"success":true,"result":{"app_token":"t","track_id":1}}"""
                    request.url.encodedPath.contains("/login/authorize/") ->
                        """{"success":true,"result":{"status":"granted"}}"""
                    request.url.encodedPath.endsWith("/login/session/") ->
                        """{"success":true,"result":{"session_token":"s"}}"""
                    else -> """{"success":true,"result":{"logged_in":false,"challenge":"c"}}"""
                },
            )
        }

        api.authorize(base, AuthorizeRequestDto("id", "name", "1.0", "device"))
        api.trackAuthorization(base, 42)
        api.loginChallenge(base)
        api.openSession(base, "id", "password")
        api.ls(base, "token", "Lw==", onlyFolder = true)

        assertEquals(
            listOf(
                "$base/login/authorize/",
                "$base/login/authorize/42",
                "$base/login/",
                "$base/login/session/",
                "$base/fs/ls/Lw==?onlyFolder=1",
            ),
            seen,
        )
    }

    @Test
    fun `download sends a range header when asked`() = runTest {
        val api = client { request ->
            assertEquals("bytes=0-65535", request.headers[HttpHeaders.Range])
            respond(ByteArray(16), HttpStatusCode.PartialContent)
        }
        val result = api.download("base", "token", "cGhvdG8uanBn", 0L..65535L)
        assertEquals(16, (result as FbxResult.Ok).value.size)
    }
}
