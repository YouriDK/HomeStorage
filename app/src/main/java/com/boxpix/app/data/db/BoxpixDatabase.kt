package com.boxpix.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrashItemEntity::class,
        MediaItemEntity::class,
        WorkQueueEntity::class,
        ProtectedFolderEntity::class,
        ExcludedFolderEntity::class,
        TagEntity::class,
        MediaTagEntity::class,
    ],
    version = 6, // v6: manual takenAt + location + excluded folders (pre-release, destructive migration)
    exportSchema = false,
)
abstract class BoxpixDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashDao
    abstract fun mediaDao(): MediaDao
    abstract fun workQueueDao(): WorkQueueDao
    abstract fun protectedFolderDao(): ProtectedFolderDao
    abstract fun excludedFolderDao(): ExcludedFolderDao
    abstract fun tagDao(): TagDao
    abstract fun searchDao(): SearchDao
}
