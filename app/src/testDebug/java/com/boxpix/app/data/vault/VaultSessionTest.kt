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
    fun `configured vault location wins over the app root`() = runTest(timeout = 60.seconds) {
        val counting = CountingStorageProvider(instantFake())
        // The vault lives on the OTHER disk; the app root has none.
        counting.mkdir(PathCodec.encode("/"), "Backup")
        VaultFixture.install(counting, diskRootDisplay = "/Backup")

        val followingRoot = VaultSession(counting, photosRoot, Dispatchers.Default)
        assertEquals(VaultState.NoVault, followingRoot.probe())

        val configured = VaultSession(counting, photosRoot, Dispatchers.Default) { "/Backup" }
        assertEquals(VaultState.Locked, configured.probe())
        assertEquals("/Backup/${VaultFormat.VAULT_DIR}", configured.mountDisplayPath)
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
    fun `raw key unlock works and a bad raw key fails closed`() = runTest(timeout = 60.seconds) {
        val counting = CountingStorageProvider(instantFake())
        VaultFixture.install(counting)
        val session = session(counting)
        session.probe()

        assertEquals(UnlockResult.Success, session.unlockWithRawKey(VaultFixture.rawMasterkeyBytes))
        assertEquals(VaultState.Unlocked, session.state.value)
        session.lock()

        val garbage = ByteArray(64) { 42 }
        val bad = session.unlockWithRawKey(garbage)
        assertTrue("expected failure, got $bad", bad !is UnlockResult.Success)
        assertEquals(VaultState.Locked, session.state.value)
        assertTrue("raw key must be wiped even on failure", garbage.all { it == 0.toByte() })
    }

    @Test
    fun `raw key is retained only on request and wiped on lock`() = runTest(timeout = 60.seconds) {
        val counting = CountingStorageProvider(instantFake())
        VaultFixture.install(counting)
        val session = session(counting)
        session.probe()

        assertEquals(UnlockResult.Success, session.unlock(VaultFixture.PASSPHRASE))
        assertNull("not retained unless asked", session.takeRawKeyForWrapping())
        session.lock()

        assertEquals(UnlockResult.Success, session.unlock(VaultFixture.PASSPHRASE, retainRawKey = true))
        val taken = session.takeRawKeyForWrapping()
        assertTrue(taken != null && taken.contentEquals(VaultFixture.rawMasterkeyBytes))
        assertNull("one-shot: gone after take", session.takeRawKeyForWrapping())
        session.lock()
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
