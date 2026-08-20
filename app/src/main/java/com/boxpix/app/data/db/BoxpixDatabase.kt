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
    // v6 is the migration baseline: from here on schema changes ship a real
    // Migration (tags and folder lists must survive), validated against the
    // schema JSONs committed under app/schemas/.
    version = 6,
    exportSchema = true,
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
