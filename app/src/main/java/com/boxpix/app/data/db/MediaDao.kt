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

    /** Drops rows of a folder that no longer exist on the disk. */
    @Query(
        "DELETE FROM media_items WHERE providerId = :providerId AND folderDisplayPath = :folder " +
            "AND pathB64 NOT IN (:keepPathsB64)",
    )
    suspend fun deleteFolderRowsNotIn(providerId: String, folder: String, keepPathsB64: List<String>)

    @Query(
        "UPDATE media_items SET hasThumb = :hasThumb WHERE providerId = :providerId AND pathB64 = :pathB64",
    )
    suspend fun setHasThumb(providerId: String, pathB64: String, hasThumb: Boolean)

    @Query(
        "UPDATE media_items SET takenAtEpochSeconds = :takenAt " +
            "WHERE providerId = :providerId AND pathB64 = :pathB64",
    )
    suspend fun setTakenAt(providerId: String, pathB64: String, takenAt: Long?)

    @Query("SELECT COUNT(*) FROM media_items WHERE providerId = :providerId")
    fun count(providerId: String): Flow<Int>

    /** Timeline source (M4): newest capture first, unread dates last. */
    @Query(
        "SELECT * FROM media_items WHERE providerId = :providerId AND isVideo = 0 " +
            "ORDER BY takenAtEpochSeconds IS NULL, takenAtEpochSeconds DESC",
    )
    fun byCaptureDate(providerId: String): Flow<List<MediaItemEntity>>
}
