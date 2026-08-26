package com.boxpix.app.data.vault

import com.boxpix.app.data.db.SearchQueryBuilder.TypeFilter
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class VaultMetaRepositoryTest {

    private class Env {
        val fake = FakeStorageProvider(
            config = FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0L),
        )
        val session = VaultSession(fake, RootLocator { PathCodec.encode("/Photos") }, Dispatchers.Default)
        val repo = VaultMetaRepository(
            session,
            CoroutineScope(Dispatchers.Unconfined),
            observeSession = false,
        )
    }

    private suspend fun unlockedEnv(): Env {
        val env = Env()
        VaultFixture.install(env.fake)
        env.session.probe()
        check(env.session.unlock(VaultFixture.PASSPHRASE) == UnlockResult.Success)
        env.repo.open()
        return env
    }

    @Test
    fun `open builds the index from the vault listing`() = runTest(timeout = 60.seconds) {
        val env = unlockedEnv()
        val paths = env.repo.entries.value.map { it.path }.sorted()
        assertEquals(listOf("/Holidays/2024/deep.png", "/Holidays/beach.jpg", "/photo.jpg"), paths)
        assertEquals("/Holidays/2024", env.repo.entries.value.first { it.name == "deep.png" }.folder)
    }

    @Test
    fun `reconcile detects additions, removals and renames - tags follow the rename`() =
        runTest(timeout = 60.seconds) {
            val env = unlockedEnv()
            val vault = env.session.provider!!

            env.repo.addTag("/photo.jpg", "keepme")
            env.repo.updateEntry("/photo.jpg") { it.copy(takenAtEpochSeconds = 123L, takenAtManual = true) }

            // Rename = same bytes under a new encrypted name, original deleted —
            // the physical layout Cryptomator desktop would leave behind.
            val rootDirId = VaultFormat.ROOT_DIR_ID
            val holidaysDirId = "11111111-2222-3333-4444-555555555555"
            val entry = env.repo.entries.value.first { it.path == "/photo.jpg" }
            val rootPhysical =
                VaultFormat.physicalDirPath("/Photos/.vault", VaultFixture.hashDirectoryId(rootDirId))
            val holidaysPhysical =
                VaultFormat.physicalDirPath("/Photos/.vault", VaultFixture.hashDirectoryId(holidaysDirId))
            val oldEnc = VaultFixture.encryptedName("photo.jpg", rootDirId)
            val newEnc = VaultFixture.encryptedName("renamed.jpg", rootDirId)
            val oldBytes = env.fake.download(PathCodec.encode("$rootPhysical/$oldEnc")).getOrNull()!!
            env.fake.upload(PathCodec.encode(rootPhysical), newEnc, oldBytes)
            env.fake.delete(listOf(PathCodec.encode("$rootPhysical/$oldEnc")))
            // A brand-new file, plus one plain removal (beach.jpg).
            env.fake.delete(
                listOf(
                    PathCodec.encode(
                        "$holidaysPhysical/${VaultFixture.encryptedName("beach.jpg", holidaysDirId)}",
                    ),
                ),
            )
            env.fake.upload(
                PathCodec.encode(rootPhysical),
                VaultFixture.encryptedName("fresh.png", rootDirId),
                VaultFixture.encryptContent(ByteArray(64) { 3 }),
            )

            vault.invalidateResolutionCache()
            env.repo.reconcile()

            val byPath = env.repo.entries.value.associateBy { it.path }
            assertNull(byPath["/photo.jpg"])
            assertNull(byPath["/Holidays/beach.jpg"])
            assertTrue("addition indexed", byPath.containsKey("/fresh.png"))
            val renamed = byPath.getValue("/renamed.jpg")
            assertEquals("metadata follows the rename", 123L, renamed.takenAtEpochSeconds)
            assertTrue(renamed.takenAtManual)
            assertEquals("tags follow the rename", listOf("keepme"), env.repo.tagsFor("/renamed.jpg"))
            assertEquals(entry.sizeBytes, renamed.sizeBytes)
        }

    @Test
    fun `meta persists inside the vault and reloads after a lock-unlock cycle`() =
        runTest(timeout = 60.seconds) {
            val env = unlockedEnv()
            env.repo.addTag("/photo.jpg", "Sea")
            env.repo.toggleFavorite("/Holidays/beach.jpg")
            env.repo.updateEntry("/photo.jpg") { it.copy(locationText = "Nice") }
            env.repo.flush()

            // Nothing readable leaks: no plaintext meta file names anywhere on
            // the disk — the files live encrypted under the vault's d/ tree.
            assertTrue(findName(env, "/Photos/.vault", "index.json").isEmpty())
            assertTrue(findName(env, "/Photos/.vault", "tags.json").isEmpty())

            env.session.lock()
            env.session.probe()
            check(env.session.unlock(VaultFixture.PASSPHRASE) == UnlockResult.Success)
            env.repo.open()

            assertEquals(listOf("Sea"), env.repo.tagsFor("/photo.jpg"))
            assertTrue(env.repo.isFavorite("/Holidays/beach.jpg"))
            assertEquals("Nice", env.repo.entries.value.first { it.path == "/photo.jpg" }.locationText)
        }

    @Test
    fun `search filters by name, type, folder and tags`() = runTest(timeout = 60.seconds) {
        val env = unlockedEnv()
        env.repo.addTag("/Holidays/beach.jpg", "Sea")

        assertEquals(
            listOf("/Holidays/beach.jpg"),
            env.repo.search(nameContains = "bea").map { it.path },
        )
        assertEquals(3, env.repo.search(types = setOf(TypeFilter.PHOTO)).size)
        assertEquals(0, env.repo.search(types = setOf(TypeFilter.VIDEO)).size)
        assertEquals(
            listOf("/Holidays/2024/deep.png", "/Holidays/beach.jpg"),
            env.repo.search(folderPrefix = "/Holidays").map { it.path }.sorted(),
        )
        assertEquals(
            listOf("/Holidays/beach.jpg"),
            env.repo.search(tagNames = setOf("Sea")).map { it.path },
        )
        assertEquals(0, env.repo.search(tagNames = setOf("Sea", "Nope")).size)
    }

    private suspend fun findName(env: Env, under: String, name: String): List<String> {
        val found = ArrayList<String>()
        val toVisit = ArrayDeque(listOf(under))
        while (toVisit.isNotEmpty()) {
            val dir = toVisit.removeFirst()
            val listed = env.fake.list(PathCodec.encode(dir)).getOrNull().orEmpty()
            listed.forEach {
                if (it.isDirectory) toVisit.addLast(it.displayPath)
                if (it.name == name) found += it.displayPath
            }
        }
        return found
    }
}
