package com.boxpix.app.data.storage

import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Where the library starts: the fake tree's /Photos, or the chosen real root. */
fun interface RootLocator {
    suspend fun rootPathB64(): String?
}

@Singleton
class DefaultRootLocator @Inject constructor(
    private val env: StorageEnv,
    private val settings: SettingsStore,
) : RootLocator {

    override suspend fun rootPathB64(): String? =
        if (env.useFakeProvider.first()) {
            PathCodec.encode(FAKE_ROOT)
        } else {
            settings.current().rootPathB64
        }

    private companion object {
        const val FAKE_ROOT = "/Photos"
    }
}
