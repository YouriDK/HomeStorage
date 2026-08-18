package com.boxpix.app.data.freebox.api

import org.junit.Assert.assertEquals
import org.junit.Test

class FreeboxCryptoTest {

    // RFC 2202 HMAC-SHA1 test vectors.
    @Test
    fun `rfc2202 case 2`() {
        assertEquals(
            "effcdf6ae5eb2fa2d27416d5f184df9c259a7c79",
            FreeboxCrypto.sessionPassword("Jefe", "what do ya want for nothing?"),
        )
    }

    @Test
    fun `rfc2202 case 1`() {
        val key = "\u000B".repeat(20)
        assertEquals(
            "b617318655057264e28bc0b6fb378c8ef146be00",
            FreeboxCrypto.sessionPassword(key, "Hi There"),
        )
    }

    @Test
    fun `output is lowercase hex of fixed length`() {
        val password = FreeboxCrypto.sessionPassword("app-token", "challenge-string")
        assertEquals(40, password.length)
        assertEquals(password, password.lowercase())
    }
}
