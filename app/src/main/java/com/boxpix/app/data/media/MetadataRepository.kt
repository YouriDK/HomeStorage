package com.boxpix.app.data.media

import com.boxpix.app.data.db.MediaDao
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.tags.TagRepository
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.ui.viewer.MediaRef
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Batch metadata edits (V1 feedback): tags, capture date, place — applied to
 * the index instantly, embedded into JPEGs through the existing XMP queue.
 * The date is flagged manual so a later EXIF read never undoes the fix.
 */
@Singleton
class MetadataRepository @Inject constructor(
    private val mediaDao: MediaDao,
    private val tagRepository: TagRepository,
    private val env: StorageEnv,
) {

    suspend fun applyToSelection(
        items: List<MediaRef>,
        tagIds: Set<Long>,
        takenAtEpochSeconds: Long?,
        location: String?,
    ) {
        val trimmedLocation = location?.trim()?.takeIf { it.isNotEmpty() }
        val providerId = if (env.useFakeProvider.first()) {
            TrashRepository.PROVIDER_FAKE
        } else {
            TrashRepository.PROVIDER_FREEBOX
        }
        items.forEach { item ->
            tagIds.forEach { tagRepository.addTag(item, it) } // addTag enqueues its own XMP job
            if (takenAtEpochSeconds != null) {
                mediaDao.setManualTakenAt(providerId, item.pathB64, takenAtEpochSeconds)
            }
            if (trimmedLocation != null) {
                mediaDao.setLocation(providerId, item.pathB64, trimmedLocation)
            }
            if (takenAtEpochSeconds != null || trimmedLocation != null) {
                tagRepository.enqueueXmp(providerId, item)
            }
        }
    }
}
