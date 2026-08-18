package com.boxpix.app.data.trash

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.db.TrashDao
import com.boxpix.app.data.db.TrashItemEntity
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.storage.StorageProvider
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SPEC §2/§5 trash contract: deleting from the UI is always a move to
 * /.trash/<mirror-of-original-path>, never an actual rm. Restore moves back
 * (recreating the original folder if needed); only purge deletes for real.
 */
@Singleton
class TrashRepository @Inject constructor(
    private val provider: StorageProvider,
    private val dao: TrashDao,
    private val clock: Clock,
) {

    val items: Flow<List<TrashItemEntity>> = dao.items()
    val count: Flow<Int> = dao.count()

    suspend fun trash(entries: List<StorageEntry>): FbxResult<Unit> {
        for (entry in entries) {
            val originalParent = parentOf(entry.displayPath)
            val trashDir = TRASH_ROOT + originalParent.removeSuffix("/")

            ensureFolder(trashDir)?.let { return FbxResult.Err(it) }

            val moved = moveAvoidingConflict(entry.pathB64, entry.name, trashDir)
            val finalName = when (moved) {
                is FbxResult.Ok -> moved.value
                is FbxResult.Err -> return moved
            }

            dao.insert(
                TrashItemEntity(
                    trashPathB64 = PathCodec.encode("$trashDir/$finalName"),
                    originalParentPath = originalParent,
                    name = finalName,
                    isDirectory = entry.isDirectory,
                    sizeBytes = entry.sizeBytes,
                    trashedAtEpochSeconds = clock.instant().epochSecond,
                ),
            )
        }
        return FbxResult.Ok(Unit)
    }

    suspend fun restore(item: TrashItemEntity): FbxResult<Unit> {
        ensureFolder(item.originalParentPath)?.let { return FbxResult.Err(it) }

        val moved = moveAvoidingConflict(item.trashPathB64, item.name, item.originalParentPath)
        if (moved is FbxResult.Err) return moved

        dao.delete(item.trashPathB64)
        return FbxResult.Ok(Unit)
    }

    /** Permanent deletion of one trashed item. */
    suspend fun purge(item: TrashItemEntity): FbxResult<Unit> {
        val deleted = provider.delete(listOf(item.trashPathB64))
        // A file already gone from the disk is a success for coherence purposes.
        if (deleted is FbxResult.Err && !deleted.error.isMissingPath()) return deleted
        dao.delete(item.trashPathB64)
        return FbxResult.Ok(Unit)
    }

    suspend fun purgeAll() {
        dao.all().forEach { purge(it) }
    }

    suspend fun purgeOlderThan(days: Long = AUTO_PURGE_DAYS) {
        val cutoff = clock.instant().epochSecond - days * 86_400
        dao.olderThan(cutoff).forEach { purge(it) }
    }

    /**
     * Moves an entry into [destDir], renaming it first ("name (2).ext") when the
     * destination already holds that name. Returns the final name.
     */
    private suspend fun moveAvoidingConflict(
        pathB64: String,
        name: String,
        destDir: String,
    ): FbxResult<String> {
        var currentB64 = pathB64
        var currentName = name
        var attempt = 1
        while (true) {
            val destB64 = PathCodec.encode(destDir.ifEmpty { "/" })
            when (val moved = provider.move(listOf(currentB64), destB64)) {
                is FbxResult.Ok -> return FbxResult.Ok(currentName)
                is FbxResult.Err -> {
                    val conflict = (moved.error as? FreeboxError.Api)
                        ?.code == StorageProvider.ERROR_CONFLICT
                    if (!conflict || attempt >= MAX_CONFLICT_ATTEMPTS) return moved
                    attempt++
                    val candidate = suffixedName(name, attempt)
                    when (val renamed = provider.rename(currentB64, candidate)) {
                        is FbxResult.Ok -> {
                            currentB64 = renamed.value.pathB64
                            currentName = renamed.value.name
                        }
                        is FbxResult.Err -> return renamed
                    }
                }
            }
        }
    }

    private suspend fun ensureFolder(displayPath: String): FreeboxError? {
        var parent = "/"
        for (segment in displayPath.split('/').filter { it.isNotEmpty() }) {
            val made = provider.mkdir(PathCodec.encode(parent), segment)
            if (made is FbxResult.Err && !made.error.isConflict()) return made.error
            parent = if (parent == "/") "/$segment" else "$parent/$segment"
        }
        return null
    }

    private fun FreeboxError.isConflict() =
        this is FreeboxError.Api && code == StorageProvider.ERROR_CONFLICT

    private fun FreeboxError.isMissingPath() =
        this is FreeboxError.Api && code == StorageProvider.ERROR_NOT_FOUND

    private fun parentOf(displayPath: String): String {
        val cut = displayPath.trimEnd('/').substringBeforeLast('/', "")
        return cut.ifEmpty { "/" }
    }

    private fun suffixedName(name: String, attempt: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            "${name.substring(0, dot)} ($attempt)${name.substring(dot)}"
        } else {
            "$name ($attempt)"
        }
    }

    companion object {
        const val TRASH_ROOT = "/.trash"
        const val AUTO_PURGE_DAYS = 30L
        private const val MAX_CONFLICT_ATTEMPTS = 10
    }
}
