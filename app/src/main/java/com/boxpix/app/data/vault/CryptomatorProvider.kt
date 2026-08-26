package com.boxpix.app.data.vault

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.media.MediaTypes
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
import org.cryptomator.cryptolib.common.EncryptingWritableByteChannel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.UUID

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

    /**
     * Creates a cleartext folder: encrypted node dir + its `dir.c9r` pointer,
     * the hashed physical dir, and the `dirid.c9r` backup Cryptomator desktop
     * also writes. New entry — nothing cached becomes stale.
     */
    override suspend fun mkdir(parentB64: String, name: String): FbxResult<StorageEntry> {
        val clearParent = clearPath(parentB64)
        val parent = when (val r = resolveDir(clearParent)) {
            is FbxResult.Ok -> r.value
            is FbxResult.Err -> return r
        }
        val encrypted = encryptName(name, parent.dirId)
        val nodeDir = when (val made = inner.mkdir(PathCodec.encode(parent.physicalDisplay), encrypted)) {
            is FbxResult.Ok -> "${parent.physicalDisplay}/$encrypted"
            is FbxResult.Err -> return made
        }
        val dirId = UUID.randomUUID().toString()
        inner.upload(PathCodec.encode(nodeDir), VaultFormat.DIR_ID_FILE, dirId.toByteArray(Charsets.UTF_8))
            .let { if (it is FbxResult.Err) return it }

        val physical = physicalDirOf(dirId)
        val dataParent = physical.substringBeforeLast('/')
        inner.mkdir(PathCodec.encode(dataParent.substringBeforeLast('/')), dataParent.substringAfterLast('/'))
        when (val made = inner.mkdir(PathCodec.encode(dataParent), physical.substringAfterLast('/'))) {
            is FbxResult.Ok -> Unit
            is FbxResult.Err -> return made
        }
        withContext(cryptoDispatcher) {
            inner.upload(PathCodec.encode(physical), VaultFormat.DIR_ID_BACKUP, encryptBytes(dirId.toByteArray(Charsets.UTF_8)))
        }

        val display = if (clearParent == "/") "/$name" else "$clearParent/$name"
        return FbxResult.Ok(
            StorageEntry(
                pathB64 = PathCodec.encode(display),
                displayPath = display,
                name = name,
                isDirectory = true,
                sizeBytes = 0,
                modifiedEpochSeconds = System.currentTimeMillis() / 1000,
                mimeType = null,
                hidden = false,
            ),
        )
    }

    /** Encrypts [bytes] fully in RAM, then hands the ciphertext to the disk. */
    override suspend fun upload(parentB64: String, name: String, bytes: ByteArray): FbxResult<Unit> {
        val clearParent = clearPath(parentB64)
        val parent = when (val r = resolveDir(clearParent)) {
            is FbxResult.Ok -> r.value
            is FbxResult.Err -> return r
        }
        val encrypted = encryptName(name, parent.dirId)
        val ciphertext = withContext(cryptoDispatcher) { encryptBytes(bytes) }
        return inner.upload(PathCodec.encode(parent.physicalDisplay), encrypted, ciphertext)
    }

    /**
     * Renames in place. Works for files and folder nodes alike: a folder's
     * physical tree is keyed by its dirId, which a rename never changes —
     * only the encrypted node name (whose associated data is the PARENT's
     * dirId, unchanged too) is replaced.
     */
    override suspend fun rename(pathB64: String, newName: String): FbxResult<StorageEntry> {
        val clear = clearPath(pathB64)
        val parentPath = clear.substringBeforeLast('/').ifEmpty { "/" }
        val name = clear.substringAfterLast('/')
        val parent = when (val r = resolveDir(parentPath)) {
            is FbxResult.Ok -> r.value
            is FbxResult.Err -> return r
        }
        val oldEnc = encryptName(name, parent.dirId)
        val newEnc = encryptName(newName, parent.dirId)
        val renamed = inner.rename(PathCodec.encode("${parent.physicalDisplay}/$oldEnc"), newEnc)
        if (renamed is FbxResult.Err) return renamed

        // A renamed folder shifts the clear paths of its whole subtree.
        cacheMutex.withLock {
            dirCache.keys.removeAll { it == clear || it.startsWith("$clear/") }
        }
        val display = if (parentPath == "/") "/$newName" else "$parentPath/$newName"
        // Freebox's rename result is rebuilt with isDirectory=false (v16
        // lesson); good enough — every rename caller reloads its listing.
        val wasDirectory = (renamed as FbxResult.Ok).value.isDirectory
        return FbxResult.Ok(
            StorageEntry(
                pathB64 = PathCodec.encode(display),
                displayPath = display,
                name = newName,
                isDirectory = wasDirectory,
                sizeBytes = renamed.value.sizeBytes,
                modifiedEpochSeconds = renamed.value.modifiedEpochSeconds,
                mimeType = if (wasDirectory) null else MediaTypes.mimeTypeFor(newName),
                hidden = false,
            ),
        )
    }

    /**
     * Moves entries to [destParentB64], both inside the vault. The encrypted
     * name is bound to the parent's dirId (SIV associated data), so a move is
     * a rename to the name encrypted for the DESTINATION, then a physical
     * move. Conflicts map 1:1: the same clear name in the destination yields
     * the same encrypted name, which the inner provider already refuses.
     */
    override suspend fun move(pathsB64: List<String>, destParentB64: String): FbxResult<Unit> {
        val destClear = clearPath(destParentB64)
        val dest = when (val r = resolveDir(destClear)) {
            is FbxResult.Ok -> r.value
            is FbxResult.Err -> return r
        }
        for (pathB64 in pathsB64) {
            val clear = clearPath(pathB64)
            val parentPath = clear.substringBeforeLast('/').ifEmpty { "/" }
            val name = clear.substringAfterLast('/')
            val parent = when (val r = resolveDir(parentPath)) {
                is FbxResult.Ok -> r.value
                is FbxResult.Err -> return r
            }
            val sourceEnc = encryptName(name, parent.dirId)
            val destEnc = encryptName(name, dest.dirId)
            val sourcePhysical = "${parent.physicalDisplay}/$sourceEnc"
            val renamed = inner.rename(PathCodec.encode(sourcePhysical), destEnc)
            if (renamed is FbxResult.Err) return renamed
            val staged = "${parent.physicalDisplay}/$destEnc"
            val moved = inner.move(listOf(PathCodec.encode(staged)), PathCodec.encode(dest.physicalDisplay))
            if (moved is FbxResult.Err) {
                inner.rename(PathCodec.encode(staged), sourceEnc) // best-effort rollback
                return moved
            }
            cacheMutex.withLock {
                dirCache.keys.removeAll { it == clear || it.startsWith("$clear/") }
            }
        }
        return FbxResult.Ok(Unit)
    }

    /**
     * Permanent deletion. For folders, every descendant folder's physical dir
     * is collected by listing BEFORE the node is removed — nothing else knows
     * where the hashed dirs live.
     */
    override suspend fun delete(pathsB64: List<String>): FbxResult<Unit> {
        for (pathB64 in pathsB64) {
            val clear = clearPath(pathB64)
            val parentPath = clear.substringBeforeLast('/').ifEmpty { "/" }
            val name = clear.substringAfterLast('/')
            val parent = when (val r = resolveDir(parentPath)) {
                is FbxResult.Ok -> r.value
                is FbxResult.Err -> return r
            }
            val nodePhysical = "${parent.physicalDisplay}/${encryptName(name, parent.dirId)}"
            val physicalDirs = ArrayList<String>()
            if (resolveDir(clear) is FbxResult.Ok && isDirectoryNode(clear)) {
                collectPhysicalDirs(clear, physicalDirs)
            }
            val targets = (listOf(nodePhysical) + physicalDirs).map(PathCodec::encode)
            val deleted = inner.delete(targets)
            if (deleted is FbxResult.Err) return deleted
            cacheMutex.withLock {
                dirCache.keys.removeAll { it == clear || it.startsWith("$clear/") }
            }
        }
        return FbxResult.Ok(Unit)
    }

    private suspend fun isDirectoryNode(clear: String): Boolean {
        val parentPath = clear.substringBeforeLast('/').ifEmpty { "/" }
        return (list(PathCodec.encode(parentPath)) as? FbxResult.Ok)
            ?.value?.any { it.displayPath == clear && it.isDirectory } == true
    }

    private suspend fun collectPhysicalDirs(clearDir: String, into: MutableList<String>) {
        val resolved = (resolveDir(clearDir) as? FbxResult.Ok)?.value ?: return
        into += resolved.physicalDisplay
        val children = (list(PathCodec.encode(clearDir)) as? FbxResult.Ok)?.value.orEmpty()
        children.filter { it.isDirectory }.forEach { collectPhysicalDirs(it.displayPath, into) }
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
            mimeType = if (entry.isDirectory) null else MediaTypes.mimeTypeFor(clearName),
            hidden = false,
        )
    }

    private fun cleartextSizeOf(ciphertextSize: Long): Long = try {
        cryptor.fileContentCryptor()
            .cleartextSize(ciphertextSize - cryptor.fileHeaderCryptor().headerSize())
    } catch (_: IllegalArgumentException) {
        0L // truncated/foreign file: the listing still shows it, download will fail cleanly
    }

    private fun encryptBytes(cleartext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(cleartext.size + 1024)
        EncryptingWritableByteChannel(Channels.newChannel(out), cryptor).use {
            it.write(ByteBuffer.wrap(cleartext))
        }
        return out.toByteArray()
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

    companion object {
        /** Content decryption failed authentication: tampered or foreign data. */
        const val ERROR_VAULT_INTEGRITY = "vault_integrity"

        private const val DECRYPT_BUFFER_BYTES = 512 * 1024
    }
}
