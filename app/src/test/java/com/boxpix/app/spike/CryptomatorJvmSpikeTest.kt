package com.boxpix.app.spike

import com.google.common.io.BaseEncoding
import org.cryptomator.cryptolib.api.CryptorProvider
import org.cryptomator.cryptolib.common.DecryptingReadableByteChannel
import org.cryptomator.cryptolib.common.MasterkeyFileAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.security.SecureRandom

/**
 * M8 SPIKE — THROWAWAY CODE. Sanity check: can cryptolib 2.2.0 open a real
 * Cryptomator vault (format 8, SIV_GCM) on the JVM? Runs only on this Mac
 * (hardcoded vault path, skipped elsewhere via assumeTrue).
 */
class CryptomatorJvmSpikeTest {

    private companion object {
        const val VAULT_DIR = "/Users/ychoucoutou/Downloads/vault-test"
        const val PASSPHRASE = "test1234"

        // Unlock once per JVM: scrypt (cost 32768) is deliberately slow.
        val cryptor by lazy {
            val masterkey = MasterkeyFileAccess(ByteArray(0), SecureRandom())
                .load(File(VAULT_DIR, "masterkey.cryptomator").toPath(), PASSPHRASE)
            CryptorProvider.forScheme(CryptorProvider.Scheme.SIV_GCM)
                .provide(masterkey, SecureRandom())
        }
    }

    private fun vaultPresent() = assumeTrue(File(VAULT_DIR).isDirectory)

    private fun rootDirFiles(): List<File> {
        val hash = cryptor.fileNameCryptor().hashDirectoryId("")
        val dir = File(VAULT_DIR, "d/${hash.take(2)}/${hash.drop(2)}")
        assertTrue("root dir ${dir.path} must exist", dir.isDirectory)
        return dir.listFiles()!!.filter { it.isFile && it.name != "dirid.c9r" }
    }

    @Test
    fun `unlock vault and decrypt every filename in root`() {
        vaultPresent()
        val t0 = System.nanoTime()
        val names = rootDirFiles().map { file ->
            cryptor.fileNameCryptor().decryptFilename(
                BaseEncoding.base64Url(),
                file.name.removeSuffix(".c9r"),
                ByteArray(0), // root directory id is the empty string
            )
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("SPIKE unlock+decrypt ${names.size} names in ${ms}ms: $names")
        assertTrue(names.isNotEmpty())
        assertTrue(names.any { it.endsWith(".dmg", ignoreCase = true) })
    }

    @Test
    fun `decrypt a photo end-to-end and check magic bytes`() {
        vaultPresent()
        val photo = rootDirFiles().filter { it.length() in 1_000_000..10_000_000 }
            .minByOrNull { it.length() }!!
        val out = ByteBuffer.allocate(photo.length().toInt())
        val t0 = System.nanoTime()
        photo.inputStream().channel.use { src ->
            DecryptingReadableByteChannel(src, cryptor, true).use { dec ->
                while (dec.read(out) != -1) { /* drain */ }
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        out.flip()
        val magicOk = (out.get(0) == 0xFF.toByte() && out.get(1) == 0xD8.toByte()) || // JPEG
            (out.get(0) == 0x89.toByte() && out.get(1) == 'P'.code.toByte()) || // PNG
            (out.get(4) == 'f'.code.toByte() && out.get(5) == 't'.code.toByte()) // HEIC ftyp
        println("SPIKE photo ${photo.length()} B ciphertext -> ${out.limit()} B cleartext in ${ms}ms")
        assertTrue("decrypted photo should start with a known image magic", magicOk)
    }

    @Test
    fun `stream-decrypt the 370 MB file with bounded memory and measure throughput`() {
        vaultPresent()
        val big = rootDirFiles().maxByOrNull { it.length() }!!
        val headerSize = cryptor.fileHeaderCryptor().headerSize()
        val expectedClear = cryptor.fileContentCryptor().cleartextSize(big.length() - headerSize)
        val buf = ByteBuffer.allocate(512 * 1024) // constant memory, viewer-style
        var total = 0L
        val t0 = System.nanoTime()
        big.inputStream().channel.use { src ->
            DecryptingReadableByteChannel(src, cryptor, true).use { dec ->
                while (true) {
                    buf.clear()
                    val n = dec.read(buf)
                    if (n == -1) break
                    total += n
                }
            }
        }
        val seconds = (System.nanoTime() - t0) / 1e9
        println("SPIKE big file: $total B in %.2fs = %.1f MB/s (JVM)".format(seconds, total / seconds / 1_048_576))
        assertEquals(expectedClear, total)
    }

    @Test
    fun `random access - decrypt one 32k chunk in the middle without reading the rest`() {
        vaultPresent()
        val big = rootDirFiles().maxByOrNull { it.length() }!!
        val headerCryptor = cryptor.fileHeaderCryptor()
        val contentCryptor = cryptor.fileContentCryptor()
        val chunkIndex = 5_000L // ~163 MB into the cleartext
        val t0 = System.nanoTime()
        val cleartext = java.io.RandomAccessFile(big, "r").use { raf ->
            val headerBytes = ByteArray(headerCryptor.headerSize())
            raf.readFully(headerBytes)
            val header = headerCryptor.decryptHeader(ByteBuffer.wrap(headerBytes))
            val chunkBytes = ByteArray(contentCryptor.ciphertextChunkSize())
            raf.seek(headerCryptor.headerSize() + chunkIndex * contentCryptor.ciphertextChunkSize())
            raf.readFully(chunkBytes)
            contentCryptor.decryptChunk(ByteBuffer.wrap(chunkBytes), chunkIndex, header, true)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("SPIKE random access: chunk $chunkIndex -> ${cleartext.remaining()} B cleartext in ${ms}ms")
        assertEquals(contentCryptor.cleartextChunkSize(), cleartext.remaining())
    }
}
