package com.boxpix.app.data.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * SPEC §3 export/import config: reinstall without re-pairing. The payload
 * carries the app_token, so it is ALWAYS passphrase-encrypted
 * (PBKDF2-HMAC-SHA256 → AES-256-GCM; file = magic + salt + iv + ciphertext).
 */
@Serializable
data class ConfigBackup(
    val version: Int = 1,
    @SerialName("app_token") val appToken: String,
    @SerialName("manual_host") val manualHost: String? = null,
    @SerialName("api_domain") val apiDomain: String? = null,
    @SerialName("https_port") val httpsPort: Int? = null,
    @SerialName("api_base_url") val apiBaseUrl: String? = null,
    @SerialName("api_version") val apiVersion: String? = null,
    @SerialName("box_name") val boxName: String? = null,
)

object ConfigCrypto {

    private val MAGIC = "BXP1".toByteArray()
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256

    private val json = Json { ignoreUnknownKeys = true }

    fun encrypt(backup: ConfigBackup, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        }
        val plaintext = json.encodeToString(ConfigBackup.serializer(), backup).toByteArray()
        return MAGIC + salt + iv + cipher.doFinal(plaintext)
    }

    /** Null on wrong passphrase or corrupted file. */
    fun decrypt(bytes: ByteArray, passphrase: CharArray): ConfigBackup? = try {
        require(bytes.size > MAGIC.size + SALT_LEN + IV_LEN)
        require(bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
        val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
        val iv = bytes.copyOfRange(MAGIC.size + SALT_LEN, MAGIC.size + SALT_LEN + IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        }
        val plaintext = cipher.doFinal(bytes.copyOfRange(MAGIC.size + SALT_LEN + IV_LEN, bytes.size))
        json.decodeFromString(ConfigBackup.serializer(), String(plaintext))
    } catch (_: Exception) {
        null
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return SecretKeySpec(key.encoded, "AES")
    }
}
