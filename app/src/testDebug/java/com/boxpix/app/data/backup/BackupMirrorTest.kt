package com.boxpix.app.data.backup

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.vault.VaultFixture
import com.boxpix.app.data.vault.VaultFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

class BackupMirrorTest {

    private class MemoryConfig(var root: Pair<String, String>?) : BackupConfig {
        var source: Pair<String, String>? = PathCodec.encode("/Photos") to "/Photos"
        var lastAt: Long? = null
        var earliestHour: Int = -1
        override suspend fun backupSource() = source
        override suspend fun backupRoot() = root
        override suspend fun lastBackupAtEpochSeconds() = lastAt
        override suspend fun setLastBackupAt(epochSeconds: Long) {
            lastAt = epochSeconds
        }

        override suspend fun earliestStartHour() = earliestHour
    }

    private class SteppingClock(private var now: Instant = Instant.ofEpochSecond(1_700_000_000)) : Clock() {
        fun advanceSeconds(seconds: Long) {
            now = now.plusSeconds(seconds)
        }

        override fun instant(): Instant = now
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    private class Env {
        val fake = FakeStorageProvider(
            config = FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0L),
        )
        val config = MemoryConfig("dest" to "/Backup")
        val clock = SteppingClock()
        val mirror = BackupMirror(
            fake,
            config,
            clock,
            CoroutineScope(Dispatchers.Unconfined),
        )

        init {
            config.root = PathCodec.encode("/Backup") to "/Backup"
        }
    }

    private suspend fun prepare(env: Env) {
        VaultFixture.install(env.fake) // a vault on the source disk
        env.fake.mkdir(PathCodec.encode("/"), "Backup") // the second disk
        // Something in the source trash must NOT reach the backup.
        env.fake.mkdir(PathCodec.encode("/"), ".trash")
        env.fake.upload(PathCodec.encode("/.trash"), "deleted.jpg", ByteArray(8))
    }

    @Test
    fun `first pass mirrors everything except the trash, vault included`() =
        runTest(timeout = 60.seconds) {
            val env = Env()
            prepare(env)

            val report = env.mirror.run()
            assertNotNull(report)
            assertTrue(report!!.copiedEntries > 0)
            assertEquals(0, report.failures)

            // A seeded clear file made it (spot check via subtree walk).
            val backupPhotos = env.fake.list(PathCodec.encode("/Backup/Photos"), includeHidden = true)
                .getOrNull()!!
            assertTrue(backupPhotos.isNotEmpty())
            // The ENCRYPTED vault is mirrored — masterkey file present, still ciphertext.
            val masterkey = env.fake.download(
                PathCodec.encode("/Backup/Photos/${VaultFormat.VAULT_DIR}/${VaultFormat.MASTERKEY_FILE}"),
            )
            assertArrayEquals(VaultFixture.masterkeyFileBytes, (masterkey as FbxResult.Ok).value)
            // The trash never crosses.
            assertTrue(
                env.fake.list(PathCodec.encode("/Backup/Photos"), includeHidden = true)
                    .getOrNull()!!.none { it.name == ".trash" },
            )
        }

    @Test
    fun `second pass is a no-op, size changes are re-copied`() = runTest(timeout = 60.seconds) {
        val env = Env()
        prepare(env)
        env.mirror.run()

        val second = env.mirror.run()!!
        assertEquals(0, second.copiedEntries)
        assertEquals(0, second.overwrittenFiles)

        // Replace a source file with different content/size -> overwrite copy.
        env.fake.upload(PathCodec.encode("/Photos"), "grown.jpg", ByteArray(10))
        env.mirror.run()
        env.fake.upload(PathCodec.encode("/Photos"), "grown.jpg", ByteArray(999))
        val third = env.mirror.run()!!
        assertEquals(1, third.overwrittenFiles)
        val mirrored = env.fake.download(PathCodec.encode("/Backup/Photos/grown.jpg"))
        assertEquals(999, (mirrored as FbxResult.Ok).value.size)
    }

    @Test
    fun `weekly gate and nested-root guard`() = runTest(timeout = 60.seconds) {
        val env = Env()
        prepare(env)

        assertTrue(env.mirror.runIfDue()) // never ran: due
        assertFalse(env.mirror.runIfDue()) // just ran: not due
        env.clock.advanceSeconds(BackupMirror.WEEKLY_SECONDS + 1)
        assertTrue(env.mirror.runIfDue()) // a week later: due again

        // A backup root inside the source refuses to run at all.
        env.config.root = PathCodec.encode("/Photos/Backup") to "/Photos/Backup"
        assertNull(env.mirror.run())
    }

    @Test
    fun `unset source refuses to run — the app root is never a fallback`() =
        runTest(timeout = 60.seconds) {
            val env = Env()
            prepare(env)

            env.config.source = null
            assertNull(env.mirror.run())
            assertFalse(env.mirror.runIfDue())

            env.config.source = PathCodec.encode("/Photos") to "/Photos"
            assertNotNull(env.mirror.run())
        }

    @Test
    fun `earliest hour gates the scheduled pass but never the manual one`() =
        runTest(timeout = 60.seconds) {
            val env = Env()
            prepare(env)

            // The stepping clock starts at 22:13 UTC.
            env.config.earliestHour = 23
            assertFalse(env.mirror.runIfDue()) // due, but too early in the day
            assertNotNull(env.mirror.run()) // "Back up now" ignores the schedule

            env.clock.advanceSeconds(BackupMirror.WEEKLY_SECONDS + 1)
            env.config.earliestHour = 22
            assertTrue(env.mirror.runIfDue()) // due and past the start hour
        }
}
