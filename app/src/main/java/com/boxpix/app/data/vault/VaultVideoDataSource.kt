package com.boxpix.app.data.vault

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * media3 DataSource over an encrypted vault file: ExoPlayer's range requests
 * become chunk-aligned decrypting reads through [VaultFileHandle] — streaming
 * and seeking without ever writing cleartext to disk. Read-ahead is bounded
 * ([READ_AHEAD_BYTES] per fetch); everything dies with the player or the lock.
 *
 * The loader thread is ExoPlayer's own (never main); [runBlocking] bridges the
 * suspend provider calls and surfaces interruption/lock as IOException, which
 * the player reports as a source error.
 */
@OptIn(UnstableApi::class)
class VaultVideoDataSource private constructor(
    private val session: VaultSession,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var handle: VaultFileHandle? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var buffer = ByteArray(0)
    private var bufferStart = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val relativePath = dataSpec.uri.path
            ?.takeIf { dataSpec.uri.scheme == SCHEME && it.isNotEmpty() }
            ?: throw IOException("not a vault uri: ${dataSpec.uri}")
        val provider = session.provider ?: throw IOException("vault is locked")
        val opened = runBlocking {
            provider.openFile(com.boxpix.app.data.freebox.api.PathCodec.encode(relativePath))
        }
        val fileHandle = opened.getOrNull() ?: throw IOException("vault open failed: $opened")
        handle = fileHandle

        position = dataSpec.position
        if (position > fileHandle.cleartextSize) throw IOException("position past end of file")
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            fileHandle.cleartextSize - position
        }
        buffer = ByteArray(0)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT
        val fileHandle = handle ?: throw IOException("source not open")

        if (position < bufferStart || position >= bufferStart + buffer.size) {
            buffer = try {
                runBlocking { fileHandle.read(position, READ_AHEAD_BYTES) }
            } catch (e: InterruptedException) {
                throw IOException(e)
            }
            bufferStart = position
            if (buffer.isEmpty()) return C.RESULT_END_OF_INPUT
        }

        val within = (position - bufferStart).toInt()
        val available = buffer.size - within
        val toCopy = minOf(length.toLong(), available.toLong(), bytesRemaining).toInt()
        System.arraycopy(buffer, within, target, offset, toCopy)
        position += toCopy
        bytesRemaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (handle != null) {
            handle = null
            buffer = ByteArray(0)
            transferEnded()
        }
        uri = null
    }

    class Factory(private val session: VaultSession) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultVideoDataSource(session)
    }

    companion object {
        const val SCHEME = "boxpix-vault"

        /** ~1.5 MB of ciphertext per round-trip: smooth playback, bounded RAM. */
        private const val READ_AHEAD_BYTES = 1_536 * 1024

        fun uriFor(vaultRelativePath: String): Uri =
            Uri.Builder().scheme(SCHEME).path(vaultRelativePath).build()
    }
}
