package com.boxpix.app.data.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * On-disk layout of a Cryptomator vault (format 8), as created by Cryptomator
 * desktop. The app only ever OPENS such a vault (SPEC M8): it never creates
 * one and never handles the recovery key.
 */
object VaultFormat {
    /** The single vault lives at `<disk root>/.vault/`. */
    const val VAULT_DIR = ".vault"
    const val CONFIG_FILE = "vault.cryptomator"
    const val MASTERKEY_FILE = "masterkey.cryptomator"
    const val DATA_DIR = "d"

    /** Every encrypted node (file, folder, symlink) carries this suffix. */
    const val C9R_SUFFIX = ".c9r"

    /** Inside a folder node: the plaintext directory id of the folder it points to. */
    const val DIR_ID_FILE = "dir.c9r"

    /** Encrypted backup of a directory's own id, present in each physical dir. */
    const val DIR_ID_BACKUP = "dirid.c9r"

    /** Root directory id is the empty string by definition of the format. */
    const val ROOT_DIR_ID = ""

    /** Physical location of a hashed directory id: `d/<2 chars>/<30 chars>`. */
    fun physicalDirPath(vaultRootDisplay: String, hashedDirId: String): String =
        "$vaultRootDisplay/$DATA_DIR/${hashedDirId.take(2)}/${hashedDirId.drop(2)}"
}

/**
 * Minimal verification of `vault.cryptomator` — a JWT signed with the raw
 * 512-bit masterkey. Signature check uses the platform's HmacSHA256 (same JCE
 * Mac the Freebox login already relies on; no hand-rolled primitive), then the
 * claims are checked against what this app supports.
 */
object VaultConfig {
    const val SUPPORTED_FORMAT = 8
    const val SUPPORTED_CIPHER_COMBO = "SIV_GCM"

    sealed interface Check {
        data class Supported(val shorteningThreshold: Int) : Check
        data class Unsupported(val reason: String) : Check
        data object BadSignature : Check
        data object Malformed : Check
    }

    @Serializable
    private data class Header(val alg: String = "", val kid: String = "")

    @Serializable
    private data class Claims(
        val format: Int = -1,
        val cipherCombo: String = "",
        val shorteningThreshold: Int = 220,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun verify(jwt: String, rawMasterkey: ByteArray): Check {
        val parts = jwt.trim().split('.')
        if (parts.size != 3) return Check.Malformed
        val (header, claims) = try {
            val decoder = Base64.getUrlDecoder()
            json.decodeFromString<Header>(String(decoder.decode(parts[0]), Charsets.UTF_8)) to
                json.decodeFromString<Claims>(String(decoder.decode(parts[1]), Charsets.UTF_8))
        } catch (_: Exception) {
            return Check.Malformed
        }
        if (header.alg != "HS256") return Check.Unsupported("alg=${header.alg}")
        val expected = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(rawMasterkey, "HmacSHA256"))
            doFinal("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        }
        val actual = try {
            Base64.getUrlDecoder().decode(parts[2])
        } catch (_: IllegalArgumentException) {
            return Check.Malformed
        }
        if (!MessageDigest.isEqual(expected, actual)) return Check.BadSignature
        return when {
            claims.format != SUPPORTED_FORMAT -> Check.Unsupported("format=${claims.format}")
            claims.cipherCombo != SUPPORTED_CIPHER_COMBO ->
                Check.Unsupported("cipherCombo=${claims.cipherCombo}")
            else -> Check.Supported(claims.shorteningThreshold)
        }
    }
}
