package com.boxpix.app.data.media

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.db.MediaDao
import com.boxpix.app.data.db.WorkQueueDao
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.net.NetworkStatus
import com.boxpix.app.data.prefs.XmpPolicy
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.data.tags.TagRepository
import com.boxpix.app.data.trash.TrashRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drains the XMP write-through queue, strictly per the spike contract
 * (docs/spike-xmp.md): JPEG only; safe replace = upload under a temp name,
 * remove the original, rename the temp over it (never truncate a master file
 * mid-upload); then, in the same breath, re-read the file's new mtime and
 * update the index + THUMB queue row so the unchanged pixels never trigger a
 * thumbnail regeneration loop. Real box: unmetered network only.
 */
@Singleton
class XmpQueueProcessor @Inject constructor(
    private val provider: StorageProvider,
    private val queueDao: WorkQueueDao,
    private val mediaDao: MediaDao,
    private val tags: TagRepository,
    private val writer: XmpTagWriter,
    private val env: StorageEnv,
    private val network: NetworkStatus,
    private val xmpPolicy: XmpPolicy,
) {

    private val mutex = Mutex()

    suspend fun process(limit: Int) {
        if (!mutex.tryLock()) return
        try {
            if (!xmpPolicy.enabled()) return // owner's switch (covers pre-existing jobs too)
            val useFake = env.useFakeProvider.first()
            if (!useFake && !network.isUnmetered()) return // wifi only on the real box
            val providerId =
                if (useFake) TrashRepository.PROVIDER_FAKE else TrashRepository.PROVIDER_FREEBOX

            queueDao.pending(providerId, WorkQueueEntity.TYPE_XMP, limit).forEach { job ->
                val outcome = runCatching { processJob(providerId, job) }
                    .getOrElse { "exception: ${it.message}" }
                queueDao.upsert(
                    if (outcome == null) {
                        job.copy(status = WorkQueueEntity.STATUS_DONE, lastError = null)
                    } else {
                        val attempts = job.attempts + 1
                        job.copy(
                            attempts = attempts,
                            status = if (attempts >= WorkQueueEntity.MAX_ATTEMPTS) {
                                WorkQueueEntity.STATUS_FAILED
                            } else {
                                WorkQueueEntity.STATUS_PENDING
                            },
                            lastError = outcome,
                        )
                    },
                )
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Null on success, an error label otherwise. */
    private suspend fun processJob(providerId: String, job: WorkQueueEntity): String? {
        val row = mediaDao.byPath(providerId, job.pathB64)
        val metadata = XmpMetadata(
            keywords = tags.keywordsForMedia(providerId, job.pathB64),
            takenAtEpochSeconds = row?.takeIf { it.takenAtManual }?.takenAtEpochSeconds,
            location = row?.locationText,
        )
        if (metadata.isEmpty) return null // nothing to write

        val original = provider.download(job.pathB64).getOrNull()
            ?: return "download_failed"
        val rewritten = writer.withMetadata(original, metadata)
            ?: return "rewrite_failed"
        if (rewritten.contentEquals(original)) return null // keywords already embedded

        val parentDisplay = job.displayPath.trimEnd('/').substringBeforeLast('/', "")
            .ifEmpty { "/" }
        val name = job.displayPath.trimEnd('/').substringAfterLast('/')
        val tmpName = "$name$TMP_SUFFIX"
        val parentB64 = PathCodec.encode(parentDisplay)

        val uploaded = provider.upload(parentB64, tmpName, rewritten)
        if (uploaded is FbxResult.Err) return "upload_failed: ${uploaded.error}"

        val removed = provider.delete(listOf(job.pathB64))
        if (removed is FbxResult.Err) {
            provider.delete(listOf(PathCodec.encode("$parentDisplay/$tmpName"))) // best-effort cleanup
            return "replace_failed: ${removed.error}"
        }

        val renamed = provider.rename(PathCodec.encode("$parentDisplay/$tmpName"), name)
        if (renamed is FbxResult.Err) return "rename_failed: ${renamed.error}"

        // "Modified by us": adopt the new disk mtime so the reconciler never
        // sees a stale thumb (pixels are proven identical by the spike).
        val newMtime = provider.list(parentB64).getOrNull()
            ?.firstOrNull { it.name == name }?.modifiedEpochSeconds
        if (newMtime != null) {
            mediaDao.setMtime(providerId, job.pathB64, newMtime)
            queueDao.upsert(
                WorkQueueEntity(
                    providerId = providerId,
                    type = WorkQueueEntity.TYPE_THUMB,
                    pathB64 = job.pathB64,
                    displayPath = job.displayPath,
                    enqueuedMtime = newMtime,
                    status = WorkQueueEntity.STATUS_DONE,
                    attempts = 0,
                    lastError = null,
                ),
            )
        }
        return null
    }

    private companion object {
        const val TMP_SUFFIX = ".boxpix-tmp"
    }
}
