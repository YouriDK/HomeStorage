package com.boxpix.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrashItemEntity::class,
        MediaItemEntity::class,
        WorkQueueEntity::class,
        ProtectedFolderEntity::class,
        TagEntity::class,
        MediaTagEntity::class,
    ],
    version = 5, // v5: tags + media_tags (pre-release, destructive migration)
    exportSchema = false,
)
abstract class BoxpixDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashDao
    abstract fun mediaDao(): MediaDao
    abstract fun workQueueDao(): WorkQueueDao
    abstract fun protectedFolderDao(): ProtectedFolderDao
    abstract fun tagDao(): TagDao
}
