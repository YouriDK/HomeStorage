package com.boxpix.app.data.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory "last reconciliation pass" stamp for the Settings sync card. */
@Singleton
class SyncStatus @Inject constructor() {
    private val _lastPassAtEpochSeconds = MutableStateFlow<Long?>(null)
    val lastPassAtEpochSeconds: StateFlow<Long?> = _lastPassAtEpochSeconds

    fun recordPass(atEpochSeconds: Long) {
        _lastPassAtEpochSeconds.value = atEpochSeconds
    }
}
