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
 * Vault mutations are bounded by V1 scope: uploads cap at 200 MB (whole-file
 * RAM encryption, no streaming) and moves across the vault boundary refuse —
 * both with typed errors the UI turns into clear messages.
 */
class VaultRoutingProvider(
    private val disk: StorageProvider,
    private val session: VaultSession,
    /** Fired after any successful vault mutation, so the in-vault index catches up. */
    private val onVaultMutated: () -> Unit = {},
) : StorageProvider {

    override val capabilities: StorageCapabilities get() = disk.capabilities

    private sealed interface Route {
        data object Disk : Route
        data class Vault(val provider: CryptomatorProvider, val mount: String, val relativeB64: String) : Route
        data class Refused(val error: FreeboxError) : Route
    }

    override suspend fun list(
        pathB64: String?,
        onlyFolders: Boolean,
        includeHidden: Boolean,
    ): FbxResult<List<StorageEntry>> =
        when (val route = route(pathB64)) {
            Route.Disk -> disk.list(pathB64, onlyFolders, includeHidden)
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
        when (val route = route(parentB64)) {
            Route.Disk -> disk.mkdir(parentB64, name)
            is Route.Refused -> FbxResult.Err(route.error)
            is Route.Vault -> route.provider.mkdir(route.relativeB64, name)
                .map { it.mounted(route.mount) }
                .alsoNotifyOnSuccess()
        }

    override suspend fun rename(pathB64: String, newName: String): FbxResult<StorageEntry> =
        when (val route = route(pathB64)) {
            Route.Disk -> disk.rename(pathB64, newName)
            is Route.Refused -> FbxResult.Err(route.error)
            is Route.Vault -> route.provider.rename(route.relativeB64, newName)
                .map { it.mounted(route.mount) }
                .alsoNotifyOnSuccess()
        }

    override suspend fun move(pathsB64: List<String>, destParentB64: String): FbxResult<Unit> {
        val routes = (pathsB64 + destParentB64).map { route(it) }
        routes.filterIsInstance<Route.Refused>().firstOrNull()?.let { return FbxResult.Err(it.error) }
        val vaultRoutes = routes.filterIsInstance<Route.Vault>()
        return when {
            vaultRoutes.isEmpty() -> disk.move(pathsB64, destParentB64)
            // Moving across the vault boundary means silently decrypting or
            // encrypting whole files — explicitly out of V1 scope.
            vaultRoutes.size != routes.size ->
                FbxResult.Err(FreeboxError.Api(ERROR_VAULT_CROSS_BOUNDARY))
            else -> {
                val dest = vaultRoutes.last()
                dest.provider.move(vaultRoutes.dropLast(1).map { it.relativeB64 }, dest.relativeB64)
                    .alsoNotifyOnSuccess()
            }
        }
    }

    override suspend fun delete(pathsB64: List<String>): FbxResult<Unit> {
        val routes = pathsB64.map { route(it) }
        routes.filterIsInstance<Route.Refused>().firstOrNull()?.let { return FbxResult.Err(it.error) }
        val vaultRoutes = routes.filterIsInstance<Route.Vault>()
        return when {
            vaultRoutes.isEmpty() -> disk.delete(pathsB64)
            vaultRoutes.size != routes.size ->
                FbxResult.Err(FreeboxError.Api(ERROR_VAULT_CROSS_BOUNDARY))
            else -> vaultRoutes.first().provider.delete(vaultRoutes.map { it.relativeB64 })
                .alsoNotifyOnSuccess()
        }
    }

    override suspend fun upload(parentB64: String, name: String, bytes: ByteArray): FbxResult<Unit> =
        when (val route = route(parentB64)) {
            Route.Disk -> disk.upload(parentB64, name, bytes)
            is Route.Refused -> FbxResult.Err(route.error)
            is Route.Vault ->
                if (bytes.size > MAX_VAULT_UPLOAD_BYTES) {
                    // No upload streaming in V1: whole-file RAM encryption only.
                    FbxResult.Err(FreeboxError.Api(ERROR_VAULT_UPLOAD_TOO_LARGE))
                } else {
                    route.provider.upload(route.relativeB64, name, bytes).alsoNotifyOnSuccess()
                }
        }

    private fun <T> FbxResult<T>.alsoNotifyOnSuccess(): FbxResult<T> {
        if (this is FbxResult.Ok) onVaultMutated()
        return this
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

    companion object {
        const val ERROR_VAULT_LOCKED = "vault_locked"
        const val ERROR_VAULT_CROSS_BOUNDARY = "vault_cross_boundary"
        const val ERROR_VAULT_UPLOAD_TOO_LARGE = "vault_upload_too_large"
        const val MAX_VAULT_UPLOAD_BYTES = 200 * 1024 * 1024
    }
}
