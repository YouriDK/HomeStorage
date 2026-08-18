package com.boxpix.app.data.storage

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.api.PathCodec
import javax.inject.Inject
import javax.inject.Singleton

/** mkdir -p semantics over a StorageProvider, shared by trash and thumbs. */
@Singleton
class StorageFolders @Inject constructor(
    private val provider: StorageProvider,
) {

    /**
     * Paths ensured this session — thumbnail generation would otherwise re-walk
     * the same mkdir chain for every file of a folder. A stale entry only costs
     * one failed upload, which the next reconciler pass repairs.
     */
    private val ensured = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Creates every missing segment of [displayPath]. When the provider's root
     * is not writable, the first segment is the disk itself: it always exists
     * and cannot be created, so it seeds the walk instead of being mkdir'ed.
     */
    suspend fun ensure(displayPath: String): FreeboxError? {
        if (displayPath in ensured) return null
        val segments = displayPath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        val rooted = displayPath.startsWith("/")
        var parent: String
        var startIndex: Int
        if (provider.capabilities.canCreateAtRoot) {
            parent = "/"
            startIndex = 0
        } else {
            parent = (if (rooted) "/" else "") + segments.first()
            startIndex = 1
        }

        for (i in startIndex until segments.size) {
            val segment = segments[i]
            val made = provider.mkdir(PathCodec.encode(parent), segment)
            if (made is FbxResult.Err && !made.error.isConflict()) return made.error
            parent = if (parent == "/") "/$segment" else "$parent/$segment"
        }
        ensured += displayPath
        return null
    }

    private fun FreeboxError.isConflict() =
        this is FreeboxError.Api && code in StorageProvider.CONFLICT_CODES
}
