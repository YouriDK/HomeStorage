package com.boxpix.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrashDao {

    @Query("SELECT * FROM trash_items ORDER BY trashedAtEpochSeconds DESC")
    fun items(): Flow<List<TrashItemEntity>>

    @Query("SELECT COUNT(*) FROM trash_items")
    fun count(): Flow<Int>

    @Query("SELECT * FROM trash_items")
    suspend fun all(): List<TrashItemEntity>

    @Query("SELECT * FROM trash_items WHERE trashedAtEpochSeconds < :cutoffEpochSeconds")
    suspend fun olderThan(cutoffEpochSeconds: Long): List<TrashItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TrashItemEntity)

    @Query("DELETE FROM trash_items WHERE trashPathB64 = :trashPathB64")
    suspend fun delete(trashPathB64: String)
}
