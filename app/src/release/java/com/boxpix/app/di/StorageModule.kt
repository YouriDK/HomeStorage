package com.boxpix.app.di

import com.boxpix.app.data.freebox.FreeboxProvider
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.flowOf
import javax.inject.Singleton

/** Release wiring: always the real Freebox provider, no fake anywhere. */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun storageProvider(real: FreeboxProvider): StorageProvider = real

    @Provides
    @Singleton
    fun storageEnv(): StorageEnv =
        StorageEnv(useFakeProvider = flowOf(false), fakeControls = null)
}
