package com.boxpix.app.data.download

import com.boxpix.app.data.db.WorkQueueDao
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.net.NetworkStatus
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.ui.viewer.MediaRef
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues save-to-device requests, with the metered-connection guard: over
 * 50 MB on a metered network (typically the remote *.fbxos.fr path on mobile
 * data), the caller must confirm before anything is queued.
 */
@Singleton
class DownloadRequester @Inject constructor(
    private val queueDao: WorkQueueDao,
    private val network: NetworkStatus,
    private val env: StorageEnv,
) {

    sealed interface Outcome {
        data object Enqueued : Outcome

        /** Nothing queued yet: confirm with the user, then call [enqueue] directly. */
        data class NeedsConfirmation(val items: List<MediaRef>, val totalBytes: Long) : Outcome
    }

    suspend fun request(items: List<MediaRef>): Outcome {
        val medias = items.filter { it.sizeBytes >= 0 }
        val totalBytes = medias.sumOf { it.sizeBytes }
        val metered = !env.useFakeProvider.first() && !network.isUnmetered()
        return if (metered && totalBytes > CONFIRM_THRESHOLD_BYTES) {
            Outcome.NeedsConfirmation(medias, totalBytes)
        } else {
            enqueue(medias)
            Outcome.Enqueued
        }
    }

    suspend fun enqueue(items: List<MediaRef>) {
        val providerId = if (env.useFakeProvider.first()) {
            TrashRepository.PROVIDER_FAKE
        } else {
            TrashRepository.PROVIDER_FREEBOX
        }
        items.forEach { item ->
            queueDao.upsert(
                WorkQueueEntity(
                    providerId = providerId,
                    type = WorkQueueEntity.TYPE_DOWNLOAD,
                    pathB64 = item.pathB64,
                    displayPath = item.displayPath,
                    enqueuedMtime = item.mtime,
                    status = WorkQueueEntity.STATUS_PENDING,
                    attempts = 0,
                    lastError = null,
                ),
            )
        }
    }

    companion object {
        const val CONFIRM_THRESHOLD_BYTES = 50L * 1024 * 1024
    }
}
