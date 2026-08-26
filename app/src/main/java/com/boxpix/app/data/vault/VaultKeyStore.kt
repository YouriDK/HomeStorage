package com.boxpix.app.data.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in "unlock with biometrics": the raw vault masterkey wrapped by an
 * AndroidKeyStore AES-GCM key that REQUIRES a biometric authentication per use
 * (CryptoObject flow). The wrapped blob alone is useless without the hardware
 * key, and the hardware key is invalidated when biometrics are re-enrolled —
 * in that case the blob is discarded and the passphrase becomes the only path
 * again, which is exactly the fallback the UI offers anyway.
 */
interface VaultKeyStore {
    sealed interface Remembered {
        /** Nothing stored: passphrase only. */
        data object None : Remembered

        /** Stored and usable: authenticate this cipher via BiometricPrompt, then [unwrap]. */
        data class Ready(val cipher: Cipher) : Remembered

        /** Biometrics changed since enrolment; the blob was discarded. */
        data object Invalidated : Remembered
    }

    val isRemembered: Boolean

    /** Cipher to authenticate (BiometricPrompt CryptoObject) before [store]. */
    fun encryptCipher(): Cipher

    fun beginBiometricUnlock(): Remembered

    /** Persists [rawKey] wrapped by the authenticated [cipher]; wipes [rawKey]. */
    fun store(cipher: Cipher, rawKey: ByteArray)

    /** The raw masterkey, or null when the blob is gone or the cipher was refused. */
    fun unwrap(cipher: Cipher): ByteArray?

    fun forget()
}

@Singleton
class AndroidVaultKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : VaultKeyStore {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override val isRemembered: Boolean
        get() = prefs.contains(KEY_BLOB)

    override fun encryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, obtainKey()) }

    override fun beginBiometricUnlock(): VaultKeyStore.Remembered {
        val blob = prefs.getString(KEY_BLOB, null) ?: return VaultKeyStore.Remembered.None
        val iv = prefs.getString(KEY_IV, null) ?: return VaultKeyStore.Remembered.None
        return try {
            val key = existingKey() ?: return VaultKeyStore.Remembered.None.also { forget() }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv.decoded()))
            }
            VaultKeyStore.Remembered.Ready(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            forget()
            VaultKeyStore.Remembered.Invalidated
        } catch (_: Exception) {
            forget()
            VaultKeyStore.Remembered.None
        }
    }

    override fun store(cipher: Cipher, rawKey: ByteArray) {
        try {
            val wrapped = cipher.doFinal(rawKey)
            prefs.edit()
                .putString(KEY_BLOB, wrapped.encoded())
                .putString(KEY_IV, cipher.iv.encoded())
                .apply()
        } finally {
            rawKey.fill(0)
        }
    }

    override fun unwrap(cipher: Cipher): ByteArray? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            cipher.doFinal(blob.decoded())
        } catch (_: Exception) {
            null
        }
    }

    override fun forget() {
        prefs.edit().remove(KEY_BLOB).remove(KEY_IV).apply()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    // Keystore plumbing

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? =
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun obtainKey(): SecretKey = existingKey() ?: generateKey()

    private fun generateKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun ByteArray.encoded(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.decoded(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "boxpix_vault_wrap"
        const val PREFS_NAME = "boxpix_vault"
        const val KEY_BLOB = "wrapped_masterkey"
        const val KEY_IV = "wrapped_masterkey_iv"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
