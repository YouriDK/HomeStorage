package com.boxpix.app.spike

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.io.BaseEncoding
import org.cryptomator.cryptolib.api.Cryptor
import org.cryptomator.cryptolib.api.CryptorProvider
import org.cryptomator.cryptolib.common.DecryptingReadableByteChannel
import org.cryptomator.cryptolib.common.MasterkeyFileAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * M8 SPIKE — THROWAWAY CODE. Answers "does cryptolib work on the real device,
 * and how fast?". Needs the test vault pushed beforehand:
 *   adb push ~/Downloads/vault-test /data/local/tmp/
 * (adb push into Android/data lands in a FUSE view the app cannot read on this
 * Samsung, so the test copies the vault into filesDir itself, through the
 * instrumentation's shell identity.)
 * All measurements land in logcat under the SPIKE tag.
 */
@RunWith(AndroidJUnit4::class)
class CryptomatorDeviceSpikeTest {

    private companion object {
        const val TAG = "SPIKE"
        const val PASSPHRASE = "test1234"
        const val PUSHED = "/data/local/tmp/vault-test"

        val vaultDir: File by lazy {
            val instr = InstrumentationRegistry.getInstrumentation()
            val target = File(instr.targetContext.filesDir, "vault-test")
            if (!File(target, "masterkey.cryptomator").exists()) {
                shellLines(instr, "find $PUSHED -type f").forEach { src ->
                    val dest = File(target, src.removePrefix("$PUSHED/"))
                    dest.parentFile!!.mkdirs()
                    val pfd = instr.uiAutomation.executeShellCommand("cat $src")
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                        dest.outputStream().use { input.copyTo(it, 1 shl 20) }
                    }
                }
                Log.i(TAG, "vault copied into ${target.path}")
            }
            target
        }

        fun shellLines(instr: android.app.Instrumentation, cmd: String): List<String> {
            val pfd = instr.uiAutomation.executeShellCommand(cmd)
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use {
                it.readBytes().decodeToString().lines().filter(String::isNotBlank)
            }
        }

        var unlockMs = -1L

        // One unlock per process: scrypt cost 32768 is deliberately slow.
        val cryptor: Cryptor by lazy {
            val t0 = System.nanoTime()
            val masterkey = MasterkeyFileAccess(ByteArray(0), SecureRandom())
                .load(File(vaultDir, "masterkey.cryptomator").toPath(), PASSPHRASE)
            val c = CryptorProvider.forScheme(CryptorProvider.Scheme.SIV_GCM)
                .provide(masterkey, SecureRandom())
            unlockMs = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "unlock (scrypt 32768 + cryptor init): ${unlockMs}ms")
            c
        }
    }

    private fun vaultPresent() = assumeTrue("push the vault first", vaultDir.isDirectory)

    private fun rootDirFiles(): List<File> {
        val hash = cryptor.fileNameCryptor().hashDirectoryId("")
        val dir = File(vaultDir, "d/${hash.take(2)}/${hash.drop(2)}")
        assertTrue("root dir ${dir.path} must exist", dir.isDirectory)
        return dir.listFiles()!!.filter { it.isFile && it.name != "dirid.c9r" }
    }

    @Test
    fun unlockAndDecryptFilenames() {
        vaultPresent()
        val files = rootDirFiles()
        val t0 = System.nanoTime()
        val names = files.map { file ->
            cryptor.fileNameCryptor().decryptFilename(
                BaseEncoding.base64Url(),
                file.name.removeSuffix(".c9r"),
                ByteArray(0),
            )
        }
        val us = (System.nanoTime() - t0) / 1_000
        Log.i(TAG, "decrypted ${names.size} filenames in ${us}µs (${us / names.size}µs each): $names")
        assertTrue(names.any { it.endsWith(".dmg", ignoreCase = true) })
    }

    @Test
    fun decryptPhotoAndDecodeBitmap() {
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
        val decryptMs = (System.nanoTime() - t0) / 1_000_000
        out.flip()
        val bytes = ByteArray(out.limit()).also { out.get(it) }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        Log.i(TAG, "photo ${photo.length()} B decrypted in ${decryptMs}ms -> ${opts.outWidth}x${opts.outHeight} ${opts.outMimeType}")
        assertTrue("decrypted bytes must decode as an image", opts.outWidth > 0 && opts.outHeight > 0)
    }

    @Test
    fun streamDecrypt370MbBoundedMemory() {
        vaultPresent()
        val big = rootDirFiles().maxByOrNull { it.length() }!!
        val headerSize = cryptor.fileHeaderCryptor().headerSize()
        val expectedClear = cryptor.fileContentCryptor().cleartextSize(big.length() - headerSize)
        val rt = Runtime.getRuntime()
        val heapBefore = rt.totalMemory() - rt.freeMemory()
        val buf = ByteBuffer.allocate(512 * 1024)
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
        val heapAfter = rt.totalMemory() - rt.freeMemory()
        Log.i(
            TAG,
            "big file: $total B in %.2fs = %.1f MB/s, heap delta %+d KB"
                .format(seconds, total / seconds / 1_048_576, (heapAfter - heapBefore) / 1024),
        )
        assertEquals(expectedClear, total)
    }

    @Test
    fun randomAccessSingleChunk() {
        vaultPresent()
        val big = rootDirFiles().maxByOrNull { it.length() }!!
        val headerCryptor = cryptor.fileHeaderCryptor()
        val contentCryptor = cryptor.fileContentCryptor()
        // Decrypt the header once, then 50 chunks scattered through the file —
        // the pattern a seeking ExoPlayer DataSource would produce.
        val t0 = System.nanoTime()
        var chunks = 0
        RandomAccessFile(big, "r").use { raf ->
            val headerBytes = ByteArray(headerCryptor.headerSize())
            raf.readFully(headerBytes)
            val header = headerCryptor.decryptHeader(ByteBuffer.wrap(headerBytes))
            val chunkBytes = ByteArray(contentCryptor.ciphertextChunkSize())
            val lastChunk = (big.length() - headerCryptor.headerSize()) / contentCryptor.ciphertextChunkSize() - 1
            var index = 0L
            while (index < lastChunk) {
                raf.seek(headerCryptor.headerSize() + index * contentCryptor.ciphertextChunkSize())
                raf.readFully(chunkBytes)
                val clear = contentCryptor.decryptChunk(ByteBuffer.wrap(chunkBytes), index, header, true)
                assertEquals(contentCryptor.cleartextChunkSize(), clear.remaining())
                chunks++
                index += lastChunk / 50
            }
        }
        val us = (System.nanoTime() - t0) / 1_000
        Log.i(TAG, "random access: header + $chunks scattered chunks in ${us}µs (${us / chunks}µs/chunk)")
    }
}
