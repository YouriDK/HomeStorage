package com.boxpix.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkQueueDao {

    @Upsert
    suspend fun upsert(job: WorkQueueEntity)

    @Query(
        "SELECT * FROM work_queue WHERE providerId = :providerId AND type = :type " +
            "AND status = 'PENDING' LIMIT :limit",
    )
    suspend fun pending(providerId: String, type: String, limit: Int): List<WorkQueueEntity>

    @Query(
        "SELECT * FROM work_queue WHERE providerId = :providerId AND type = :type AND pathB64 = :pathB64",
    )
    suspend fun find(providerId: String, type: String, pathB64: String): WorkQueueEntity?

    @Query(
        "SELECT COUNT(*) FROM work_queue WHERE providerId = :providerId AND status = 'PENDING'",
    )
    fun pendingCount(providerId: String): Flow<Int>

    @Query("DELETE FROM work_queue WHERE providerId = :providerId AND pathB64 = :pathB64")
    suspend fun deleteForPath(providerId: String, pathB64: String)
}
