package com.boxpix.app.data.upload

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.vault.UnlockResult
import com.boxpix.app.data.vault.VaultFixture
import com.boxpix.app.data.vault.VaultRoutingProvider
import com.boxpix.app.data.vault.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class PhoneUploaderTest {

    private fun instantFake() = FakeStorageProvider(
        config = FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0L),
    )

    private fun file(name: String, bytes: ByteArray, dir: String = "") =
        suspend { PhoneUploader.PhoneFile(name, bytes, dir) }

    // No withTimeout here: it would count VIRTUAL time while the vault work
    // runs on real dispatchers; runTest's own timeout is the guard.
    private suspend fun awaitOutcome(uploader: PhoneUploader): PhoneUploader.Outcome =
        uploader.lastOutcome.first { it != null }!!

    @Test
    fun `uploads land in the destination, subfolders recreated, bounds reported`() =
        runTest(timeout = 60.seconds) {
            val fake = instantFake()
            val uploader = PhoneUploader(fake, CoroutineScope(Dispatchers.Unconfined))
            val photo = ByteArray(2_048) { it.toByte() }
            val nested = ByteArray(512) { (it * 3).toByte() }

            uploader.upload(
                "/Photos/Family",
                listOf(
                    file("new.jpg", photo),
                    file("trip.jpg", nested, dir = "Trip 2026/Day 1"),
                    file("huge.mp4", ByteArray(PhoneUploader.MAX_UPLOAD_BYTES + 1)),
                    suspend { null }, // unreadable pick
                ),
            )
            val outcome = awaitOutcome(uploader)

            assertEquals(2, outcome.uploaded)
            assertEquals(1, outcome.failed)
            assertEquals(1, outcome.skippedTooLarge)
            assertEquals("/Photos/Family", outcome.destDisplayPath)

            val direct = fake.download(PathCodec.encode("/Photos/Family/new.jpg"))
            assertArrayEquals(photo, (direct as FbxResult.Ok).value)
            val deep = fake.download(PathCodec.encode("/Photos/Family/Trip 2026/Day 1/trip.jpg"))
            assertArrayEquals(nested, (deep as FbxResult.Ok).value)
        }

    @Test
    fun `uploading into the vault encrypts on the way in`() = runTest(timeout = 60.seconds) {
        val fake = instantFake()
        VaultFixture.install(fake)
        val session = VaultSession(fake, RootLocator { PathCodec.encode("/Photos") }, Dispatchers.Default)
        val routing = VaultRoutingProvider(fake, session)
        session.probe()
        check(session.unlock(VaultFixture.PASSPHRASE) == UnlockResult.Success)

        val uploader = PhoneUploader(routing, CoroutineScope(Dispatchers.Unconfined))
        val secret = ByteArray(1_024) { (it * 7).toByte() }
        uploader.upload("/Photos/.vault/Holidays", listOf(file("from-phone.jpg", secret)))
        val outcome = awaitOutcome(uploader)
        assertEquals(1, outcome.uploaded)

        // Cleartext comes back through the vault…
        val back = routing.download(PathCodec.encode("/Photos/.vault/Holidays/from-phone.jpg"))
        assertArrayEquals(secret, (back as FbxResult.Ok).value)
        // …and nothing readable exists under that name on the raw disk.
        val toVisit = ArrayDeque(listOf("/Photos/.vault"))
        while (toVisit.isNotEmpty()) {
            val dir = toVisit.removeFirst()
            fake.list(PathCodec.encode(dir)).getOrNull().orEmpty().forEach {
                check(it.name != "from-phone.jpg") { "cleartext name leaked at ${it.displayPath}" }
                if (it.isDirectory) toVisit.addLast(it.displayPath)
            }
        }
    }
}
