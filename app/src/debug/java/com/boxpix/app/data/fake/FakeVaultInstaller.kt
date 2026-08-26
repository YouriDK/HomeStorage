package com.boxpix.app.data.fake

import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.data.vault.VaultFormat
import com.google.common.io.BaseEncoding
import org.cryptomator.cryptolib.api.CryptorProvider
import org.cryptomator.cryptolib.api.Masterkey
import org.cryptomator.cryptolib.common.EncryptingWritableByteChannel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.security.SecureRandom
import java.util.Base64

/**
 * Seeds a REAL Cryptomator vault (format 8) into the fake tree at
 * `/Photos/.vault/`, so the whole M8 flow — tile, unlock, browsing, and the
 * upcoming index/thumbs/video lots — can be exercised on an emulator with no
 * box and no desktop app. Passphrase: "boxpix".
 *
 * The masterkey file, config JWT and raw key are pre-generated constants
 * (regenerating them is a 10-line cryptolib snippet): embedding the raw key
 * skips scrypt at seeding time, while the UI unlock still runs the true
 * scrypt path against the embedded masterkey file. Fixture material only —
 * these constants protect nothing.
 */
object FakeVaultInstaller {

    const val PASSPHRASE = "boxpix"

    private const val RAW_KEY_B64 =
        "55XjZdTf4w4n/PBCpd5eiq/+CuaEZq+psrrSU/h240yZ+YjG17zHg6bheMewjHqOT9zgL59R5aT6TOngWcnnZw=="
    private const val MASTERKEY_FILE_B64 =
        "ewogICJ2ZXJzaW9uIjogOTk5LAogICJzY3J5cHRTYWx0IjogIkI3RDM1SUp3c3hrPSIsCiAgInNjcnlwdENvc3RQYXJhb" +
            "SI6IDMyNzY4LAogICJzY3J5cHRCbG9ja1NpemUiOiA4LAogICJwcmltYXJ5TWFzdGVyS2V5IjogIm5CaXptTUpCVVRDZ" +
            "VdqcFNkTEkveUJ5bStRbEc5dGZVOVBMSXh3VFV0aldJTTdFUTVNZzdRQT09IiwKICAiaG1hY01hc3RlcktleSI6ICJRN" +
            "TNzaTRUZU05cndjalBkOEZjcFRoL3BSaGpuckVaRk9GQzNvc3AvUVJHOHZic3ZwbFRHU1E9PSIsCiAgInZlcnNpb25NY" +
            "WMiOiAidlg0Z1ZtUzB4Tk9ITll5UGd5OW12aHRPTEdpQW1ld0xNMFA0dDJpSXJ5VT0iCn0="
    private const val CONFIG_JWT =
        "eyJraWQiOiJtYXN0ZXJrZXlmaWxlOm1hc3RlcmtleS5jcnlwdG9tYXRvciIsImFsZyI6IkhTMjU2IiwidHlwIjoiSldUIn0." +
            "eyJqdGkiOiJib3hwaXgtZmFrZS12YXVsdCIsImZvcm1hdCI6OCwiY2lwaGVyQ29tYm8iOiJTSVZfR0NNIiwic2hvcnRlb" +
            "mluZ1RocmVzaG9sZCI6MjIwfQ.Ic8RyhfeVzwseC4Vu5cdDMDvuRCO4Yf8XPXME8RJ37w"

    private const val HOLIDAYS_DIR_ID = "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb"

    /** Idempotent; failures are swallowed — a fake without a vault still works. */
    suspend fun install(
        provider: StorageProvider,
        synthesizer: FakeImageSynthesizer?,
        diskRootDisplay: String = "/Photos",
    ) {
        runCatching { doInstall(provider, synthesizer, diskRootDisplay) }
    }

    private suspend fun doInstall(
        provider: StorageProvider,
        synthesizer: FakeImageSynthesizer?,
        diskRootDisplay: String,
    ) {
        val raw = Base64.getDecoder().decode(RAW_KEY_B64)
        val cryptor = CryptorProvider.forScheme(CryptorProvider.Scheme.SIV_GCM)
            .provide(Masterkey(raw), SecureRandom())
        val names = cryptor.fileNameCryptor()

        val vaultRoot = "$diskRootDisplay/${VaultFormat.VAULT_DIR}"
        mkdirs(provider, vaultRoot)
        upload(provider, vaultRoot, VaultFormat.CONFIG_FILE, CONFIG_JWT.toByteArray(Charsets.US_ASCII))
        upload(provider, vaultRoot, VaultFormat.MASTERKEY_FILE, Base64.getDecoder().decode(MASTERKEY_FILE_B64))

        fun physical(dirId: String) = VaultFormat.physicalDirPath(vaultRoot, names.hashDirectoryId(dirId))
        fun encName(clear: String, dirId: String) = names.encryptFilename(
            BaseEncoding.base64Url(),
            clear,
            dirId.toByteArray(Charsets.UTF_8),
        ) + VaultFormat.C9R_SUFFIX

        val rootPhysical = physical(VaultFormat.ROOT_DIR_ID)
        val holidaysPhysical = physical(HOLIDAYS_DIR_ID)
        mkdirs(provider, rootPhysical)
        mkdirs(provider, holidaysPhysical)

        suspend fun putPhoto(dirPhysical: String, dirId: String, name: String, seed: Int, takenAt: Long) {
            val photo = synthesizer?.jpegWithExif(seed, takenAt)
                ?: ByteArray(4_096) { (seed + it).toByte() }
            upload(provider, dirPhysical, encName(name, dirId), encrypt(photo, cryptor))
        }

        val base = 1_690_000_000L
        putPhoto(rootPhysical, VaultFormat.ROOT_DIR_ID, "secret-01.jpg", 101, base)
        putPhoto(rootPhysical, VaultFormat.ROOT_DIR_ID, "secret-02.jpg", 102, base + 86_400)
        putPhoto(rootPhysical, VaultFormat.ROOT_DIR_ID, "secret-03.jpg", 103, base + 2 * 86_400)

        val holidaysNode = "$rootPhysical/${encName("Holidays", VaultFormat.ROOT_DIR_ID)}"
        mkdirs(provider, holidaysNode)
        upload(provider, holidaysNode, VaultFormat.DIR_ID_FILE, HOLIDAYS_DIR_ID.toByteArray(Charsets.UTF_8))

        putPhoto(holidaysPhysical, HOLIDAYS_DIR_ID, "beach.jpg", 201, base + 3 * 86_400)
        putPhoto(holidaysPhysical, HOLIDAYS_DIR_ID, "sunset.jpg", 202, base + 4 * 86_400)

        cryptor.destroy()
    }

    private fun encrypt(cleartext: ByteArray, cryptor: org.cryptomator.cryptolib.api.Cryptor): ByteArray {
        val out = ByteArrayOutputStream()
        EncryptingWritableByteChannel(Channels.newChannel(out), cryptor).use {
            it.write(ByteBuffer.wrap(cleartext))
        }
        return out.toByteArray()
    }

    private suspend fun mkdirs(provider: StorageProvider, displayPath: String) {
        var parent = "/"
        displayPath.trimStart('/').split('/').forEach { segment ->
            provider.mkdir(PathCodec.encode(parent), segment) // conflict = already there
            parent = if (parent == "/") "/$segment" else "$parent/$segment"
        }
    }

    private suspend fun upload(provider: StorageProvider, dir: String, name: String, bytes: ByteArray) {
        provider.upload(PathCodec.encode(dir), name, bytes)
    }
}
