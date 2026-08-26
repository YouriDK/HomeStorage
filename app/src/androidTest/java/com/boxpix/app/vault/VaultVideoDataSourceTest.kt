package com.boxpix.app.vault

import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.vault.UnlockResult
import com.boxpix.app.data.vault.VaultFormat
import com.boxpix.app.data.vault.VaultSession
import com.boxpix.app.data.vault.VaultVideoDataSource
import com.google.common.io.BaseEncoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.cryptomator.cryptolib.api.CryptorProvider
import org.cryptomator.cryptolib.api.Masterkey
import org.cryptomator.cryptolib.common.EncryptingWritableByteChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * M8 lot 5 gate on a real device: ExoPlayer's DataSource contract (open at a
 * position, sequential reads, reopen to seek) served by chunk-aligned
 * decryption over the storage provider. Raw-key unlock: no scrypt needed here.
 */
@RunWith(AndroidJUnit4::class)
class VaultVideoDataSourceTest {

    private val clip = ByteArray(200_000) { (it * 13).toByte() }

    private fun buildVault(fake: FakeStorageProvider): ByteArray = runBlocking {
        val csprng = SecureRandom()
        val masterkey = Masterkey.generate(csprng)
        val raw = masterkey.encoded.copyOf()
        val cryptor = CryptorProvider.forScheme(CryptorProvider.Scheme.SIV_GCM)
            .provide(masterkey, csprng)

        suspend fun mkdirs(path: String) {
            var parent = "/"
            path.trimStart('/').split('/').forEach {
                fake.mkdir(PathCodec.encode(parent), it)
                parent = if (parent == "/") "/$it" else "$parent/$it"
            }
        }

        val vaultRoot = "/Photos/.vault"
        mkdirs(vaultRoot)
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val header = """{"kid":"masterkeyfile:masterkey.cryptomator","alg":"HS256","typ":"JWT"}"""
        val claims = """{"jti":"t","format":8,"cipherCombo":"SIV_GCM","shorteningThreshold":220}"""
        val input = b64.encodeToString(header.toByteArray()) + "." + b64.encodeToString(claims.toByteArray())
        val sig = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(raw, "HmacSHA256"))
            doFinal(input.toByteArray(Charsets.US_ASCII))
        }
        fake.upload(
            PathCodec.encode(vaultRoot),
            VaultFormat.CONFIG_FILE,
            "$input.${b64.encodeToString(sig)}".toByteArray(Charsets.US_ASCII),
        )

        val names = cryptor.fileNameCryptor()
        val rootPhysical = VaultFormat.physicalDirPath(vaultRoot, names.hashDirectoryId(""))
        mkdirs(rootPhysical)
        val encName = names.encryptFilename(BaseEncoding.base64Url(), "clip.mp4", ByteArray(0)) +
            VaultFormat.C9R_SUFFIX
        val out = ByteArrayOutputStream()
        EncryptingWritableByteChannel(Channels.newChannel(out), cryptor).use {
            it.write(ByteBuffer.wrap(clip))
        }
        fake.upload(PathCodec.encode(rootPhysical), encName, out.toByteArray())
        cryptor.destroy()
        raw
    }

    @Test
    fun streamsAndSeeksThroughTheDecryptingSource() {
        val fake = FakeStorageProvider(
            FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0),
        )
        val raw = buildVault(fake)
        val session = VaultSession(fake, RootLocator { PathCodec.encode("/Photos") }, Dispatchers.Default)
        runBlocking {
            session.probe()
            assertEquals(UnlockResult.Success, session.unlockWithRawKey(raw))
        }

        val factory = VaultVideoDataSource.Factory(session)
        val uri = VaultVideoDataSource.uriFor("/clip.mp4")

        // Full sequential stream.
        val ds = factory.createDataSource()
        val total = ds.open(DataSpec(uri))
        assertEquals(clip.size.toLong(), total)
        val streamed = ByteArrayOutputStream()
        val buffer = ByteArray(7_919) // prime-sized reads exercise the windowing
        while (true) {
            val n = ds.read(buffer, 0, buffer.size)
            if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) break
            streamed.write(buffer, 0, n)
        }
        ds.close()
        assertArrayEquals(clip, streamed.toByteArray())

        // Seek: reopen mid-file with a bounded length, like ExoPlayer does.
        val seek = factory.createDataSource()
        val seekLength = seek.open(
            DataSpec.Builder().setUri(uri).setPosition(150_000L).setLength(10_000L).build(),
        )
        assertEquals(10_000L, seekLength)
        val seeked = ByteArray(10_000)
        var got = 0
        while (got < seeked.size) {
            val n = seek.read(seeked, got, seeked.size - got)
            if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) break
            got += n
        }
        seek.close()
        assertEquals(10_000, got)
        assertArrayEquals(clip.copyOfRange(150_000, 160_000), seeked)

        // Lock mid-session: the next open fails closed.
        runBlocking { session.lock() }
        val locked = factory.createDataSource()
        try {
            locked.open(DataSpec(uri))
            throw AssertionError("open must fail on a locked vault")
        } catch (expected: IOException) {
            // fail-closed, as designed
        }
    }
}
