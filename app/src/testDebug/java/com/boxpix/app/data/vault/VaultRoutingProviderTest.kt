package com.boxpix.app.data.vault

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.support.TestSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class VaultRoutingProviderTest {

    private class Env {
        val fake = FakeStorageProvider(
            config = FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0L),
        )
        val session = VaultSession(fake, RootLocator { PathCodec.encode("/Photos") }, Dispatchers.Default)
        val routing = VaultRoutingProvider(fake, session)
        val mount = "/Photos/${VaultFormat.VAULT_DIR}"
    }

    private suspend fun unlockedEnv(): Env {
        val env = Env()
        VaultFixture.install(env.fake)
        env.session.probe()
        check(env.session.unlock(VaultFixture.PASSPHRASE) == UnlockResult.Success)
        return env
    }

    @Test
    fun `locked vault - every vault path is unreachable, invariant of lot 2`() = runTest(timeout = 60.seconds) {
        val env = Env()
        VaultFixture.install(env.fake)
        env.session.probe() // Locked, mount known — but no provider

        val listed = env.routing.list(PathCodec.encode(env.mount))
        assertEquals(
            VaultRoutingProvider.ERROR_VAULT_LOCKED,
            ((listed as FbxResult.Err).error as FreeboxError.Api).code,
        )
        val downloaded = env.routing.download(PathCodec.encode("${env.mount}/photo.jpg"))
        assertEquals(
            VaultRoutingProvider.ERROR_VAULT_LOCKED,
            ((downloaded as FbxResult.Err).error as FreeboxError.Api).code,
        )
    }

    @Test
    fun `unlocked vault - listings and downloads are served under the mount`() =
        runTest(timeout = 60.seconds) {
            val env = unlockedEnv()

            val entries = (env.routing.list(PathCodec.encode(env.mount)) as FbxResult.Ok).value
            assertEquals(listOf("Holidays", "photo.jpg"), entries.map { it.name }.sorted())
            assertTrue(entries.all { it.displayPath.startsWith("${env.mount}/") })

            val photo = env.routing.download(PathCodec.encode("${env.mount}/photo.jpg"))
            assertArrayEquals(TestSupport.TINY_JPEG, (photo as FbxResult.Ok).value)

            val deep = env.routing.download(PathCodec.encode("${env.mount}/Holidays/2024/deep.png"))
            assertArrayEquals(VaultFixture.deepPngBytes, (deep as FbxResult.Ok).value)
        }

    @Test
    fun `locking mid-session cuts access instantly`() = runTest(timeout = 60.seconds) {
        val env = unlockedEnv()
        assertTrue(env.routing.list(PathCodec.encode(env.mount)) is FbxResult.Ok)

        env.session.lock()

        val after = env.routing.list(PathCodec.encode(env.mount))
        assertEquals(
            VaultRoutingProvider.ERROR_VAULT_LOCKED,
            ((after as FbxResult.Err).error as FreeboxError.Api).code,
        )
    }

    @Test
    fun `locked vault refuses every mutation`() = runTest(timeout = 60.seconds) {
        val env = Env()
        VaultFixture.install(env.fake)
        env.session.probe() // Locked
        val inVault = PathCodec.encode("${env.mount}/photo.jpg")
        val mountB64 = PathCodec.encode(env.mount)

        listOf(
            env.routing.mkdir(mountB64, "New").map { },
            env.routing.rename(inVault, "renamed.jpg").map { },
            env.routing.upload(mountB64, "x.jpg", ByteArray(4)),
            env.routing.move(listOf(inVault), mountB64),
            env.routing.delete(listOf(inVault)),
        ).forEach { result ->
            assertEquals(
                VaultRoutingProvider.ERROR_VAULT_LOCKED,
                ((result as FbxResult.Err).error as FreeboxError.Api).code,
            )
        }
    }

    @Test
    fun `disk paths pass through untouched`() = runTest(timeout = 60.seconds) {
        val env = unlockedEnv()
        val direct = env.fake.list(PathCodec.encode("/Photos")).getOrNull()!!
        val routed = env.routing.list(PathCodec.encode("/Photos")).getOrNull()!!
        assertEquals(direct.map { it.pathB64 }, routed.map { it.pathB64 })
    }
}
