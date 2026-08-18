package com.boxpix.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrashDao {

    @Query("SELECT * FROM trash_items WHERE providerId = :providerId ORDER BY trashedAtEpochSeconds DESC")
    fun items(providerId: String): Flow<List<TrashItemEntity>>

    @Query("SELECT COUNT(*) FROM trash_items WHERE providerId = :providerId")
    fun count(providerId: String): Flow<Int>

    @Query("SELECT * FROM trash_items WHERE providerId = :providerId")
    suspend fun all(providerId: String): List<TrashItemEntity>

    @Query("SELECT * FROM trash_items WHERE providerId = :providerId AND trashedAtEpochSeconds < :cutoffEpochSeconds")
    suspend fun olderThan(cutoffEpochSeconds: Long, providerId: String): List<TrashItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TrashItemEntity)

    @Query("DELETE FROM trash_items WHERE trashPathB64 = :trashPathB64")
    suspend fun delete(trashPathB64: String)
}
