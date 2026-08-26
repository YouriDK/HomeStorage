package com.boxpix.app.data.vault

import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.support.TestSupport
import com.google.common.io.BaseEncoding
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.cryptomator.cryptolib.api.Cryptor
import org.cryptomator.cryptolib.api.CryptorProvider
import org.cryptomator.cryptolib.api.Masterkey
import org.cryptomator.cryptolib.common.EncryptingWritableByteChannel
import org.cryptomator.cryptolib.common.MasterkeyFileAccess
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Builds a tiny but REAL Cryptomator vault (format 8, SIV_GCM) in memory and
 * installs it into a StorageProvider under `<disk root>/.vault/` — the same
 * bytes Cryptomator desktop would write, produced by cryptolib itself.
 *
 * Cleartext tree:
 *   /photo.jpg              (TestSupport.TINY_JPEG)
 *   /Holidays/beach.jpg
 *   /Holidays/2024/deep.png
 *   /.DS_Store, /._photo.jpg  (macOS noise, must stay invisible)
 * plus, at the physical layer: a stray non-.c9r file and a .c9r entry that no
 * key of this vault produced (both must be skipped by the provider).
 *
 * The masterkey is generated once per JVM (scrypt is slow by design).
 */
object VaultFixture {

    const val PASSPHRASE = "fixture-pass"
    const val WRONG_PASSPHRASE = "not-the-pass"

    val deepPngBytes = ByteArray(3_072) { (it * 31).toByte() }
    val beachJpgBytes = TestSupport.TINY_JPEG + ByteArray(512) { it.toByte() }

    private val csprng = SecureRandom()
    private val masterkey: Masterkey by lazy { Masterkey.generate(csprng) }

    val masterkeyFileBytes: ByteArray by lazy {
        ByteArrayOutputStream().also {
            MasterkeyFileAccess(ByteArray(0), csprng).persist(masterkey, it, PASSPHRASE, 999)
        }.toByteArray()
    }

    val configJwt: String by lazy { signedConfig(format = 8, cipherCombo = "SIV_GCM") }

    /** A structurally valid config whose signature no masterkey produced. */
    val tamperedConfigJwt: String by lazy {
        val parts = configJwt.split('.').toMutableList()
        parts[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        parts.joinToString(".")
    }

    private val cryptor: Cryptor by lazy {
        CryptorProvider.forScheme(CryptorProvider.Scheme.SIV_GCM).provide(masterkey.copy(), csprng)
    }

    /** Installs the vault into [provider] under `<diskRootDisplay>/.vault/`. */
    suspend fun install(provider: StorageProvider, diskRootDisplay: String = "/Photos") {
        val vaultRoot = "$diskRootDisplay/${VaultFormat.VAULT_DIR}"
        mkdirs(provider, vaultRoot)
        upload(provider, vaultRoot, VaultFormat.CONFIG_FILE, configJwt.toByteArray(Charsets.US_ASCII))
        upload(provider, vaultRoot, VaultFormat.MASTERKEY_FILE, masterkeyFileBytes)

        val rootDirId = VaultFormat.ROOT_DIR_ID
        val holidaysDirId = "11111111-2222-3333-4444-555555555555"
        val y2024DirId = "66666666-7777-8888-9999-000000000000"

        val rootPhysical = physicalDir(vaultRoot, rootDirId)
        val holidaysPhysical = physicalDir(vaultRoot, holidaysDirId)
        val y2024Physical = physicalDir(vaultRoot, y2024DirId)
        listOf(rootPhysical, holidaysPhysical, y2024Physical).forEach { mkdirs(provider, it) }

        // Root: one photo, macOS noise, one subfolder node pointing at Holidays.
        putFile(provider, rootPhysical, rootDirId, "photo.jpg", TestSupport.TINY_JPEG)
        putFile(provider, rootPhysical, rootDirId, ".DS_Store", ByteArray(16))
        putFile(provider, rootPhysical, rootDirId, "._photo.jpg", ByteArray(16))
        putDirNode(provider, rootPhysical, rootDirId, "Holidays", holidaysDirId)

        // Physical-layer noise nothing should ever surface.
        upload(provider, rootPhysical, "garbage.txt", ByteArray(8))
        upload(provider, rootPhysical, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA.c9r", ByteArray(64))

        putFile(provider, holidaysPhysical, holidaysDirId, "beach.jpg", beachJpgBytes)
        putDirNode(provider, holidaysPhysical, holidaysDirId, "2024", y2024DirId)

        putFile(provider, y2024Physical, y2024DirId, "deep.png", deepPngBytes)
    }

    fun encryptContent(cleartext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        EncryptingWritableByteChannel(Channels.newChannel(out), cryptor).use {
            it.write(ByteBuffer.wrap(cleartext))
        }
        return out.toByteArray()
    }

    fun encryptedName(clearName: String, dirId: String): String =
        cryptor.fileNameCryptor().encryptFilename(
            BaseEncoding.base64Url(),
            clearName,
            dirId.toByteArray(Charsets.UTF_8),
        ) + VaultFormat.C9R_SUFFIX

    // Internals

    private fun physicalDir(vaultRoot: String, dirId: String): String =
        VaultFormat.physicalDirPath(vaultRoot, cryptor.fileNameCryptor().hashDirectoryId(dirId))

    private suspend fun putFile(
        provider: StorageProvider,
        physicalDir: String,
        dirId: String,
        clearName: String,
        cleartext: ByteArray,
    ) {
        upload(provider, physicalDir, encryptedName(clearName, dirId), encryptContent(cleartext))
    }

    private suspend fun putDirNode(
        provider: StorageProvider,
        physicalParent: String,
        parentDirId: String,
        clearName: String,
        targetDirId: String,
    ) {
        val nodeDir = "$physicalParent/${encryptedName(clearName, parentDirId)}"
        mkdirs(provider, nodeDir)
        upload(provider, nodeDir, VaultFormat.DIR_ID_FILE, targetDirId.toByteArray(Charsets.UTF_8))
    }

    private suspend fun mkdirs(provider: StorageProvider, displayPath: String) {
        val segments = displayPath.trimStart('/').split('/')
        var parent = "/"
        segments.forEach { segment ->
            val next = if (parent == "/") "/$segment" else "$parent/$segment"
            provider.mkdir(PathCodec.encode(parent), segment) // conflict = already there
            parent = next
        }
    }

    private suspend fun upload(provider: StorageProvider, dir: String, name: String, bytes: ByteArray) {
        val result = provider.upload(PathCodec.encode(dir), name, bytes)
        check(result.getOrNull() != null) { "fixture upload failed for $dir/$name: $result" }
    }

    private fun signedConfig(format: Int, cipherCombo: String): String {
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val header = buildJsonObject {
            put("kid", "masterkeyfile:masterkey.cryptomator")
            put("alg", "HS256")
            put("typ", "JWT")
        }
        val claims = buildJsonObject {
            put("jti", "vault-fixture")
            put("format", format)
            put("cipherCombo", cipherCombo)
            put("shorteningThreshold", 220)
        }
        val signingInput =
            b64.encodeToString(header.toString().toByteArray(Charsets.UTF_8)) + "." +
                b64.encodeToString(claims.toString().toByteArray(Charsets.UTF_8))
        val signature = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(masterkey.encoded, "HmacSHA256"))
            doFinal(signingInput.toByteArray(Charsets.US_ASCII))
        }
        return "$signingInput.${b64.encodeToString(signature)}"
    }
}
