package com.boxpix.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SPEC §4 Tag. `usageCount` is deliberately NOT stored (computed by join):
 * a stored counter drifts on remaps and merges; COUNT never lies.
 * Favourites = the system tag (isSystem), pinned by construction.
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["providerId", "name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: String,
    val name: String,
    val pinned: Boolean,
    val isSystem: Boolean,
)

@Entity(tableName = "media_tags", primaryKeys = ["providerId", "pathB64", "tagId"])
data class MediaTagEntity(
    val providerId: String,
    val pathB64: String,
    /** Kept for the tags.json journal (paths there are human-readable). */
    val displayPath: String,
    val tagId: Long,
    val taggedAtEpochSeconds: Long,
    /** Which device wrote it — feeds the journal's who/what/when. */
    val deviceId: String,
)

/** Projection: a tag with its live usage count. */
data class TagWithCount(
    val id: Long,
    val name: String,
    val pinned: Boolean,
    val isSystem: Boolean,
    val usageCount: Int,
)
