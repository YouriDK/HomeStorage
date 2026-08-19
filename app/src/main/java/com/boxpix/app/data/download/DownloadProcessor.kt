package com.boxpix.app.data.download

import com.boxpix.app.data.db.WorkQueueDao
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.freebox.api.FreeboxApiClient
import com.boxpix.app.data.media.StreamingAccess
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.data.trash.TrashRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Save-to-device queue. Jobs persist in Room, so an interrupted batch resumes
 * on the next kick (per-file granularity: a partial file is discarded by the
 * saver and the file restarts). Real box: the bytes are STREAMED from /dl/
 * straight into MediaStore — a 2 GB video never sits in memory.
 */
@Singleton
class DownloadProcessor @Inject constructor(
    private val provider: StorageProvider,
    private val queueDao: WorkQueueDao,
    private val saver: DeviceSaver,
    private val notifier: DownloadNotifier,
    private val progress: DownloadProgress,
    private val streaming: StreamingAccess,
    private val http: HttpClient,
    private val env: StorageEnv,
) {

    private val mutex = Mutex()

    suspend fun process(limit: Int) {
        if (!mutex.tryLock()) return
        try {
            val useFake = env.useFakeProvider.first()
            val providerId =
                if (useFake) TrashRepository.PROVIDER_FAKE else TrashRepository.PROVIDER_FREEBOX
            val jobs = queueDao.pending(providerId, WorkQueueEntity.TYPE_DOWNLOAD, limit)
            if (jobs.isEmpty()) return

            var saved = 0
            var failed = 0
            jobs.forEachIndexed { index, job ->
                val name = job.displayPath.trimEnd('/').substringAfterLast('/')
                notifier.progress(name, index + 1, jobs.size)
                progress.update(name, index + 1, jobs.size)
                val ok = runCatching { downloadOne(job, name, useFake) }.getOrDefault(false)
                if (ok) {
                    saved++
                    queueDao.upsert(job.copy(status = WorkQueueEntity.STATUS_DONE, lastError = null))
                } else {
                    val attempts = job.attempts + 1
                    val terminal = attempts >= WorkQueueEntity.MAX_ATTEMPTS
                    if (terminal) failed++
                    queueDao.upsert(
                        job.copy(
                            attempts = attempts,
                            status = if (terminal) WorkQueueEntity.STATUS_FAILED else WorkQueueEntity.STATUS_PENDING,
                            lastError = "save_failed",
                        ),
                    )
                }
            }
            notifier.done(saved, failed)
        } finally {
            progress.clear()
            mutex.unlock()
        }
    }

    private suspend fun downloadOne(job: WorkQueueEntity, name: String, useFake: Boolean): Boolean {
        val mimeType = mimeTypeFor(name)
        return if (useFake) {
            val bytes = provider.download(job.pathB64).getOrNull() ?: return false
            saver.save(name, mimeType) { it.write(bytes) }
        } else {
            val access = streaming.access() ?: return false
            saver.save(name, mimeType) { output ->
                http.prepareGet("${access.first}/dl/${job.pathB64}") {
                    header(FreeboxApiClient.X_FBX_APP_AUTH, access.second)
                    timeout { requestTimeoutMillis = LARGE_FILE_TIMEOUT_MS }
                }.execute { response ->
                    if (!response.status.isSuccess()) error("http ${response.status.value}")
                    response.bodyAsChannel().copyTo(output)
                }
            }
        }
    }

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    private companion object {
        const val LARGE_FILE_TIMEOUT_MS = 60L * 60 * 1000 // a movie over remote can be slow
    }
}
