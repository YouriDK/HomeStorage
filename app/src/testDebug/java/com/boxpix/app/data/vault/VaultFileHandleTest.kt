package com.boxpix.app.data.vault

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/** The random-access read path under the M8 video DataSource. */
class VaultFileHandleTest {

    /** ~3 Cryptomator chunks (32 KiB each): forces multi-chunk and boundary paths. */
    private val clipBytes = ByteArray(100_000) { (it * 7).toByte() }

    private class Env {
        val fake = FakeStorageProvider(
            config = FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0L),
        )
        val session = VaultSession(fake, RootLocator { PathCodec.encode("/Photos") }, Dispatchers.Default)
    }

    private suspend fun openClip(env: Env): VaultFileHandle {
        VaultFixture.install(env.fake)
        // Drop a multi-chunk "video" straight into the vault's physical root.
        val rootPhysical = VaultFormat.physicalDirPath(
            "/Photos/.vault",
            VaultFixture.hashDirectoryId(VaultFormat.ROOT_DIR_ID),
        )
        env.fake.upload(
            PathCodec.encode(rootPhysical),
            VaultFixture.encryptedName("clip.mp4", VaultFormat.ROOT_DIR_ID),
            VaultFixture.encryptContent(clipBytes),
        )
        env.session.probe()
        check(env.session.unlock(VaultFixture.PASSPHRASE) == UnlockResult.Success)
        val opened = env.session.provider!!.openFile(PathCodec.encode("/clip.mp4"))
        return (opened as FbxResult.Ok).value
    }

    @Test
    fun `sequential reads reassemble the exact cleartext`() = runTest(timeout = 60.seconds) {
        val handle = openClip(Env())
        assertEquals(clipBytes.size.toLong(), handle.cleartextSize)

        val out = ByteArrayOutputStream()
        var offset = 0L
        while (offset < handle.cleartextSize) {
            val part = handle.read(offset, 8_192)
            assertTrue(part.isNotEmpty())
            out.write(part)
            offset += part.size
        }
        assertArrayEquals(clipBytes, out.toByteArray())
    }

    @Test
    fun `random seeks slice correctly, chunk boundaries included`() = runTest(timeout = 60.seconds) {
        val handle = openClip(Env())
        val chunk = 32 * 1024

        listOf(
            0L to 100,
            50_000L to 10_000,
            (chunk - 100).toLong() to 200, // straddles the first chunk boundary
            (2 * chunk).toLong() to 1, // exactly on a boundary
            99_990L to 1_000, // clipped at EOF
        ).forEach { (offset, want) ->
            val expected = clipBytes.copyOfRange(
                offset.toInt(),
                (offset + want).coerceAtMost(clipBytes.size.toLong()).toInt(),
            )
            assertArrayEquals("read($offset, $want)", expected, handle.read(offset, want))
        }

        assertEquals(0, handle.read(clipBytes.size.toLong(), 100).size)
    }

    @Test
    fun `locking kills the handle - reads fail closed`() = runTest(timeout = 60.seconds) {
        val env = Env()
        val handle = openClip(env)
        assertTrue(handle.read(0, 16).isNotEmpty())

        env.session.lock()

        val outcome = runCatching { handle.read(0, 16) }
        assertTrue(
            "expected a failure after lock, got $outcome",
            outcome.exceptionOrNull() is IOException || outcome.isFailure,
        )
    }
}
