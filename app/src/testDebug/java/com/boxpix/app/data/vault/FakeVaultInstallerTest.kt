package com.boxpix.app.data.vault

import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.fake.FakeVaultInstaller
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class FakeVaultInstallerTest {
    @Test
    fun `seeded fake vault probes and unlocks with boxpix`() = runTest(timeout = 60.seconds) {
        val fake = FakeStorageProvider(
            FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0),
        )
        FakeVaultInstaller.install(fake, null)
        val session = VaultSession(fake, RootLocator { PathCodec.encode("/Photos") }, Dispatchers.Default)
        assertEquals(VaultState.Locked, session.probe())
        assertEquals(UnlockResult.Success, session.unlock(FakeVaultInstaller.PASSPHRASE))
        val names = session.provider!!.list(null).getOrNull()!!.map { it.name }.sorted()
        assertEquals(listOf("Holidays", "secret-01.jpg", "secret-02.jpg", "secret-03.jpg"), names)
    }
}
