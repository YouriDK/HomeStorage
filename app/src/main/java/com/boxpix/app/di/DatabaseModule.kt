package com.boxpix.app.di

import android.content.Context
import androidx.room.Room
import com.boxpix.app.data.db.BoxpixDatabase
import com.boxpix.app.data.db.MediaDao
import com.boxpix.app.data.db.ProtectedFolderDao
import com.boxpix.app.data.db.SearchDao
import com.boxpix.app.data.db.TagDao
import com.boxpix.app.data.db.TrashDao
import com.boxpix.app.data.db.WorkQueueDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): BoxpixDatabase =
        Room.databaseBuilder(context, BoxpixDatabase::class.java, "boxpix.db")
            // Pre-release: the index is a reconstructible cache, migrations start at v1.0.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun trashDao(db: BoxpixDatabase): TrashDao = db.trashDao()

    @Provides
    fun mediaDao(db: BoxpixDatabase): MediaDao = db.mediaDao()

    @Provides
    fun workQueueDao(db: BoxpixDatabase): WorkQueueDao = db.workQueueDao()

    @Provides
    fun protectedFolderDao(db: BoxpixDatabase): ProtectedFolderDao = db.protectedFolderDao()

    @Provides
    fun tagDao(db: BoxpixDatabase): TagDao = db.tagDao()

    @Provides
    fun searchDao(db: BoxpixDatabase): SearchDao = db.searchDao()
}
