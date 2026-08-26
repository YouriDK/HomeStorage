package com.boxpix.app.data.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Automatic vault locking, aligned with the app lock's philosophy:
 * - app lock enabled: the whole app re-locks at every background, so the
 *   vault locks immediately with it;
 * - app lock disabled: the vault forgives quick switches but locks after a
 *   prolonged stay in the background.
 * There is no foreground inactivity timer — the app lock has none either.
 */
class VaultAutoLock(
    private val session: VaultSession,
    private val scope: CoroutineScope,
    private val clock: Clock,
) {

    private var stoppedAt: Instant? = null

    fun onAppStopped(appLockEnabled: Boolean) {
        if (appLockEnabled) {
            stoppedAt = null
            scope.launch { session.lock() }
        } else {
            stoppedAt = clock.instant()
        }
    }

    fun onAppStarted() {
        val since = stoppedAt ?: return
        stoppedAt = null
        if (Duration.between(since, clock.instant()) >= BACKGROUND_LOCK_AFTER) {
            scope.launch { session.lock() }
        }
    }

    companion object {
        val BACKGROUND_LOCK_AFTER: Duration = Duration.ofMinutes(5)
    }
}
