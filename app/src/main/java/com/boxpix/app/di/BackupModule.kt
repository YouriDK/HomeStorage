package com.boxpix.app.di

import com.boxpix.app.data.backup.BackupConfig
import com.boxpix.app.data.backup.BackupMirror
import com.boxpix.app.data.prefs.UiPrefsStore
import kotlinx.coroutines.flow.first
import com.boxpix.app.data.storage.StorageProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    /** The RAW disk provider on purpose: the mirror must see the dot-entries. */
    @Provides
    @Singleton
    fun backupMirror(
        @DiskStorage disk: StorageProvider,
        uiPrefs: UiPrefsStore,
        clock: Clock,
        scope: kotlinx.coroutines.CoroutineScope,
    ): BackupMirror = BackupMirror(
        disk,
        object : BackupConfig {
            override suspend fun backupSource() = uiPrefs.backupSource.first()
            override suspend fun backupRoot() = uiPrefs.backupRoot.first()
            override suspend fun lastBackupAtEpochSeconds() = uiPrefs.lastBackupAtEpochSeconds.first()
            override suspend fun setLastBackupAt(epochSeconds: Long) = uiPrefs.setLastBackupAt(epochSeconds)
            override suspend fun intervalDays() = uiPrefs.backupIntervalDays.first()
            override suspend fun earliestStartHour() = uiPrefs.backupEarliestHour.first()
        },
        clock,
        scope,
    )
}
