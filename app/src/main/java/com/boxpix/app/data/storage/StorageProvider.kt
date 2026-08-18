package com.boxpix.app.data.storage

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError

/**
 * The only storage abstraction the rest of the app is allowed to talk to.
 * V1 has a single real implementation (FreeboxProvider) plus a debug fake.
 *
 * The M2 file operations have "unsupported" defaults: FreeboxProvider stays
 * frozen until the M1 gate (validation against the real box) and will wire
 * fs/mkdir, fs/rename, fs/mv and fs/rm afterwards.
 */
interface StorageProvider {
    val capabilities: StorageCapabilities

    /** Lists a folder; a null path lists the filesystem root, which exposes the disks. */
    suspend fun list(pathB64: String? = null, onlyFolders: Boolean = false): FbxResult<List<StorageEntry>>

    /** Full or partial (when [range] is set) read of a file's bytes. */
    suspend fun download(pathB64: String, range: LongRange? = null): FbxResult<ByteArray>

    suspend fun mkdir(parentB64: String, name: String): FbxResult<StorageEntry> = unsupported()

    suspend fun rename(pathB64: String, newName: String): FbxResult<StorageEntry> = unsupported()

    /** Moves entries into [destParentB64], keeping their names. */
    suspend fun move(pathsB64: List<String>, destParentB64: String): FbxResult<Unit> = unsupported()

    /** Permanent deletion — only the trash layer is allowed to call this. */
    suspend fun delete(pathsB64: List<String>): FbxResult<Unit> = unsupported()

    companion object {
        const val ERROR_NOT_SUPPORTED = "not_supported"

        /** Destination already holds an entry with that name. */
        const val ERROR_CONFLICT = "destination_conflict"

        /** Conflict spellings observed across firmware generations. */
        val CONFLICT_CODES = setOf(ERROR_CONFLICT, "exists", "already_exists", "file_exists")

        const val ERROR_NOT_FOUND = "path_not_found"

        private fun <T> unsupported(): FbxResult<T> =
            FbxResult.Err(FreeboxError.Api(ERROR_NOT_SUPPORTED))
    }
}

data class StorageCapabilities(
    val supportsRangeRequests: Boolean,
    /**
     * Whether entries can be created directly at the tree's root. False on the
     * Freebox: its root is virtual (it lists the disks), so app folders like
     * .trash must live inside the first path segment (the disk).
     */
    val canCreateAtRoot: Boolean,
)

/** Provider-agnostic entry; paths keep the provider's opaque encoded form. */
data class StorageEntry(
    val pathB64: String,
    val displayPath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedEpochSeconds: Long,
    val mimeType: String?,
    val hidden: Boolean,
    /** Known for fake/indexed videos only; null until M3 metadata lands. */
    val durationSeconds: Long? = null,
)
