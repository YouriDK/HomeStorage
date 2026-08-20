package com.boxpix.app.data.media

import android.util.Log
import com.boxpix.app.BuildConfig
import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.db.MediaDao
import com.boxpix.app.data.db.MediaItemEntity
import com.boxpix.app.data.db.WorkQueueDao
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.db.ExcludedFolderDao
import com.boxpix.app.data.db.SearchQueryBuilder
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.FolderListsSync
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.data.trash.TrashRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SPEC §3: no filesystem events on the box, so every pass compares the desired
 * state with the actual one and catches up the delta. Idempotent, resumable,
 * safe to interrupt: scan (listings → index diff + thumb jobs) then process
 * a bounded slice of the queue.
 */
@Singleton
class Reconciler @Inject constructor(
    private val provider: StorageProvider,
    private val mediaDao: MediaDao,
    private val queueDao: WorkQueueDao,
    private val thumbnails: ThumbnailRepository,
    private val env: StorageEnv,
    private val rootLocator: RootLocator,
    private val excludedDao: ExcludedFolderDao,
    private val folderLists: FolderListsSync,
    private val syncStatus: SyncStatus,
    private val clock: java.time.Clock,
    private val telemetry: WorkerTelemetry,
) {

    private val passMutex = Mutex()

    /**
     * One reconciliation pass. [maxFolders] bounds the scan breadth (light pass
     * on app open), [processLimit] bounds the thumbnail jobs handled inline.
     * Skips silently when a pass is already running or no root is configured.
     */
    suspend fun runPass(maxFolders: Int, processLimit: Int) {
        if (!passMutex.tryLock()) return
        try {
            val root = rootLocator.rootPathB64() ?: return
            val providerId = currentProviderId()
            // Another device may have changed the shared folder lists since our last pass.
            runCatching { folderLists.importIfNewer() }
            val scanned = scan(providerId, root, maxFolders)
            val processed = processQueue(providerId, processLimit)
            syncStatus.recordPass(clock.instant().epochSecond)
            log("pass done: $scanned folders scanned, $processed jobs processed")
        } finally {
            passMutex.unlock()
        }
    }

    private suspend fun scan(providerId: String, rootB64: String, maxFolders: Int): Int {
        val excludedPaths = excludedDao.snapshot(providerId).map { it.displayPath }
        var visited = 0
        val toVisit = ArrayDeque(listOf(rootB64))
        while (toVisit.isNotEmpty() && visited < maxFolders) {
            val folderB64 = toVisit.removeFirst()
            if (isExcluded(folderDisplayOf(folderB64), excludedPaths)) continue
            val entries = when (val listed = provider.list(folderB64)) {
                is FbxResult.Ok -> listed.value
                is FbxResult.Err -> continue // transient listing failure: next pass catches up
            }
            visited++

            entries.filter { it.isDirectory }.forEach { toVisit.addLast(it.pathB64) }
            val files = entries.filterNot { it.isDirectory }
            val folderDisplay = folderDisplayOf(folderB64)
            val known = mediaDao.folderItems(providerId, folderDisplay).associateBy { it.pathB64 }

            val rows = files.map { file ->
                val previous = known[file.pathB64]
                val unchanged = previous != null && previous.mtime == file.modifiedEpochSeconds
                MediaItemEntity(
                    providerId = providerId,
                    pathB64 = file.pathB64,
                    displayPath = file.displayPath,
                    name = file.name,
                    folderDisplayPath = folderDisplay,
                    sizeBytes = file.sizeBytes,
                    mtime = file.modifiedEpochSeconds,
                    takenAtEpochSeconds = if (unchanged) previous?.takenAtEpochSeconds else null,
                    takenAtManual = unchanged && previous?.takenAtManual == true,
                    locationText = if (unchanged) previous?.locationText else null,
                    mimeType = file.mimeType,
                    isVideo = file.mimeType?.startsWith("video/") == true,
                    durationSeconds = file.durationSeconds,
                    hasThumb = if (unchanged) previous?.hasThumb == true else false,
                )
            }
            if (rows.isNotEmpty()) mediaDao.upsert(rows)
            // Stale rows are resolved in memory, then deleted in bounded chunks:
            // a NOT IN over a large folder would blow SQLite's variable cap.
            val stale = known.keys - files.map { it.pathB64 }.toSet()
            stale.chunked(SearchQueryBuilder.IN_CHUNK_SIZE).forEach { chunk ->
                mediaDao.deleteByPaths(providerId, chunk)
            }

            files.forEach { file ->
                // Extension gate (V1 feedback): a pdf/zip/sidecar never creates a
                // job, whatever mime the box reports for it.
                val type = when {
                    MediaTypes.isPhoto(file.name) -> WorkQueueEntity.TYPE_THUMB
                    // Video thumbnails are the worker's job (SPEC M7), real box only:
                    // the fake's videos are not decodable containers.
                    MediaTypes.isVideo(file.name) &&
                        providerId == TrashRepository.PROVIDER_FREEBOX ->
                        WorkQueueEntity.TYPE_VIDEO_THUMB
                    else -> return@forEach
                }
                val existing = queueDao.find(providerId, type, file.pathB64)
                val fresh = known[file.pathB64]
                    ?.takeIf { it.mtime == file.modifiedEpochSeconds && it.hasThumb } != null
                val needsJob = !fresh &&
                    (existing == null || existing.enqueuedMtime != file.modifiedEpochSeconds)
                if (needsJob) {
                    queueDao.upsert(
                        WorkQueueEntity(
                            providerId = providerId,
                            type = type,
                            pathB64 = file.pathB64,
                            displayPath = file.displayPath,
                            enqueuedMtime = file.modifiedEpochSeconds,
                            status = WorkQueueEntity.STATUS_PENDING,
                            attempts = 0,
                            lastError = null,
                        ),
                    )
                }
            }
        }
        return visited
    }

    private suspend fun processQueue(providerId: String, limit: Int): Int {
        if (limit <= 0) return 0
        val jobs = queueDao.pending(providerId, WorkQueueEntity.TYPE_THUMB, limit)
        jobs.forEachIndexed { index, job ->
            telemetry.jobStarted(
                WorkQueueEntity.TYPE_THUMB,
                job.displayPath.substringAfterLast('/'),
                index + 1,
                jobs.size,
            )
            val thumb = thumbnails.generate(job.displayPath, job.pathB64)
            val updated = if (thumb != null) {
                job.copy(status = WorkQueueEntity.STATUS_DONE, lastError = null)
            } else {
                telemetry.errorLogged(
                    WorkQueueEntity.TYPE_THUMB,
                    job.displayPath.substringAfterLast('/'),
                    "generation_failed",
                )
                val attempts = job.attempts + 1
                job.copy(
                    attempts = attempts,
                    status = if (attempts >= WorkQueueEntity.MAX_ATTEMPTS) {
                        WorkQueueEntity.STATUS_FAILED
                    } else {
                        WorkQueueEntity.STATUS_PENDING
                    },
                    lastError = "generation_failed",
                )
            }
            queueDao.upsert(updated)
        }
        telemetry.jobsFinished()
        return jobs.size
    }

    private suspend fun currentProviderId(): String =
        if (env.useFakeProvider.first()) TrashRepository.PROVIDER_FAKE else TrashRepository.PROVIDER_FREEBOX

    private fun folderDisplayOf(folderB64: String): String =
        runCatching { PathCodec.decode(folderB64) }.getOrDefault("")

    private fun isExcluded(displayPath: String, excludedPaths: List<String>): Boolean =
        excludedPaths.any { it == displayPath || displayPath.startsWith("$it/") }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "BoxpixReconciler"
    }
}
