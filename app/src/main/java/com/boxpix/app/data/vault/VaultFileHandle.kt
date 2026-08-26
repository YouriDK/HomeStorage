package com.boxpix.app.data.vault

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.storage.StorageProvider
import org.cryptomator.cryptolib.api.Cryptor
import org.cryptomator.cryptolib.api.FileHeader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Random access over one encrypted vault file: the header is decrypted once,
 * then any cleartext range maps to a bounded ciphertext RANGE request on the
 * disk (Cryptomator chunks are independently decryptable — spike-validated at
 * ~3 ms/chunk on device). This is what lets ExoPlayer seek inside a vault
 * video without ever downloading the whole file.
 */
class VaultFileHandle internal constructor(
    private val inner: StorageProvider,
    private val cryptor: Cryptor,
    private val physicalPathB64: String,
    private val ciphertextSize: Long,
    private val header: FileHeader,
) {

    private val headerSize = cryptor.fileHeaderCryptor().headerSize()
    private val clearChunk = cryptor.fileContentCryptor().cleartextChunkSize()
    private val cipherChunk = cryptor.fileContentCryptor().ciphertextChunkSize()

    val cleartextSize: Long =
        cryptor.fileContentCryptor().cleartextSize(ciphertextSize - headerSize)

    /**
     * Decrypts up to [maxLength] cleartext bytes starting at [cleartextOffset]
     * (chunk-bounded: one ciphertext range request, `ceil` chunks). Returns
     * fewer bytes only at end of file; empty at EOF.
     */
    @Throws(IOException::class)
    suspend fun read(cleartextOffset: Long, maxLength: Int): ByteArray {
        if (cleartextOffset >= cleartextSize) return ByteArray(0)
        val firstChunk = cleartextOffset / clearChunk
        val lastWanted = (cleartextOffset + maxLength - 1).coerceAtMost(cleartextSize - 1)
        val lastChunk = lastWanted / clearChunk

        val cipherFrom = headerSize + firstChunk * cipherChunk
        val cipherTo = (headerSize + (lastChunk + 1) * cipherChunk - 1)
            .coerceAtMost(ciphertextSize - 1)
        val ciphertext = when (val r = inner.download(physicalPathB64, cipherFrom..cipherTo)) {
            is FbxResult.Ok -> r.value
            is FbxResult.Err -> throw IOException("vault range read failed: ${r.error}")
        }

        val out = ByteArrayOutputStream(maxLength)
        var chunkIndex = firstChunk
        var consumed = 0
        while (consumed < ciphertext.size) {
            val end = (consumed + cipherChunk).coerceAtMost(ciphertext.size)
            val clear = try {
                cryptor.fileContentCryptor().decryptChunk(
                    // Zero-based copy on purpose: cryptolib's decryptChunk sets
                    // the payload position ABSOLUTELY to the nonce size, so a
                    // chunk buffer must start at position 0 — an offset wrap
                    // trips its internal asserts.
                    ByteBuffer.wrap(ciphertext.copyOfRange(consumed, end)),
                    chunkIndex,
                    header,
                    true,
                )
            } catch (e: Exception) {
                throw IOException("vault chunk $chunkIndex failed authentication", e)
            }
            out.write(clear.array(), clear.arrayOffset() + clear.position(), clear.remaining())
            consumed = end
            chunkIndex++
        }

        val bytes = out.toByteArray()
        val skip = (cleartextOffset - firstChunk * clearChunk).toInt()
        val length = (lastWanted - cleartextOffset + 1).toInt().coerceAtMost(bytes.size - skip)
        return bytes.copyOfRange(skip, skip + length)
    }
}
