package com.boxpix.app.di

import com.boxpix.app.data.media.AndroidMediaProcessor
import com.boxpix.app.data.media.MediaProcessor
import com.boxpix.app.data.storage.DefaultRootLocator
import com.boxpix.app.data.storage.RootLocator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Application-lifetime scope for work that must outlive any ViewModel. */
    @Provides
    @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()

    @Provides
    @Singleton
    fun mediaProcessor(): MediaProcessor = AndroidMediaProcessor()

    @Provides
    @Singleton
    fun rootLocator(impl: DefaultRootLocator): RootLocator = impl
}
