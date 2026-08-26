package com.boxpix.app.data.vault

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.StorageCapabilities
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.storage.StorageProvider
import com.google.common.io.BaseEncoding
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cryptomator.cryptolib.api.AuthenticationFailedException
import org.cryptomator.cryptolib.api.Cryptor
import org.cryptomator.cryptolib.common.DecryptingReadableByteChannel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels

/**
 * StorageProvider view over a Cryptomator vault (format 8) stored behind
 * another provider. The UI never knows it is talking to a vault: paths are
 * cleartext paths rooted at the vault ("/Holidays/img.jpg"), encoded with the
 * same PathCodec as everywhere else, and entries carry decrypted names.
 *
 * Nothing that goes through this class may ever land in Room or in the clear
 * mirrors (.thumbs/.trash/.meta of the disk) — vault data stays in the vault.
 *
 * Directory resolution (cleartext dir -> hashed physical dir) walks the format:
 * SIV filename encryption is deterministic, so each segment is one
 * `dir.c9r` download, cached until a mutation or lock invalidates it.
 *
 * V1 limits (SPEC §10): shortened names (`.c9s`, cleartext names longer than
 * the shortening threshold) and symlinks are skipped at listing.
 */
class CryptomatorProvider(
    private val inner: StorageProvider,
    private val cryptor: Cryptor,
    /** Display path of the vault dir itself, e.g. "Disque 1/.vault". */
    private val vaultRootDisplay: String,
    private val cryptoDispatcher: CoroutineDispatcher,
) : StorageProvider {

    override val capabilities = StorageCapabilities(
        supportsRangeRequests = true,
        canCreateAtRoot = true, // the vault root is a real physical directory
    )

    private data class ResolvedDir(val dirId: String, val physicalDisplay: String)

    private val cacheMutex = Mutex()
    private val dirCache = HashMap<String, ResolvedDir>()

    override suspend fun list(pathB64: String?, onlyFolders: Boolean): FbxResult<List<StorageEntry>> {
        val clearDir = clearPath(pathB64)
        val dir = when (val resolved = resolveDir(clearDir)) {
            is FbxResult.Ok -> resolved.value
            is FbxResult.Err -> return resolved
        }
        val physical = when (val listed = inner.list(PathCodec.encode(dir.physicalDisplay))) {
            is FbxResult.Ok -> listed.value
            is FbxResult.Err -> return listed
        }
        val dirIdBytes = dir.dirId.toByteArray(Charsets.UTF_8)
        val entries = withContext(cryptoDispatcher) {
            physical.mapNotNull { entry -> toClearEntry(entry, clearDir, dirIdBytes) }
        }
        return FbxResult.Ok(entries.filter { !onlyFolders || it.isDirectory })
    }

    override suspend fun download(pathB64: String, range: LongRange?): FbxResult<ByteArray> {
        val clear = clearPath(pathB64)
        val physicalB64 = when (val resolved = resolveFile(clear)) {
            is FbxResult.Ok -> resolved.value
            is FbxResult.Err -> return resolved
        }
        val ciphertext = when (val downloaded = inner.download(physicalB64)) {
            is FbxResult.Ok -> downloaded.value
            is FbxResult.Err -> return downloaded
        }
        return withContext(cryptoDispatcher) {
            try {
                val cleartext = decrypt(ciphertext)
                FbxResult.Ok(if (range == null) cleartext else slice(cleartext, range))
            } catch (_: AuthenticationFailedException) {
                FbxResult.Err(FreeboxError.Api(ERROR_VAULT_INTEGRITY))
            }
        }
    }

    /** Drops every cached directory resolution; called on lock and after mutations. */
    suspend fun invalidateResolutionCache() {
        cacheMutex.withLock { dirCache.clear() }
    }

    /** Cache size, exposed for tests only. */
    internal suspend fun cachedDirCount(): Int = cacheMutex.withLock { dirCache.size }

    // Resolution

    private suspend fun resolveDir(clearDir: String): FbxResult<ResolvedDir> {
        cacheMutex.withLock { dirCache[clearDir] }?.let { return FbxResult.Ok(it) }

        val resolved = if (clearDir == "/") {
            ResolvedDir(VaultFormat.ROOT_DIR_ID, physicalDirOf(VaultFormat.ROOT_DIR_ID))
        } else {
            val parentPath = clearDir.substringBeforeLast('/').ifEmpty { "/" }
            val name = clearDir.substringAfterLast('/')
            val parent = when (val r = resolveDir(parentPath)) {
                is FbxResult.Ok -> r.value
                is FbxResult.Err -> return r
            }
            val encrypted = encryptName(name, parent.dirId)
            val dirIdFile = "${parent.physicalDisplay}/$encrypted/${VaultFormat.DIR_ID_FILE}"
            val dirId = when (val downloaded = inner.download(PathCodec.encode(dirIdFile))) {
                is FbxResult.Ok -> String(downloaded.value, Charsets.UTF_8)
                is FbxResult.Err -> return notFoundOr(downloaded)
            }
            ResolvedDir(dirId, physicalDirOf(dirId))
        }
        cacheMutex.withLock { dirCache[clearDir] = resolved }
        return FbxResult.Ok(resolved)
    }

    private suspend fun resolveFile(clearPath: String): FbxResult<String> {
        val parentPath = clearPath.substringBeforeLast('/').ifEmpty { "/" }
        val name = clearPath.substringAfterLast('/')
        if (name.isEmpty()) return FbxResult.Err(FreeboxError.Api(StorageProvider.ERROR_NOT_FOUND))
        val parent = when (val r = resolveDir(parentPath)) {
            is FbxResult.Ok -> r.value
            is FbxResult.Err -> return r
        }
        val encrypted = encryptName(name, parent.dirId)
        return FbxResult.Ok(PathCodec.encode("${parent.physicalDisplay}/$encrypted"))
    }

    private suspend fun encryptName(clearName: String, dirId: String): String =
        withContext(cryptoDispatcher) {
            cryptor.fileNameCryptor().encryptFilename(
                BaseEncoding.base64Url(),
                clearName,
                dirId.toByteArray(Charsets.UTF_8),
            ) + VaultFormat.C9R_SUFFIX
        }

    private fun physicalDirOf(dirId: String): String =
        VaultFormat.physicalDirPath(vaultRootDisplay, cryptor.fileNameCryptor().hashDirectoryId(dirId))

    // Entry mapping

    private fun toClearEntry(entry: StorageEntry, clearDir: String, dirId: ByteArray): StorageEntry? {
        if (entry.name == VaultFormat.DIR_ID_BACKUP) return null
        // Shortened names (.c9s) and anything else Cryptomator did not write
        // (stray desktop artifacts) are ignored, V1 scope.
        if (!entry.name.endsWith(VaultFormat.C9R_SUFFIX)) return null
        val clearName = try {
            cryptor.fileNameCryptor().decryptFilename(
                BaseEncoding.base64Url(),
                entry.name.removeSuffix(VaultFormat.C9R_SUFFIX),
                dirId,
            )
        } catch (_: AuthenticationFailedException) {
            return null // foreign or corrupted node: never fail the whole listing
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Same policy as the disk itself: dot-entries (.DS_Store, ._AppleDouble)
        // stay invisible.
        if (clearName.startsWith(".")) return null
        val display = if (clearDir == "/") "/$clearName" else "$clearDir/$clearName"
        return StorageEntry(
            pathB64 = PathCodec.encode(display),
            displayPath = display,
            name = clearName,
            isDirectory = entry.isDirectory,
            sizeBytes = if (entry.isDirectory) 0 else cleartextSizeOf(entry.sizeBytes),
            modifiedEpochSeconds = entry.modifiedEpochSeconds,
            mimeType = if (entry.isDirectory) null else mimeTypeFor(clearName),
            hidden = false,
        )
    }

    private fun cleartextSizeOf(ciphertextSize: Long): Long = try {
        cryptor.fileContentCryptor()
            .cleartextSize(ciphertextSize - cryptor.fileHeaderCryptor().headerSize())
    } catch (_: IllegalArgumentException) {
        0L // truncated/foreign file: the listing still shows it, download will fail cleanly
    }

    private fun decrypt(ciphertext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(ciphertext.size)
        DecryptingReadableByteChannel(
            Channels.newChannel(ciphertext.inputStream()),
            cryptor,
            true,
        ).use { channel ->
            val buffer = ByteBuffer.allocate(DECRYPT_BUFFER_BYTES)
            while (channel.read(buffer) != -1) {
                buffer.flip()
                out.write(buffer.array(), 0, buffer.limit())
                buffer.clear()
            }
        }
        return out.toByteArray()
    }

    private fun slice(bytes: ByteArray, range: LongRange): ByteArray {
        val from = range.first.coerceIn(0, bytes.size.toLong()).toInt()
        val to = (range.last + 1).coerceIn(from.toLong(), bytes.size.toLong()).toInt()
        return bytes.copyOfRange(from, to)
    }

    private fun clearPath(pathB64: String?): String =
        pathB64?.let(PathCodec::decode)?.trimEnd('/')?.ifEmpty { "/" } ?: "/"

    private fun notFoundOr(err: FbxResult.Err): FbxResult.Err = when (err.error) {
        is FreeboxError.Api -> FbxResult.Err(FreeboxError.Api(StorageProvider.ERROR_NOT_FOUND))
        else -> err // transport problems must surface as such, not as a fake 404
    }

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        else -> "application/octet-stream"
    }

    companion object {
        /** Content decryption failed authentication: tampered or foreign data. */
        const val ERROR_VAULT_INTEGRITY = "vault_integrity"

        private const val DECRYPT_BUFFER_BYTES = 512 * 1024
    }
}
