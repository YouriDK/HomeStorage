package com.boxpix.app.data.db

import androidx.room.Entity

/**
 * One media file of the index (SPEC §4). The index is a reconstructible cache:
 * the disk is the truth, the reconciler rebuilds these rows from listings.
 */
@Entity(tableName = "media_items", primaryKeys = ["providerId", "pathB64"])
data class MediaItemEntity(
    val providerId: String,
    val pathB64: String,
    val displayPath: String,
    val name: String,
    val folderDisplayPath: String,
    val sizeBytes: Long,
    val mtime: Long,
    /** EXIF capture date; null until the pipeline has read the file. */
    val takenAtEpochSeconds: Long?,
    /** True when the date was corrected by hand — EXIF reads no longer overwrite it. */
    val takenAtManual: Boolean = false,
    /** Free-text place set by hand; written to XMP when the queue is on. */
    val locationText: String? = null,
    val mimeType: String?,
    val isVideo: Boolean,
    val durationSeconds: Long?,
    val hasThumb: Boolean,
)
