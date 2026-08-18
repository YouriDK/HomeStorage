package com.boxpix.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TrashItemEntity::class, MediaItemEntity::class, WorkQueueEntity::class],
    version = 3, // v3: media index + work queue (pre-release, destructive migration)
    exportSchema = false,
)
abstract class BoxpixDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashDao
    abstract fun mediaDao(): MediaDao
    abstract fun workQueueDao(): WorkQueueDao
}
