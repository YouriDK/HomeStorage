package com.boxpix.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query(
        "SELECT t.id, t.name, t.pinned, t.isSystem, COUNT(mt.pathB64) AS usageCount " +
            "FROM tags t LEFT JOIN media_tags mt ON mt.tagId = t.id " +
            "WHERE t.providerId = :providerId " +
            "GROUP BY t.id ORDER BY usageCount DESC, t.name COLLATE NOCASE",
    )
    fun tagsWithCounts(providerId: String): Flow<List<TagWithCount>>

    @Query("SELECT * FROM tags WHERE providerId = :providerId AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(providerId: String, name: String): TagEntity?

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun byId(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE providerId = :providerId")
    suspend fun all(providerId: String): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Query("UPDATE tags SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    // Media ↔ tag links

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun link(link: MediaTagEntity)

    @Query("DELETE FROM media_tags WHERE providerId = :providerId AND pathB64 = :pathB64 AND tagId = :tagId")
    suspend fun unlink(providerId: String, pathB64: String, tagId: Long)

    @Query("SELECT tagId FROM media_tags WHERE providerId = :providerId AND pathB64 = :pathB64")
    suspend fun tagIdsForMedia(providerId: String, pathB64: String): List<Long>

    @Query("SELECT tagId FROM media_tags WHERE providerId = :providerId AND pathB64 = :pathB64")
    fun tagIdsForMediaFlow(providerId: String, pathB64: String): Flow<List<Long>>

    @Query("SELECT * FROM media_tags WHERE providerId = :providerId")
    suspend fun allLinks(providerId: String): List<MediaTagEntity>

    @Query("SELECT DISTINCT pathB64 FROM media_tags WHERE providerId = :providerId AND tagId = :tagId")
    fun pathsForTag(providerId: String, tagId: Long): Flow<List<String>>

    /** Paths carrying ALL of [tagIds] (combinable filters = AND). */
    @Query(
        "SELECT pathB64 FROM media_tags WHERE providerId = :providerId AND tagId IN (:tagIds) " +
            "GROUP BY pathB64 HAVING COUNT(DISTINCT tagId) = :tagCount",
    )
    suspend fun pathsWithAllTags(providerId: String, tagIds: List<Long>, tagCount: Int): List<String>

    /** SPEC §4: moving via the app remaps tagged paths atomically. */
    @Query(
        "UPDATE media_tags SET pathB64 = :newPathB64, displayPath = :newDisplayPath " +
            "WHERE providerId = :providerId AND pathB64 = :oldPathB64",
    )
    suspend fun remapPath(providerId: String, oldPathB64: String, newPathB64: String, newDisplayPath: String)
}
