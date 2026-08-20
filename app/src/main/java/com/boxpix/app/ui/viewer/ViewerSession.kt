package com.boxpix.app.ui.viewer

import com.boxpix.app.data.db.MediaItemEntity
import com.boxpix.app.data.media.FileKind
import com.boxpix.app.data.media.MediaTypes
import com.boxpix.app.data.storage.StorageEntry
import javax.inject.Inject
import javax.inject.Singleton

/** Provider-agnostic media reference the viewer and timeline share. */
data class MediaRef(
    val pathB64: String,
    val displayPath: String,
    val name: String,
    val mtime: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    val takenAtEpochSeconds: Long?,
    val isVideo: Boolean,
    val durationSeconds: Long?,
) {
    /** Derived once at construction — never recomputed in composition. */
    val kind: FileKind = MediaTypes.kindOf(name)
}

fun StorageEntry.toMediaRef() = MediaRef(
    pathB64 = pathB64,
    displayPath = displayPath,
    name = name,
    mtime = modifiedEpochSeconds,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    takenAtEpochSeconds = null,
    isVideo = mimeType?.startsWith("video/") == true,
    durationSeconds = durationSeconds,
)

fun MediaItemEntity.toMediaRef() = MediaRef(
    pathB64 = pathB64,
    displayPath = displayPath,
    name = name,
    mtime = mtime,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    takenAtEpochSeconds = takenAtEpochSeconds,
    isVideo = isVideo,
    durationSeconds = durationSeconds,
)

/**
 * Hand-off between a grid and the viewer: the tapped list and start index.
 * Navigation arguments cannot carry a media list; this singleton can.
 */
@Singleton
class ViewerSession @Inject constructor() {
    var items: List<MediaRef> = emptyList()
        private set
    var startIndex: Int = 0
        private set

    fun open(items: List<MediaRef>, startIndex: Int) {
        this.items = items
        this.startIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }
}
