package com.boxpix.app.data.db

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** Separate from MediaDao so in-memory test doubles never have to fake SQL. */
@Dao
interface SearchDao {
    @RawQuery
    suspend fun search(query: SupportSQLiteQuery): List<MediaItemEntity>
}
