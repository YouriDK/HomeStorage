package com.boxpix.app.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigCryptoTest {

    private val backup = ConfigBackup(
        appToken = "secret-token",
        apiDomain = "abcd.fbxos.fr",
        httpsPort = 3615,
        apiBaseUrl = "/api/",
        apiVersion = "16.0",
        boxName = "Freebox Pop",
    )

    @Test
    fun `roundtrip with the right passphrase`() {
        val bytes = ConfigCrypto.encrypt(backup, "correct horse".toCharArray())
        assertEquals(backup, ConfigCrypto.decrypt(bytes, "correct horse".toCharArray()))
    }

    @Test
    fun `wrong passphrase yields null, not garbage`() {
        val bytes = ConfigCrypto.encrypt(backup, "correct horse".toCharArray())
        assertNull(ConfigCrypto.decrypt(bytes, "battery staple".toCharArray()))
    }

    @Test
    fun `corrupted file yields null`() {
        val bytes = ConfigCrypto.encrypt(backup, "p".toCharArray())
        bytes[bytes.size - 3] = (bytes[bytes.size - 3] + 1).toByte()
        assertNull(ConfigCrypto.decrypt(bytes, "p".toCharArray()))
    }
}
