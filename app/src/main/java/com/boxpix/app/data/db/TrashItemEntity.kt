package com.boxpix.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One trashed entry. The file itself lives on the disk under /.trash/<mirror>;
 * this row only remembers where it came from and when, per SPEC §4.
 */
@Entity(tableName = "trash_items")
data class TrashItemEntity(
    /** Current location inside /.trash (encoded path — also the identity). */
    @PrimaryKey val trashPathB64: String,
    /** Which provider owns the file ("fake" or "freebox") — the two never mix. */
    val providerId: String,
    /** Folder (display path) the entry lived in, e.g. "/Photos/Family". */
    val originalParentPath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val trashedAtEpochSeconds: Long,
)
