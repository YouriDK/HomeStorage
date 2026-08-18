package com.boxpix.app.data.freebox

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.api.FileInfoDto
import com.boxpix.app.data.freebox.api.FreeboxApiClient
import com.boxpix.app.data.freebox.api.FsTaskDto
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.freebox.auth.FreeboxSessionManager
import com.boxpix.app.data.storage.StorageCapabilities
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.storage.StorageProvider
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FreeboxProvider @Inject constructor(
    private val api: FreeboxApiClient,
    private val sessions: FreeboxSessionManager,
) : StorageProvider {

    override val capabilities = StorageCapabilities(
        supportsRangeRequests = true,
        canCreateAtRoot = false, // "/" is virtual: it lists the disks (access_denied otherwise)
    )

    override suspend fun list(pathB64: String?, onlyFolders: Boolean): FbxResult<List<StorageEntry>> =
        sessions.withSession { base, token ->
            api.ls(
                base = base,
                sessionToken = token,
                pathB64 = pathB64 ?: PathCodec.ROOT,
                onlyFolder = onlyFolders,
                removeHidden = true,
            )
        }.map { entries -> entries.map(FileInfoDto::toStorageEntry) }

    override suspend fun download(pathB64: String, range: LongRange?): FbxResult<ByteArray> =
        sessions.withSession { base, token ->
            api.download(base, token, pathB64, range)
        }

    override suspend fun mkdir(parentB64: String, name: String): FbxResult<StorageEntry> =
        sessions.withSession { base, token ->
            api.mkdir(base, token, parentB64, name)
        }.map {
            // The entry is rebuilt locally: the API's mkdir result shape is not
            // relied upon (v16 lesson), and parent + name is all we need.
            val parentDisplay = runCatching { PathCodec.decode(parentB64) }.getOrDefault("")
            val display = joinPath(parentDisplay, name)
            StorageEntry(
                pathB64 = PathCodec.encode(display),
                displayPath = display,
                name = name,
                isDirectory = true,
                sizeBytes = 0,
                modifiedEpochSeconds = System.currentTimeMillis() / 1000,
                mimeType = null,
                hidden = name.startsWith("."),
            )
        }

    override suspend fun rename(pathB64: String, newName: String): FbxResult<StorageEntry> =
        sessions.withSession { base, token ->
            api.rename(base, token, pathB64, newName)
        }.map {
            val oldDisplay = runCatching { PathCodec.decode(pathB64) }.getOrDefault("")
            val parentDisplay = oldDisplay.substringBeforeLast('/', "")
            val display = joinPath(parentDisplay, newName)
            StorageEntry(
                pathB64 = PathCodec.encode(display),
                displayPath = display,
                name = newName,
                isDirectory = false,
                sizeBytes = 0,
                modifiedEpochSeconds = System.currentTimeMillis() / 1000,
                mimeType = null,
                hidden = newName.startsWith("."),
            )
        }

    override suspend fun move(pathsB64: List<String>, destParentB64: String): FbxResult<Unit> =
        sessions.withSession { base, token ->
            // fs/mv cannot report conflicts (its modes all resolve them silently),
            // but the StorageProvider contract promises a conflict error — so the
            // destination is checked first. mode=skip keeps races non-destructive.
            val destNames = when (val listed = api.ls(base, token, destParentB64)) {
                is FbxResult.Ok -> listed.value.map { it.name }.toSet()
                is FbxResult.Err -> return@withSession listed
            }
            val conflict = pathsB64.any { encoded ->
                val name = runCatching { PathCodec.decode(encoded) }.getOrDefault("").substringAfterLast('/')
                name.isNotEmpty() && name in destNames
            }
            if (conflict) {
                return@withSession FbxResult.Err(FreeboxError.Api(StorageProvider.ERROR_CONFLICT))
            }
            when (val started = api.mv(base, token, pathsB64, destParentB64)) {
                is FbxResult.Ok -> awaitTask(base, token, started.value)
                is FbxResult.Err -> started
            }
        }

    override suspend fun delete(pathsB64: List<String>): FbxResult<Unit> =
        sessions.withSession { base, token ->
            when (val started = api.rm(base, token, pathsB64)) {
                is FbxResult.Ok -> awaitTask(base, token, started.value)
                is FbxResult.Err -> started
            }
        }

    override suspend fun upload(parentB64: String, name: String, bytes: ByteArray): FbxResult<Unit> =
        sessions.withSession { base, token ->
            api.upload(base, token, parentB64, name, bytes)
        }

    /** fs/mv and fs/rm are asynchronous on the box: poll their task until it settles. */
    private suspend fun awaitTask(base: String, token: String, initial: FsTaskDto): FbxResult<Unit> {
        var task = initial
        repeat(TASK_POLL_ATTEMPTS) {
            if (task.isDone) return FbxResult.Ok(Unit)
            if (task.isFailed) return FbxResult.Err(FreeboxError.Api(task.errorCode ?: "task_failed"))
            delay(TASK_POLL_INTERVAL_MS)
            task = when (val polled = api.fsTask(base, token, initial.id)) {
                is FbxResult.Ok -> polled.value
                is FbxResult.Err -> return polled
            }
        }
        return FbxResult.Err(FreeboxError.Api("task_timeout"))
    }

    private fun joinPath(parent: String, name: String): String = when {
        parent.isEmpty() || parent == "/" -> {
            // Fake-style rooted trees keep the slash; the real box's paths are
            // relative to the virtual root ("Disque 1/…").
            if (parent == "/") "/$name" else name
        }
        else -> "${parent.trimEnd('/')}/$name"
    }

    private companion object {
        const val TASK_POLL_INTERVAL_MS = 300L
        const val TASK_POLL_ATTEMPTS = 200 // ~60 s worst case
    }
}

private fun FileInfoDto.toStorageEntry() = StorageEntry(
    pathB64 = path,
    displayPath = runCatching { PathCodec.decode(path) }.getOrDefault(name),
    name = name,
    isDirectory = isDirectory,
    sizeBytes = size,
    modifiedEpochSeconds = modification,
    mimeType = mimetype,
    hidden = hidden,
)
