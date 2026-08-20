package com.boxpix.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Upsert
    suspend fun upsert(items: List<MediaItemEntity>)

    @Query("SELECT * FROM media_items WHERE providerId = :providerId AND folderDisplayPath = :folder")
    suspend fun folderItems(providerId: String, folder: String): List<MediaItemEntity>

    /** Callers chunk the list (SQLite variable cap) — see SearchQueryBuilder.IN_CHUNK_SIZE. */
    @Query("DELETE FROM media_items WHERE providerId = :providerId AND pathB64 IN (:pathsB64)")
    suspend fun deleteByPaths(providerId: String, pathsB64: List<String>)

    @Query(
        "UPDATE media_items SET hasThumb = :hasThumb WHERE providerId = :providerId AND pathB64 = :pathB64",
    )
    suspend fun setHasThumb(providerId: String, pathB64: String, hasThumb: Boolean)

    /** EXIF-sourced date: never tramples a manual correction. */
    @Query(
        "UPDATE media_items SET takenAtEpochSeconds = :takenAt " +
            "WHERE providerId = :providerId AND pathB64 = :pathB64 AND takenAtManual = 0",
    )
    suspend fun setTakenAtFromExif(providerId: String, pathB64: String, takenAt: Long?)

    @Query(
        "UPDATE media_items SET takenAtEpochSeconds = :takenAt, takenAtManual = 1 " +
            "WHERE providerId = :providerId AND pathB64 = :pathB64",
    )
    suspend fun setManualTakenAt(providerId: String, pathB64: String, takenAt: Long)

    @Query(
        "UPDATE media_items SET locationText = :location " +
            "WHERE providerId = :providerId AND pathB64 = :pathB64",
    )
    suspend fun setLocation(providerId: String, pathB64: String, location: String?)

    @Query("SELECT COUNT(*) FROM media_items WHERE providerId = :providerId")
    fun count(providerId: String): Flow<Int>

    /**
     * Timeline source: photos and videos, newest capture first; rows without an
     * EXIF date fall back to their mtime so nothing disappears from the tab.
     */
    @Query(
        "SELECT * FROM media_items WHERE providerId = :providerId " +
            "ORDER BY COALESCE(takenAtEpochSeconds, mtime) DESC",
    )
    fun byCaptureDate(providerId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE providerId = :providerId AND pathB64 = :pathB64")
    suspend fun byPath(providerId: String, pathB64: String): MediaItemEntity?

    /** Post-XMP-rewrite: same path, new disk mtime (spike contract, no thumb loop). */
    @Query("UPDATE media_items SET mtime = :mtime WHERE providerId = :providerId AND pathB64 = :pathB64")
    suspend fun setMtime(providerId: String, pathB64: String, mtime: Long)

    @Query(
        "UPDATE media_items SET durationSeconds = :durationSeconds " +
            "WHERE providerId = :providerId AND pathB64 = :pathB64",
    )
    suspend fun setDuration(providerId: String, pathB64: String, durationSeconds: Long?)

    @Query("SELECT * FROM media_items WHERE providerId = :providerId")
    suspend fun all(providerId: String): List<MediaItemEntity>
}
