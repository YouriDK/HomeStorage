package com.boxpix.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TrashItemEntity::class],
    version = 2, // v2: trash_items gains providerId (pre-release, destructive migration)
    exportSchema = false,
)
abstract class BoxpixDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashDao
}
