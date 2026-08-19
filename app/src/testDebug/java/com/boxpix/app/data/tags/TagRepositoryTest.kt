package com.boxpix.app.data.tags

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageFolders
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.support.InMemoryTagDao
import com.boxpix.app.support.InMemoryWorkQueueDao
import com.boxpix.app.support.TestSupport
import com.boxpix.app.ui.viewer.MediaRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TagRepositoryTest {

    private val provider = FakeStorageProvider(
        FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0),
        synthesizer = { _, _ -> TestSupport.TINY_JPEG },
    )
    private val tagDao = InMemoryTagDao()
    private val queueDao = InMemoryWorkQueueDao()
    private val env = StorageEnv(useFakeProvider = flowOf(true), fakeControls = provider)
    private val json = Json { ignoreUnknownKeys = true }
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_760_000_000), ZoneOffset.UTC)
    private val journal = TagsJournal(
        provider,
        StorageFolders(provider),
        rootLocator = { PathCodec.encode("/Photos") },
        json = json,
    )
    private val pid = TrashRepository.PROVIDER_FAKE

    private fun kotlinx.coroutines.test.TestScope.repo(xmpEnabled: Boolean = true) = TagRepository(
        tagDao = tagDao,
        queueDao = queueDao,
        env = env,
        deviceIdentity = { "test-device" },
        clock = clock,
        journal = { journal },
        scope = this,
        xmpPolicy = { xmpEnabled },
    )

    private fun media(name: String, mime: String = "image/jpeg") = MediaRef(
        pathB64 = PathCodec.encode("/Photos/$name"),
        displayPath = "/Photos/$name",
        name = name,
        mtime = 1_750_000_000,
        sizeBytes = 100,
        mimeType = mime,
        takenAtEpochSeconds = null,
        isVideo = mime.startsWith("video/"),
        durationSeconds = null,
    )

    private suspend fun metaFile(name: String): String? =
        provider.download(PathCodec.encode("/.meta/$name")).let {
            (it as? FbxResult.Ok)?.value?.toString(Charsets.UTF_8)
        }

    @Test
    fun `createTag dedupes case-insensitively`() = runTest {
        val repo = repo()
        val first = repo.createTag("Vacances")
        val second = repo.createTag("vacances")
        assertNotNull(first)
        assertEquals(first!!.id, second!!.id)
        advanceUntilIdle()
    }

    @Test
    fun `addTag links and enqueues xmp for jpeg only`() = runTest {
        val repo = repo()
        val tag = repo.createTag("plage")!!
        repo.addTag(media("a.jpg"), tag.id)
        repo.addTag(media("b.png", mime = "image/png"), tag.id)
        advanceUntilIdle()

        val xmpJobs = queueDao.allJobs().filter { it.type == WorkQueueEntity.TYPE_XMP }
        assertEquals(1, xmpJobs.size)
        assertTrue(xmpJobs.single().displayPath.endsWith("a.jpg"))
        assertEquals(
            listOf("plage"),
            repo.keywordsForMedia(pid, media("a.jpg").pathB64),
        )
    }

    @Test
    fun `xmp switch off means no xmp job is ever enqueued`() = runTest {
        val repo = repo(xmpEnabled = false)
        val tag = repo.createTag("plage")!!
        repo.addTag(media("a.jpg"), tag.id)
        advanceUntilIdle()

        assertTrue(queueDao.allJobs().none { it.type == WorkQueueEntity.TYPE_XMP })
        // Tags themselves still work: Room + journal untouched by the switch.
        assertEquals(listOf("plage"), repo.keywordsForMedia(pid, media("a.jpg").pathB64))
    }

    @Test
    fun `favorites are a pinned system tag kept out of xmp and keywords`() = runTest {
        val repo = repo()
        val nowFavorite = repo.toggleFavorite(media("a.jpg"))
        advanceUntilIdle()

        assertTrue(nowFavorite)
        val favorites = tagDao.byName(pid, TagRepository.FAVORITES)!!
        assertTrue(favorites.pinned && favorites.isSystem)
        assertTrue(queueDao.allJobs().none { it.type == WorkQueueEntity.TYPE_XMP })
        assertTrue(repo.keywordsForMedia(pid, media("a.jpg").pathB64).isEmpty())
        assertEquals(listOf(media("a.jpg").pathB64), repo.favoritePaths.first())

        assertTrue(!repo.toggleFavorite(media("a.jpg")))
        advanceUntilIdle()
        assertTrue(repo.favoritePaths.first().isEmpty())
    }

    @Test
    fun `mutations export the journal and append actions`() = runTest {
        val repo = repo()
        val tag = repo.createTag("plage")!!
        repo.addTag(media("a.jpg"), tag.id)
        advanceUntilIdle()

        val snapshot = json.decodeFromString(TagsSnapshot.serializer(), metaFile(TagsJournal.TAGS_FILE)!!)
        assertEquals("test-device", snapshot.device)
        assertEquals(listOf("plage"), snapshot.tags.map { it.name })
        assertEquals(listOf("/Photos/a.jpg"), snapshot.media.map { it.path })
        assertEquals(listOf("plage"), snapshot.media.single().tags)

        val actionLines = metaFile(TagsJournal.ACTIONS_FILE)!!.trim().lines()
        assertEquals(2, actionLines.size)
        val actions = actionLines.map { json.decodeFromString(JournalAction.serializer(), it) }
        assertEquals(listOf("tag_create", "tag_add"), actions.map { it.action })
        assertTrue(actions.all { it.device == "test-device" })
    }

    @Test
    fun `a fresh remote snapshot from another device raises a conflict`() = runTest {
        val repo = repo()
        // Another device just exported (same fixed clock = "recent").
        StorageFolders(provider).ensure("/.meta")
        val remote = TagsSnapshot(
            updatedAtEpochSeconds = clock.instant().epochSecond,
            device = "other-phone",
            tags = emptyList(),
            media = emptyList(),
        )
        provider.upload(
            PathCodec.encode("/.meta"),
            TagsJournal.TAGS_FILE,
            json.encodeToString(TagsSnapshot.serializer(), remote).toByteArray(),
        )

        repo.createTag("plage")
        advanceUntilIdle()

        assertNotNull(repo.exportConflict.value)
        assertEquals("other-phone", repo.exportConflict.value!!.remoteDevice)
        // Not overwritten yet
        val onDisk = json.decodeFromString(TagsSnapshot.serializer(), metaFile(TagsJournal.TAGS_FILE)!!)
        assertEquals("other-phone", onDisk.device)

        repo.confirmConflictedExport()
        advanceUntilIdle()
        assertNull(repo.exportConflict.value)
        val after = json.decodeFromString(TagsSnapshot.serializer(), metaFile(TagsJournal.TAGS_FILE)!!)
        assertEquals("test-device", after.device)
        assertEquals(listOf("plage"), after.tags.map { it.name })
    }

    @Test
    fun `merge relinks and deletes, rename refuses collisions`() = runTest {
        val repo = repo()
        val travel = repo.createTag("travel")!!
        val trips = repo.createTag("trips")!!
        repo.addTag(media("a.jpg"), travel.id)
        repo.addTag(media("b.jpg"), travel.id)
        repo.addTag(media("b.jpg"), trips.id) // overlap: merge must dedupe
        advanceUntilIdle()

        assertTrue(!repo.renameTag(travel.id, "trips")) // collision → merge instead

        val moved = repo.mergeTags(travel.id, trips.id)
        advanceUntilIdle()
        assertEquals(2, moved)
        assertNull(tagDao.byName(pid, "travel"))
        assertEquals(listOf("trips"), repo.keywordsForMedia(pid, media("a.jpg").pathB64))
        assertEquals(listOf("trips"), repo.keywordsForMedia(pid, media("b.jpg").pathB64))

        assertTrue(repo.renameTag(trips.id, "voyages"))
        advanceUntilIdle()
        assertEquals(listOf("voyages"), repo.keywordsForMedia(pid, media("a.jpg").pathB64))
    }

    @Test
    fun `deleteTag unlinks everything`() = runTest {
        val repo = repo()
        val tag = repo.createTag("plage")!!
        repo.addTag(media("a.jpg"), tag.id)
        advanceUntilIdle()

        repo.deleteTag(tag.id)
        advanceUntilIdle()
        assertNull(tagDao.byName(pid, "plage"))
        assertTrue(repo.keywordsForMedia(pid, media("a.jpg").pathB64).isEmpty())
    }

    @Test
    fun `remapPath follows a move`() = runTest {
        val repo = repo()
        val tag = repo.createTag("plage")!!
        repo.addTag(media("a.jpg"), tag.id)
        advanceUntilIdle()

        val newB64 = PathCodec.encode("/Photos/Family/a.jpg")
        repo.remapPath(media("a.jpg").pathB64, newB64, "/Photos/Family/a.jpg")

        assertTrue(repo.keywordsForMedia(pid, media("a.jpg").pathB64).isEmpty())
        assertEquals(listOf("plage"), repo.keywordsForMedia(pid, newB64))
    }
}
