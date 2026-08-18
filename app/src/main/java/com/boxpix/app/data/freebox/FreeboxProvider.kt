package com.boxpix.app.data.freebox

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.freebox.api.FileInfoDto
import com.boxpix.app.data.freebox.api.FreeboxApiClient
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.freebox.auth.FreeboxSessionManager
import com.boxpix.app.data.storage.StorageCapabilities
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.storage.StorageProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FreeboxProvider @Inject constructor(
    private val api: FreeboxApiClient,
    private val sessions: FreeboxSessionManager,
) : StorageProvider {

    override val capabilities = StorageCapabilities(supportsRangeRequests = true)

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
