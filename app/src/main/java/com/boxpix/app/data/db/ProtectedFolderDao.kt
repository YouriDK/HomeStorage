package com.boxpix.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProtectedFolderDao {

    @Query("SELECT * FROM protected_folders WHERE providerId = :providerId")
    fun all(providerId: String): Flow<List<ProtectedFolderEntity>>

    @Query("SELECT * FROM protected_folders WHERE providerId = :providerId")
    suspend fun snapshot(providerId: String): List<ProtectedFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: ProtectedFolderEntity)

    @Query("DELETE FROM protected_folders WHERE providerId = :providerId AND pathB64 = :pathB64")
    suspend fun delete(providerId: String, pathB64: String)

    @Query("DELETE FROM protected_folders WHERE providerId = :providerId")
    suspend fun clear(providerId: String)
}
