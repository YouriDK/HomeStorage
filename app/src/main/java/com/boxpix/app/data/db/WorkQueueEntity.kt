package com.boxpix.app.data.db

import androidx.room.Entity

/** Pending background job (SPEC §4). One row per (provider, type, file). */
@Entity(tableName = "work_queue", primaryKeys = ["providerId", "type", "pathB64"])
data class WorkQueueEntity(
    val providerId: String,
    val type: String,
    val pathB64: String,
    val displayPath: String,
    /** mtime of the original when the job was enqueued — a newer file re-enqueues. */
    val enqueuedMtime: Long,
    val status: String,
    val attempts: Int,
    val lastError: String?,
) {
    companion object {
        const val TYPE_THUMB = "THUMB"
        const val TYPE_XMP = "XMP"

        /** Worker-only (SPEC M7): the daily phone never processes these. */
        const val TYPE_VIDEO_THUMB = "VIDEO_THUMB"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
        const val MAX_ATTEMPTS = 3
    }
}
