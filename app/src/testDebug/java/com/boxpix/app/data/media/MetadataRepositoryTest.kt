package com.boxpix.app.data.media

import com.boxpix.app.data.db.MediaItemEntity
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageFolders
import com.boxpix.app.data.tags.TagRepository
import com.boxpix.app.data.tags.TagsJournal
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.support.InMemoryMediaDao
import com.boxpix.app.support.InMemoryTagDao
import com.boxpix.app.support.InMemoryWorkQueueDao
import com.boxpix.app.support.TestSupport
import com.boxpix.app.ui.viewer.MediaRef
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MetadataRepositoryTest {

    private val provider = FakeStorageProvider(
        FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0),
        synthesizer = { _, _ -> TestSupport.TINY_JPEG },
    )
    private val mediaDao = InMemoryMediaDao()
    private val tagDao = InMemoryTagDao()
    private val queueDao = InMemoryWorkQueueDao()
    private val env = StorageEnv(useFakeProvider = flowOf(true), fakeControls = provider)
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_760_000_000), ZoneOffset.UTC)
    private val journal = TagsJournal(
        provider,
        StorageFolders(provider),
        rootLocator = { PathCodec.encode("/Photos") },
        json = Json { ignoreUnknownKeys = true },
    )
    private val pid = TrashRepository.PROVIDER_FAKE

    private fun kotlinx.coroutines.test.TestScope.repo(xmpEnabled: Boolean = true): MetadataRepository {
        val tagRepository = TagRepository(
            tagDao = tagDao,
            queueDao = queueDao,
            env = env,
            deviceIdentity = { "test-device" },
            clock = clock,
            journal = { journal },
            scope = this,
            xmpPolicy = { xmpEnabled },
        )
        return MetadataRepository(mediaDao, tagRepository, env)
    }

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

    private suspend fun seedRow(name: String, mime: String = "image/jpeg") {
        val ref = media(name, mime)
        mediaDao.upsert(
            listOf(
                MediaItemEntity(
                    providerId = pid,
                    pathB64 = ref.pathB64,
                    displayPath = ref.displayPath,
                    name = name,
                    folderDisplayPath = "/Photos",
                    sizeBytes = 100,
                    mtime = ref.mtime,
                    takenAtEpochSeconds = null,
                    mimeType = mime,
                    isVideo = false,
                    durationSeconds = null,
                    hasThumb = false,
                ),
            ),
        )
    }

    @Test
    fun `date and location land in the index flagged manual`() = runTest {
        seedRow("scan-001.jpg")
        repo().applyToSelection(
            listOf(media("scan-001.jpg")),
            tagIds = emptySet(),
            takenAtEpochSeconds = 1_083_369_600L,
            location = "  Lyon  ",
        )
        val row = mediaDao.byPath(pid, media("scan-001.jpg").pathB64)!!
        assertEquals(1_083_369_600L, row.takenAtEpochSeconds)
        assertTrue(row.takenAtManual)
        assertEquals("Lyon", row.locationText)
        advanceUntilIdle()
    }

    @Test
    fun `a later exif read never undoes a manual date`() = runTest {
        seedRow("scan-001.jpg")
        repo().applyToSelection(
            listOf(media("scan-001.jpg")),
            tagIds = emptySet(),
            takenAtEpochSeconds = 1_083_369_600L,
            location = null,
        )
        mediaDao.setTakenAtFromExif(pid, media("scan-001.jpg").pathB64, 999L)
        assertEquals(1_083_369_600L, mediaDao.byPath(pid, media("scan-001.jpg").pathB64)!!.takenAtEpochSeconds)
        advanceUntilIdle()
    }

    @Test
    fun `xmp job enqueued for jpeg only and only when the switch is on`() = runTest {
        seedRow("a.jpg")
        seedRow("b.png", mime = "image/png")
        repo().applyToSelection(
            listOf(media("a.jpg"), media("b.png", mime = "image/png")),
            tagIds = emptySet(),
            takenAtEpochSeconds = 1_083_369_600L,
            location = null,
        )
        val jobs = queueDao.allJobs().filter { it.type == WorkQueueEntity.TYPE_XMP }
        assertEquals(listOf(media("a.jpg").pathB64), jobs.map { it.pathB64 })
        advanceUntilIdle()
    }

    @Test
    fun `switch off means no file job at all`() = runTest {
        seedRow("a.jpg")
        repo(xmpEnabled = false).applyToSelection(
            listOf(media("a.jpg")),
            tagIds = emptySet(),
            takenAtEpochSeconds = 1_083_369_600L,
            location = "Lyon",
        )
        assertTrue(queueDao.allJobs().none { it.type == WorkQueueEntity.TYPE_XMP })
        advanceUntilIdle()
    }

    @Test
    fun `blank location is ignored, not stored`() = runTest {
        seedRow("a.jpg")
        repo().applyToSelection(
            listOf(media("a.jpg")),
            tagIds = emptySet(),
            takenAtEpochSeconds = null,
            location = "   ",
        )
        val row = mediaDao.byPath(pid, media("a.jpg").pathB64)!!
        assertNull(row.locationText)
        assertFalse(row.takenAtManual)
        assertTrue(queueDao.allJobs().isEmpty())
        advanceUntilIdle()
    }
}
