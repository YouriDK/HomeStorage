package com.boxpix.app.data.vault

import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class VaultSessionTest {

    private fun instantFake() = FakeStorageProvider(
        config = FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0L),
    )

    private val photosRoot = RootLocator { PathCodec.encode("/Photos") }

    private fun session(counting: CountingStorageProvider) =
        VaultSession(counting, photosRoot, Dispatchers.Default)

    @Test
    fun `probe finds the vault and settles Locked`() = runTest(timeout = 60.seconds) {
        val counting = CountingStorageProvider(instantFake())
        VaultFixture.install(counting)
        val session = session(counting)
        assertEquals(VaultState.Locked, session.probe())
        assertEquals(VaultState.Locked, session.state.value)
        assertNull(session.provider)
    }

    @Test
    fun `no vault on disk - probe settles NoVault and nothing else runs`() = runTest {
        val counting = CountingStorageProvider(instantFake())
        val session = session(counting)

        assertEquals(VaultState.NoVault, session.probe())
        val callsAfterProbe = counting.downloads.get()

        val unlock = session.unlock("whatever")
        assertTrue(unlock is UnlockResult.Failed)
        assertEquals(VaultState.NoVault, session.state.value)
        assertNull(session.provider)
        assertEquals("unlock must not touch storage without a vault", callsAfterProbe, counting.downloads.get())
    }

    @Test
    fun `wrong passphrase is a typed failure and stays Locked`() = runTest(timeout = 60.seconds) {
        val counting = CountingStorageProvider(instantFake())
        VaultFixture.install(counting)
        val session = session(counting)
        session.probe()

        assertEquals(UnlockResult.WrongPassphrase, session.unlock(VaultFixture.WRONG_PASSPHRASE))
        assertEquals(VaultState.Locked, session.state.value)
        assertNull(session.provider)
    }

    @Test
    fun `unlock succeeds, exposes a working provider, lock tears everything down`() =
        runTest(timeout = 60.seconds) {
            val counting = CountingStorageProvider(instantFake())
            VaultFixture.install(counting)
            val session = session(counting)
            session.probe()

            assertEquals(UnlockResult.Success, session.unlock(VaultFixture.PASSPHRASE))
            assertEquals(VaultState.Unlocked, session.state.value)
            val provider = session.provider!!
            val listed = provider.list(null).getOrNull()!!
            assertTrue(listed.any { it.name == "photo.jpg" })

            session.lock()
            assertEquals(VaultState.Locked, session.state.value)
            assertNull(session.provider)
            assertEquals(0, provider.cachedDirCount())
        }

    @Test
    fun `tampered vault config is rejected as unsupported`() = runTest(timeout = 60.seconds) {
        val counting = CountingStorageProvider(instantFake())
        VaultFixture.install(counting)
        // Overwrite the config with one whose signature no key produced.
        counting.upload(
            PathCodec.encode("/Photos/${VaultFormat.VAULT_DIR}"),
            VaultFormat.CONFIG_FILE,
            VaultFixture.tamperedConfigJwt.toByteArray(Charsets.US_ASCII),
        )
        val session = session(counting)
        session.probe()

        val unlock = session.unlock(VaultFixture.PASSPHRASE)
        assertTrue("expected UnsupportedVault, got $unlock", unlock is UnlockResult.UnsupportedVault)
        assertEquals(VaultState.Locked, session.state.value)
        assertNull(session.provider)
    }
}
