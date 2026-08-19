package com.boxpix.app.data.media

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.db.MediaDao
import com.boxpix.app.data.db.WorkQueueDao
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.MirrorPaths
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageFolders
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.data.trash.TrashRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** Streaming coordinates for frame extraction — abstracted for JVM tests. */
fun interface StreamingAccess {
    /** base URL to session token, null when the box is unreachable. */
    suspend fun access(): Pair<String, String>?
}

/**
 * SPEC M7 — video poster frames, the worker's exclusive job: extract a frame
 * from the streamed file (no full download), write the .thumbs sidecar under
 * the same mirror contract as photos, and store the real duration in the index.
 * Real box only: the fake's videos are not decodable containers.
 */
@Singleton
class VideoThumbProcessor @Inject constructor(
    private val provider: StorageProvider,
    private val queueDao: WorkQueueDao,
    private val mediaDao: MediaDao,
    private val folders: StorageFolders,
    private val extractor: VideoFrameExtractor,
    private val streaming: StreamingAccess,
    private val env: StorageEnv,
) {

    private val mutex = Mutex()

    suspend fun process(limit: Int): Int {
        if (!mutex.tryLock()) return 0
        try {
            if (env.useFakeProvider.first()) return 0
            val providerId = TrashRepository.PROVIDER_FREEBOX
            val access = streaming.access() ?: return 0
            val headers = mapOf(com.boxpix.app.data.freebox.api.FreeboxApiClient.X_FBX_APP_AUTH to access.second)

            val jobs = queueDao.pending(providerId, WorkQueueEntity.TYPE_VIDEO_THUMB, limit)
            jobs.forEach { job ->
                val error = processJob(providerId, job, access.first, headers)
                queueDao.upsert(
                    if (error == null) {
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
                            lastError = error,
                        )
                    },
                )
            }
            return jobs.size
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun processJob(
        providerId: String,
        job: WorkQueueEntity,
        baseUrl: String,
        headers: Map<String, String>,
    ): String? {
        val extraction = extractor.extract("$baseUrl/dl/${job.pathB64}", headers)
            ?: return "extraction_failed"

        val sidecarPath = MirrorPaths.thumbPathFor(job.displayPath, provider.capabilities.canCreateAtRoot)
        val sidecarDir = sidecarPath.substringBeforeLast('/')
        val sidecarName = sidecarPath.substringAfterLast('/')
        folders.ensure(sidecarDir)?.let { return "mkdir_failed: $it" }
        val uploaded = provider.upload(PathCodec.encode(sidecarDir), sidecarName, extraction.thumbWebp)
        if (uploaded is FbxResult.Err) return "upload_failed: ${uploaded.error}"

        mediaDao.setHasThumb(providerId, job.pathB64, true)
        extraction.durationSeconds?.let { mediaDao.setDuration(providerId, job.pathB64, it) }
        return null
    }
}
