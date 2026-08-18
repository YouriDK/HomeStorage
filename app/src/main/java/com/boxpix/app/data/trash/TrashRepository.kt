package com.boxpix.app.data.trash

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.db.TrashDao
import com.boxpix.app.data.db.TrashItemEntity
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.MirrorPaths
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageFolders
import com.boxpix.app.data.storage.StorageProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SPEC §2/§5 trash contract: deleting from the UI is always a move to
 * .trash/<mirror-of-original-path>, never an actual rm. Restore moves back
 * (recreating the original folder if needed); only purge deletes for real.
 *
 * The trash directory lives at the root of the tree the path belongs to:
 * "/Photos/x.jpg" → "/.trash/Photos/x.jpg" (rooted, fake-style), and
 * "Disque 1/Photos/x.jpg" → "Disque 1/.trash/Photos/x.jpg" (real box paths
 * are relative to a virtual root whose first segment is the disk — nothing
 * can be created at that root itself).
 *
 * Records are scoped per provider so fake leftovers never surface in the real
 * trash (and purge never touches the other provider's files).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TrashRepository @Inject constructor(
    private val provider: StorageProvider,
    private val dao: TrashDao,
    private val clock: Clock,
    private val env: StorageEnv,
    private val folders: StorageFolders,
) {

    val items: Flow<List<TrashItemEntity>> =
        env.useFakeProvider.flatMapLatest { dao.items(providerId(it)) }

    val count: Flow<Int> =
        env.useFakeProvider.flatMapLatest { dao.count(providerId(it)) }

    suspend fun trash(entries: List<StorageEntry>): FbxResult<Unit> {
        val providerId = currentProviderId()
        for (entry in entries) {
            val originalParent = parentOf(entry.displayPath)
            val trashDir = MirrorPaths.mirrorDirFor(
                originalParent,
                MirrorPaths.TRASH_DIR,
                provider.capabilities.canCreateAtRoot,
            )

            folders.ensure(trashDir)?.let { return FbxResult.Err(it) }

            val moved = moveAvoidingConflict(entry.pathB64, entry.name, trashDir)
            val finalName = when (moved) {
                is FbxResult.Ok -> moved.value
                is FbxResult.Err -> return moved
            }

            dao.insert(
                TrashItemEntity(
                    trashPathB64 = PathCodec.encode("$trashDir/$finalName"),
                    providerId = providerId,
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
        folders.ensure(item.originalParentPath)?.let { return FbxResult.Err(it) }

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
        dao.all(currentProviderId()).forEach { purge(it) }
    }

    suspend fun purgeOlderThan(days: Long = AUTO_PURGE_DAYS) {
        val cutoff = clock.instant().epochSecond - days * 86_400
        dao.olderThan(cutoff, currentProviderId()).forEach { purge(it) }
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
                    if (!moved.error.isConflict() || attempt >= MAX_CONFLICT_ATTEMPTS) return moved
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

    private suspend fun currentProviderId(): String = providerId(env.useFakeProvider.first())

    private fun providerId(useFake: Boolean): String =
        if (useFake) PROVIDER_FAKE else PROVIDER_FREEBOX

    private fun FreeboxError.isConflict() =
        this is FreeboxError.Api && code in StorageProvider.CONFLICT_CODES

    private fun FreeboxError.isMissingPath() =
        this is FreeboxError.Api && code == StorageProvider.ERROR_NOT_FOUND

    private fun parentOf(displayPath: String): String {
        val trimmed = displayPath.trimEnd('/')
        val cut = trimmed.substringBeforeLast('/', "")
        return if (cut.isEmpty()) {
            if (displayPath.startsWith("/")) "/" else ""
        } else {
            cut.ifEmpty { "/" }
        }
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
        const val AUTO_PURGE_DAYS = 30L
        const val PROVIDER_FAKE = "fake"
        const val PROVIDER_FREEBOX = "freebox"
        private const val MAX_CONFLICT_ATTEMPTS = 10
    }
}
