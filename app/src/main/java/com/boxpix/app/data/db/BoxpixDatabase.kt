package com.boxpix.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TrashItemEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BoxpixDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashDao
}
