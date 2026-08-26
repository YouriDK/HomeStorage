package com.boxpix.app.di

import com.boxpix.app.data.media.MediaProcessor
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.data.vault.AndroidVaultKeyStore
import com.boxpix.app.data.vault.VaultAutoLock
import com.boxpix.app.data.vault.VaultKeyStore
import com.boxpix.app.data.vault.VaultMetaRepository
import com.boxpix.app.data.vault.VaultRoutingProvider
import com.boxpix.app.data.vault.VaultSession
import com.boxpix.app.data.vault.VaultThumbnails
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VaultModule {

    @Provides
    @Singleton
    fun vaultSession(@DiskStorage disk: StorageProvider, rootLocator: RootLocator): VaultSession =
        VaultSession(disk, rootLocator, Dispatchers.Default)

    /** What the whole app injects: the disk with the vault mounted under .vault. */
    @Provides
    @Singleton
    fun storageProvider(
        @DiskStorage disk: StorageProvider,
        session: VaultSession,
        meta: VaultMetaRepository,
    ): StorageProvider = VaultRoutingProvider(disk, session, onVaultMutated = meta::onVaultMutated)

    @Provides
    @Singleton
    fun vaultMetaRepository(session: VaultSession, scope: CoroutineScope, clock: Clock): VaultMetaRepository =
        VaultMetaRepository(session, scope, clock)

    @Provides
    @Singleton
    fun vaultThumbnails(
        session: VaultSession,
        meta: VaultMetaRepository,
        processor: MediaProcessor,
    ): VaultThumbnails = VaultThumbnails(session, meta, processor)

    @Provides
    @Singleton
    fun vaultAutoLock(
        session: VaultSession,
        scope: CoroutineScope,
        clock: Clock,
    ): VaultAutoLock = VaultAutoLock(session, scope, clock)
}

@Module
@InstallIn(SingletonComponent::class)
interface VaultBindings {
    @Binds
    fun vaultKeyStore(impl: AndroidVaultKeyStore): VaultKeyStore
}
