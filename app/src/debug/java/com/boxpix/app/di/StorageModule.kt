package com.boxpix.app.di

import com.boxpix.app.data.fake.AndroidFakeImageSynthesizer
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.FreeboxProvider
import com.boxpix.app.data.prefs.UiPrefsStore
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
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
    fun fakeProvider(): FakeStorageProvider =
        FakeStorageProvider(synthesizer = AndroidFakeImageSynthesizer())

    @Provides
    @Singleton
    fun storageEnv(prefs: UiPrefsStore, fake: FakeStorageProvider): StorageEnv =
        StorageEnv(useFakeProvider = prefs.useFakeProvider, fakeControls = fake)

    @Provides
    @Singleton
    fun storageProvider(
        fake: FakeStorageProvider,
        real: FreeboxProvider,
        prefs: UiPrefsStore,
        scope: CoroutineScope,
    ): StorageProvider = SwitchingStorageProvider(fake, real, prefs.useFakeProvider, scope)
}
