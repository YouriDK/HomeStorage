package com.boxpix.app.data.vault

import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.media.MediaProcessor
import com.boxpix.app.data.media.Reconciler
import com.boxpix.app.data.media.SyncStatus
import com.boxpix.app.data.media.ThumbnailRepository
import com.boxpix.app.data.media.WorkerTelemetry
import com.boxpix.app.data.storage.FolderListsSync
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageFolders
import com.boxpix.app.support.InMemoryExcludedFolderDao
import com.boxpix.app.support.InMemoryMediaDao
import com.boxpix.app.support.InMemoryProtectedFolderDao
import com.boxpix.app.support.InMemoryWorkQueueDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private class StubProcessor : MediaProcessor {
    override fun readTakenAtEpochSeconds(imageBytes: ByteArray): Long? = 1_700_000_000L
    override fun makeThumbnail(imageBytes: ByteArray): ByteArray = byteArrayOf(9, 9, 9)
}

/**
 * THE M8 blocking invariant: after a full unlock -> browse -> tag -> search ->
 * lock cycle — with the Reconciler and the thumbnail pipeline running against
 * the same disk — no Room table holds a row that references a vault path, and
 * no clear mirror holds vault bytes.
 */
class VaultRoomInvariantTest {

    @Test
    fun vaultNeverLeaksIntoRoom() = runTest(timeout = 60.seconds) {
        val fake = FakeStorageProvider(
            FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0),
        )
        VaultFixture.install(fake)

        val session = VaultSession(fake, RootLocator { PathCodec.encode("/Photos") }, Dispatchers.Default)
        val routing = VaultRoutingProvider(fake, session)
        val vaultMeta = VaultMetaRepository(session, CoroutineScope(Dispatchers.Unconfined), observeSession = false)

        val mediaDao = InMemoryMediaDao()
        val queueDao = InMemoryWorkQueueDao()
        val excludedDao = InMemoryExcludedFolderDao()
        val protectedDao = InMemoryProtectedFolderDao()
        val env = StorageEnv(useFakeProvider = flowOf(true), fakeControls = fake)
        // Wired exactly like production: everything talks to the ROUTING
        // provider, the one that can technically reach decrypted vault bytes.
        val thumbnails = ThumbnailRepository(
            routing, mediaDao, StubProcessor(), StorageFolders(routing), env,
        )
        val reconciler = Reconciler(
            routing, mediaDao, queueDao, thumbnails, env,
            rootLocator = { PathCodec.encode("/Photos") },
            excludedDao = excludedDao,
            folderLists = FolderListsSync(
                provider = routing,
                folders = StorageFolders(routing),
                rootLocator = { PathCodec.encode("/Photos") },
                protectedDao = protectedDao,
                excludedDao = excludedDao,
                env = env,
                deviceIdentity = { "test-device" },
                clock = java.time.Clock.systemUTC(),
                json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                scope = CoroutineScope(Dispatchers.Unconfined),
            ),
            syncStatus = SyncStatus(),
            clock = java.time.Clock.systemUTC(),
            telemetry = WorkerTelemetry(java.time.Clock.systemUTC()),
        )

        // Full cycle: unlock -> browse -> tag -> search -> (thumbs + scan) -> lock.
        session.probe()
        check(session.unlock(VaultFixture.PASSPHRASE) == UnlockResult.Success)
        vaultMeta.open()

        val mount = session.mountDisplayPath!!
        val listed = routing.list(PathCodec.encode(mount)).getOrNull()!!
        assertTrue(listed.any { it.name == "photo.jpg" })

        vaultMeta.addTag("/photo.jpg", "Secret trip")
        assertEquals(listOf("/photo.jpg"), vaultMeta.search(tagNames = setOf("Secret trip")).map { it.path })

        // A vault cell asking for a thumbnail must produce nothing — no sidecar,
        // no Room write, not even a generated thumb.
        assertNull(thumbnails.thumbnail("$mount/photo.jpg", PathCodec.encode("$mount/photo.jpg")))

        // The Reconciler sweeps the whole disk while the vault sits unlocked.
        reconciler.runPass(maxFolders = 50, processLimit = 20)

        vaultMeta.flush()
        session.lock()

        // Room: not a single row referencing the vault, in any table.
        val leaked = buildList {
            mediaDao.store.value.values.forEach { add(it.displayPath); add(decode(it.pathB64)) }
            queueDao.store.value.values.forEach { add(it.displayPath); add(decode(it.pathB64)) }
        }.filter { VaultPaths.isVaultPath(it) }
        assertEquals("Room must hold zero vault paths, found: $leaked", emptyList<String>(), leaked)

        // Clear mirrors: no vault-derived sidecar under /Photos/.thumbs, and no
        // cleartext vault filename anywhere outside the vault.
        val thumbPaths = walk(fake, "/Photos/.thumbs")
        assertTrue(
            "clear .thumbs must not mirror the vault: $thumbPaths",
            thumbPaths.none { VaultPaths.isVaultPath(it) || it.contains("photo.jpg") },
        )

        // Post-lock: the meta is purged and vault data unreachable.
        session.probe()
        assertEquals(VaultState.Locked, session.state.value)
        assertTrue(routing.list(PathCodec.encode(mount)) is com.boxpix.app.core.FbxResult.Err)
    }

    private fun decode(pathB64: String): String =
        runCatching { PathCodec.decode(pathB64) }.getOrDefault("")

    private suspend fun walk(fake: FakeStorageProvider, under: String): List<String> {
        val found = ArrayList<String>()
        val toVisit = ArrayDeque(listOf(under))
        while (toVisit.isNotEmpty()) {
            val dir = toVisit.removeFirst()
            fake.list(PathCodec.encode(dir)).getOrNull().orEmpty().forEach {
                found += it.displayPath
                if (it.isDirectory) toVisit.addLast(it.displayPath)
            }
        }
        return found
    }
}
