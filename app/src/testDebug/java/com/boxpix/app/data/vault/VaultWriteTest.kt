package com.boxpix.app.data.vault

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.media.MediaProcessor
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.support.TestSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private class CountingProcessor : MediaProcessor {
    val thumbnailCalls = AtomicInteger()
    override fun readTakenAtEpochSeconds(imageBytes: ByteArray): Long? = 1_700_000_000L
    override fun makeThumbnail(imageBytes: ByteArray): ByteArray {
        thumbnailCalls.incrementAndGet()
        return byteArrayOf(9, 9, 9)
    }
}

class VaultWriteTest {

    private class Env(clock: Clock = Clock.systemUTC()) {
        val fake = FakeStorageProvider(
            config = FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0L),
        )
        val session = VaultSession(fake, RootLocator { PathCodec.encode("/Photos") }, Dispatchers.Default)
        val meta = VaultMetaRepository(
            session,
            CoroutineScope(Dispatchers.Unconfined),
            clock,
            observeSession = false,
        )
        val routing = VaultRoutingProvider(fake, session)
        val mount = "/Photos/${VaultFormat.VAULT_DIR}"
    }

    private suspend fun unlocked(clock: Clock = Clock.systemUTC()): Env {
        val env = Env(clock)
        VaultFixture.install(env.fake)
        env.session.probe()
        check(env.session.unlock(VaultFixture.PASSPHRASE) == UnlockResult.Success)
        env.meta.open()
        return env
    }

    private fun fixedClock(epochSeconds: Long): Clock =
        Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC)

    @Test
    fun `vault upload works under the cap and refuses above it`() = runTest(timeout = 60.seconds) {
        val env = unlocked()
        val small = env.routing.upload(PathCodec.encode(env.mount), "new.jpg", TestSupport.TINY_JPEG)
        assertTrue(small is FbxResult.Ok)
        val readBack = env.routing.download(PathCodec.encode("${env.mount}/new.jpg"))
        assertArrayEquals(TestSupport.TINY_JPEG, (readBack as FbxResult.Ok).value)

        val big = ByteArray(VaultRoutingProvider.MAX_VAULT_UPLOAD_BYTES + 1)
        val refused = env.routing.upload(PathCodec.encode(env.mount), "huge.bin", big)
        assertEquals(
            VaultRoutingProvider.ERROR_VAULT_UPLOAD_TOO_LARGE,
            ((refused as FbxResult.Err).error as FreeboxError.Api).code,
        )
    }

    @Test
    fun `rename and move inside the vault relocate the cleartext`() = runTest(timeout = 60.seconds) {
        val env = unlocked()

        val renamed = env.routing.rename(PathCodec.encode("${env.mount}/photo.jpg"), "cover.jpg")
        assertTrue(renamed is FbxResult.Ok)
        val names = env.routing.list(PathCodec.encode(env.mount)).getOrNull()!!.map { it.name }
        assertTrue("cover.jpg" in names && "photo.jpg" !in names)

        val moved = env.routing.move(
            listOf(PathCodec.encode("${env.mount}/cover.jpg")),
            PathCodec.encode("${env.mount}/Holidays"),
        )
        assertTrue("$moved", moved is FbxResult.Ok)
        val inHolidays = env.routing.download(PathCodec.encode("${env.mount}/Holidays/cover.jpg"))
        assertArrayEquals(TestSupport.TINY_JPEG, (inHolidays as FbxResult.Ok).value)

        val cross = env.routing.move(
            listOf(PathCodec.encode("${env.mount}/Holidays/cover.jpg")),
            PathCodec.encode("/Photos"),
        )
        assertEquals(
            VaultRoutingProvider.ERROR_VAULT_CROSS_BOUNDARY,
            ((cross as FbxResult.Err).error as FreeboxError.Api).code,
        )
    }

    @Test
    fun `in-vault trash - move to internal mirror, restore keeps tags, purge deletes`() =
        runTest(timeout = 60.seconds) {
            val env = unlocked()
            env.meta.addTag("/photo.jpg", "keepme")

            val trashed = env.meta.trashItems(
                listOf(VaultMetaRepository.TrashRequest("/photo.jpg", false, TestSupport.TINY_JPEG.size.toLong())),
            )
            assertTrue(trashed is FbxResult.Ok)
            assertTrue(env.meta.entries.value.none { it.path == "/photo.jpg" })
            val record = env.meta.trash.value.single()
            assertEquals("/.trash/photo.jpg", record.trashPath)
            // The bytes really moved inside the vault's encrypted trash.
            val vault = env.session.provider!!
            val inTrash = vault.download(PathCodec.encode(record.trashPath))
            assertArrayEquals(TestSupport.TINY_JPEG, (inTrash as FbxResult.Ok).value)

            // Reconcile while trashed: the tags survive.
            env.meta.reconcile()
            assertEquals(listOf("keepme"), env.meta.tagsFor("/photo.jpg"))

            val restored = env.meta.restore(record)
            assertTrue(restored is FbxResult.Ok)
            assertTrue(env.meta.trash.value.isEmpty())
            assertTrue(env.meta.entries.value.any { it.path == "/photo.jpg" })
            assertEquals(listOf("keepme"), env.meta.tagsFor("/photo.jpg"))

            // Trash again, then delete forever.
            env.meta.trashItems(
                listOf(VaultMetaRepository.TrashRequest("/photo.jpg", false, TestSupport.TINY_JPEG.size.toLong())),
            )
            val again = env.meta.trash.value.single()
            assertTrue(env.meta.deleteForever(again) is FbxResult.Ok)
            assertTrue(env.meta.trash.value.isEmpty())
            assertTrue(vault.download(PathCodec.encode(again.trashPath)) is FbxResult.Err)
            assertTrue(env.meta.tagsFor("/photo.jpg").isEmpty())
        }

    @Test
    fun `trash older than 30 days purges at unlock`() = runTest(timeout = 60.seconds) {
        val t0 = 1_700_000_000L
        val env = unlocked(fixedClock(t0))
        env.meta.trashItems(listOf(VaultMetaRepository.TrashRequest("/photo.jpg", false, 1L)))
        env.meta.flush()
        env.session.lock()

        // 31 days later, a fresh unlock over the SAME disk purges the record.
        val session2 = VaultSession(env.fake, RootLocator { PathCodec.encode("/Photos") }, Dispatchers.Default)
        val meta2 = VaultMetaRepository(
            session2,
            CoroutineScope(Dispatchers.Unconfined),
            fixedClock(t0 + 31 * 86_400),
            observeSession = false,
        )
        session2.probe()
        check(session2.unlock(VaultFixture.PASSPHRASE) == UnlockResult.Success)
        meta2.open()
        assertTrue(meta2.trash.value.isEmpty())
        assertTrue(
            session2.provider!!.download(PathCodec.encode("/.trash/photo.jpg")) is FbxResult.Err,
        )
    }

    @Test
    fun `vault thumbnails live in the internal mirror only`() = runTest(timeout = 60.seconds) {
        val env = unlocked()
        val processor = CountingProcessor()
        val thumbs = VaultThumbnails(env.session, env.meta, processor)

        val first = thumbs.thumbnail("/photo.jpg", allowGenerate = true)
        assertArrayEquals(byteArrayOf(9, 9, 9), first)
        assertEquals(1, processor.thumbnailCalls.get())

        // Served from the encrypted sidecar on the next request.
        val second = thumbs.thumbnail("/photo.jpg", allowGenerate = true)
        assertArrayEquals(byteArrayOf(9, 9, 9), second)
        assertEquals("sidecar hit, no regeneration", 1, processor.thumbnailCalls.get())

        // Index caught up: hasThumb + EXIF date, and nothing readable leaked.
        val entry = env.meta.entries.value.first { it.path == "/photo.jpg" }
        assertTrue(entry.hasThumb)
        assertEquals(1_700_000_000L, entry.takenAtEpochSeconds)
        assertTrue(findName(env.fake, "/Photos", "photo.jpg.webp").isEmpty())

        // Videos never generate (worker-only outside, nothing inside).
        assertEquals(null, thumbs.thumbnail("/nope.mp4", allowGenerate = false))
    }

    private suspend fun findName(fake: FakeStorageProvider, under: String, name: String): List<String> {
        val found = ArrayList<String>()
        val toVisit = ArrayDeque(listOf(under))
        while (toVisit.isNotEmpty()) {
            val dir = toVisit.removeFirst()
            fake.list(PathCodec.encode(dir)).getOrNull().orEmpty().forEach {
                if (it.isDirectory) toVisit.addLast(it.displayPath)
                if (it.name == name) found += it.displayPath
            }
        }
        return found
    }
}
