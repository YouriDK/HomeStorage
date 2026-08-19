package com.boxpix.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrashItemEntity::class,
        MediaItemEntity::class,
        WorkQueueEntity::class,
        ProtectedFolderEntity::class,
    ],
    version = 4, // v4: protected folders (pre-release, destructive migration)
    exportSchema = false,
)
abstract class BoxpixDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashDao
    abstract fun mediaDao(): MediaDao
    abstract fun workQueueDao(): WorkQueueDao
    abstract fun protectedFolderDao(): ProtectedFolderDao
}
