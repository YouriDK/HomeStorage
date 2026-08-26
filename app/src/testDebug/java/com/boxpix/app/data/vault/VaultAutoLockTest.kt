package com.boxpix.app.data.vault

import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

class VaultAutoLockTest {

    /** A clock the test moves by hand. */
    private class SteppingClock(private var now: Instant = Instant.EPOCH) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun instant(): Instant = now
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    private suspend fun unlockedSession(): VaultSession {
        val fake = FakeStorageProvider(
            config = FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0L),
        )
        VaultFixture.install(fake)
        val session = VaultSession(fake, RootLocator { PathCodec.encode("/Photos") }, Dispatchers.Default)
        session.probe()
        check(session.unlock(VaultFixture.PASSPHRASE) == UnlockResult.Success)
        return session
    }

    @Test
    fun `app lock enabled - vault locks with the app, immediately`() = runTest(timeout = 60.seconds) {
        val session = unlockedSession()
        val autoLock = VaultAutoLock(session, this, SteppingClock())

        autoLock.onAppStopped(appLockEnabled = true)
        advanceUntilIdle()

        assertEquals(VaultState.Locked, session.state.value)
    }

    @Test
    fun `no app lock - a quick background trip keeps the vault open`() = runTest(timeout = 60.seconds) {
        val session = unlockedSession()
        val clock = SteppingClock()
        val autoLock = VaultAutoLock(session, this, clock)

        autoLock.onAppStopped(appLockEnabled = false)
        clock.advance(Duration.ofSeconds(30))
        autoLock.onAppStarted()
        advanceUntilIdle()

        assertEquals(VaultState.Unlocked, session.state.value)
    }

    @Test
    fun `no app lock - a prolonged background stay locks the vault`() = runTest(timeout = 60.seconds) {
        val session = unlockedSession()
        val clock = SteppingClock()
        val autoLock = VaultAutoLock(session, this, clock)

        autoLock.onAppStopped(appLockEnabled = false)
        clock.advance(VaultAutoLock.BACKGROUND_LOCK_AFTER.plusSeconds(1))
        autoLock.onAppStarted()
        advanceUntilIdle()

        assertEquals(VaultState.Locked, session.state.value)
    }
}
