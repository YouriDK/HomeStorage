package com.boxpix.app.data.vault

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.StorageCapabilities
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.storage.StorageProvider

/**
 * The app-wide StorageProvider: everything outside the vault goes straight to
 * the disk provider; paths under `<disk root>/.vault/` are translated to
 * vault-relative cleartext paths and served by the unlocked CryptomatorProvider.
 * While the vault is locked, vault paths answer [ERROR_VAULT_LOCKED] — a lock
 * instantly makes every vault byte unreachable, whatever screen asks.
 *
 * Existing consumers (Reconciler, trash, thumbnails, search) can never receive
 * a vault path from a normal listing (dot-entries are stripped at the source),
 * so mounting the vault here adds no leak path — the guards on Room and the
 * clear mirrors stay where the writes happen.
 *
 * Vault mutations arrive with the M8 write lot; until then they refuse cleanly.
 */
class VaultRoutingProvider(
    private val disk: StorageProvider,
    private val session: VaultSession,
) : StorageProvider {

    override val capabilities: StorageCapabilities get() = disk.capabilities

    private sealed interface Route {
        data object Disk : Route
        data class Vault(val provider: CryptomatorProvider, val mount: String, val relativeB64: String) : Route
        data class Refused(val error: FreeboxError) : Route
    }

    override suspend fun list(pathB64: String?, onlyFolders: Boolean): FbxResult<List<StorageEntry>> =
        when (val route = route(pathB64)) {
            Route.Disk -> disk.list(pathB64, onlyFolders)
            is Route.Refused -> FbxResult.Err(route.error)
            is Route.Vault -> route.provider.list(route.relativeB64, onlyFolders)
                .map { entries -> entries.map { it.mounted(route.mount) } }
        }

    override suspend fun download(pathB64: String, range: LongRange?): FbxResult<ByteArray> =
        when (val route = route(pathB64)) {
            Route.Disk -> disk.download(pathB64, range)
            is Route.Refused -> FbxResult.Err(route.error)
            is Route.Vault -> route.provider.download(route.relativeB64, range)
        }

    override suspend fun mkdir(parentB64: String, name: String): FbxResult<StorageEntry> =
        when (route(parentB64)) {
            Route.Disk -> disk.mkdir(parentB64, name)
            else -> unsupportedInVault()
        }

    override suspend fun rename(pathB64: String, newName: String): FbxResult<StorageEntry> =
        when (route(pathB64)) {
            Route.Disk -> disk.rename(pathB64, newName)
            else -> unsupportedInVault()
        }

    override suspend fun move(pathsB64: List<String>, destParentB64: String): FbxResult<Unit> =
        if ((pathsB64 + destParentB64).all { route(it) == Route.Disk }) {
            disk.move(pathsB64, destParentB64)
        } else {
            unsupportedInVault()
        }

    override suspend fun delete(pathsB64: List<String>): FbxResult<Unit> =
        if (pathsB64.all { route(it) == Route.Disk }) disk.delete(pathsB64) else unsupportedInVault()

    override suspend fun upload(parentB64: String, name: String, bytes: ByteArray): FbxResult<Unit> =
        when (route(parentB64)) {
            Route.Disk -> disk.upload(parentB64, name, bytes)
            else -> unsupportedInVault()
        }

    private fun route(pathB64: String?): Route {
        val display = pathB64?.let { runCatching { PathCodec.decode(it) }.getOrNull() }
        if (display == null || !VaultPaths.isVaultPath(display)) return Route.Disk
        val mount = session.mountDisplayPath
            ?: return Route.Refused(FreeboxError.Api(ERROR_VAULT_LOCKED))
        val relative = VaultPaths.vaultRelative(display, mount)
            ?: return Route.Refused(FreeboxError.Api(StorageProvider.ERROR_NOT_FOUND))
        val vault = session.provider
            ?: return Route.Refused(FreeboxError.Api(ERROR_VAULT_LOCKED))
        return Route.Vault(vault, mount, PathCodec.encode(relative))
    }

    private fun StorageEntry.mounted(mount: String): StorageEntry {
        val display = if (displayPath == "/") mount else "$mount$displayPath"
        return copy(pathB64 = PathCodec.encode(display), displayPath = display)
    }

    private fun <T> unsupportedInVault(): FbxResult<T> =
        FbxResult.Err(FreeboxError.Api(StorageProvider.ERROR_NOT_SUPPORTED))

    companion object {
        const val ERROR_VAULT_LOCKED = "vault_locked"
    }
}
