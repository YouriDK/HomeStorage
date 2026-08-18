package com.boxpix.app.data.media

import com.boxpix.app.data.prefs.SettingsStore
import com.boxpix.app.data.storage.StorageEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SPEC §3 trigger "app open (light scan)": one bounded pass at startup, re-run
 * whenever the provider (fake/real) or the chosen root changes. The heavy
 * exhaustive pass belongs to the periodic ReconcilerWorker (wifi + charging).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ReconcilerScheduler @Inject constructor(
    private val reconciler: Reconciler,
    private val env: StorageEnv,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {

    fun start() {
        scope.launch {
            combine(env.useFakeProvider, settings.snapshots) { useFake, snapshot ->
                useFake to snapshot.rootPathB64
            }
                .distinctUntilChanged()
                .collectLatest {
                    // Let the UI's first listings land before competing for the box.
                    delay(START_DELAY_MS)
                    reconciler.runPass(
                        maxFolders = LIGHT_PASS_MAX_FOLDERS,
                        processLimit = LIGHT_PASS_JOB_LIMIT,
                    )
                }
        }
    }

    private companion object {
        const val START_DELAY_MS = 2_000L
        const val LIGHT_PASS_MAX_FOLDERS = 400
        const val LIGHT_PASS_JOB_LIMIT = 80
    }
}
