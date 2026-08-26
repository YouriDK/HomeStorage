package com.boxpix.app.di

import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.data.vault.VaultSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VaultModule {

    @Provides
    @Singleton
    fun vaultSession(provider: StorageProvider, rootLocator: RootLocator): VaultSession =
        VaultSession(provider, rootLocator, Dispatchers.Default)
}
