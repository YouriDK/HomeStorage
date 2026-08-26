package com.boxpix.app.di

import com.boxpix.app.data.fake.AndroidFakeImageSynthesizer
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.fake.FakeVaultInstaller
import com.boxpix.app.data.freebox.FreeboxProvider
import com.boxpix.app.data.prefs.UiPrefsStore
import com.boxpix.app.data.storage.FakeControls
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * Debug wiring: the fake provider is the default until M1 is validated against
 * the real box. The Settings debug group flips the preference at runtime — no
 * rebuild needed for the M1 gate.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun fakeProvider(scope: CoroutineScope): FakeStorageProvider {
        val synthesizer = AndroidFakeImageSynthesizer()
        return FakeStorageProvider(synthesizer = synthesizer).also { fake ->
            // M8: the fake disk ships a real Cryptomator vault (passphrase
            // "boxpix") so the whole vault flow works with no box around.
            scope.launch { FakeVaultInstaller.install(fake, synthesizer) }
        }
    }

    @Provides
    @Singleton
    fun storageEnv(prefs: UiPrefsStore, fake: FakeStorageProvider, scope: CoroutineScope): StorageEnv =
        StorageEnv(
            useFakeProvider = prefs.useFakeProvider,
            fakeControls = object : FakeControls {
                override fun sleepDisk() = fake.sleepDisk()
                override fun resetData() {
                    fake.resetData()
                    scope.launch { FakeVaultInstaller.install(fake, AndroidFakeImageSynthesizer()) }
                }
            },
        )

    @Provides
    @Singleton
    @DiskStorage
    fun diskProvider(
        fake: FakeStorageProvider,
        real: FreeboxProvider,
        prefs: UiPrefsStore,
        scope: CoroutineScope,
    ): StorageProvider = SwitchingStorageProvider(fake, real, prefs.useFakeProvider, scope)
}
