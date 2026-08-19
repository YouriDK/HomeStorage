package com.boxpix.app.data.media

import com.boxpix.app.core.FbxResult
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
import com.boxpix.app.ui.viewer.toMediaRef
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class XmpQueueProcessorTest {

    private val provider = FakeStorageProvider(
        FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0),
        synthesizer = { _, _ -> TestSupport.TINY_JPEG },
    )
    private val tagDao = InMemoryTagDao()
    private val queueDao = InMemoryWorkQueueDao()
    private val mediaDao = InMemoryMediaDao()
    private val writer = XmpTagWriter()
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_760_000_000), ZoneOffset.UTC)
    private val pid = TrashRepository.PROVIDER_FAKE

    private fun kotlinx.coroutines.test.TestScope.buildStack(
        fakeMode: Boolean = true,
        xmpEnabled: Boolean = true,
    ): Pair<TagRepository, XmpQueueProcessor> {
        val env = StorageEnv(useFakeProvider = flowOf(fakeMode), fakeControls = provider)
        val repo = TagRepository(
            tagDao = tagDao,
            queueDao = queueDao,
            env = env,
            deviceIdentity = { "test-device" },
            clock = clock,
            journal = {
                TagsJournal(
                    provider,
                    StorageFolders(provider),
                    rootLocator = { PathCodec.encode("/Photos") },
                    json = Json { ignoreUnknownKeys = true },
                )
            },
            scope = this,
            xmpPolicy = { xmpEnabled },
        )
        val processor = XmpQueueProcessor(
            provider = provider,
            queueDao = queueDao,
            mediaDao = mediaDao,
            tags = repo,
            writer = writer,
            env = env,
            network = { false }, // metered: real mode must refuse, fake must not care
            xmpPolicy = { xmpEnabled },
        )
        return repo to processor
    }

    @Test
    fun `switch off - even pre-existing jobs stay untouched`() = runTest {
        val (_, processor) = buildStack(xmpEnabled = false)
        queueDao.upsert(
            WorkQueueEntity(
                providerId = pid, type = WorkQueueEntity.TYPE_XMP, pathB64 = "x",
                displayPath = "/Photos/x.jpg", enqueuedMtime = 0,
                status = WorkQueueEntity.STATUS_PENDING, attempts = 0, lastError = null,
            ),
        )
        processor.process(10)
        assertEquals(
            WorkQueueEntity.STATUS_PENDING,
            queueDao.find(pid, WorkQueueEntity.TYPE_XMP, "x")!!.status,
        )
    }

    private suspend fun firstFamilyJpeg() =
        (provider.list(PathCodec.encode("/Photos/Family")) as FbxResult.Ok).value
            .first { it.mimeType == "image/jpeg" }

    @Test
    fun `happy path - keywords embedded, file replaced in place, thumbs not invalidated`() = runTest {
        val (repo, processor) = buildStack()
        val entry = firstFamilyJpeg()
        val media = entry.toMediaRef()

        mediaDao.upsert(
            listOf(
                MediaItemEntity(
                    providerId = pid, pathB64 = media.pathB64, displayPath = media.displayPath,
                    name = media.name, folderDisplayPath = "/Photos/Family", sizeBytes = media.sizeBytes,
                    mtime = media.mtime, takenAtEpochSeconds = null, mimeType = media.mimeType,
                    isVideo = false, durationSeconds = null, hasThumb = true,
                ),
            ),
        )

        val tag = repo.createTag("plage")!!
        repo.addTag(media, tag.id)
        advanceUntilIdle()

        processor.process(10)

        // XMP job done
        val xmpJob = queueDao.allJobs().single { it.type == WorkQueueEntity.TYPE_XMP }
        assertEquals(WorkQueueEntity.STATUS_DONE, xmpJob.status)

        // File replaced under its own name, no temp leftover, keywords embedded
        val folder = (provider.list(PathCodec.encode("/Photos/Family")) as FbxResult.Ok).value
        assertTrue(folder.none { it.name.endsWith(".boxpix-tmp") })
        val rewritten = (provider.download(media.pathB64) as FbxResult.Ok).value
        assertEquals(listOf("plage"), writer.readKeywords(XmpPackets.extract(rewritten)))

        // "Modified by us": index mtime adopted the new disk mtime, THUMB row says fresh
        val newDiskMtime = folder.first { it.name == media.name }.modifiedEpochSeconds
        assertEquals(newDiskMtime, mediaDao.byPath(pid, media.pathB64)!!.mtime)
        val thumbJob = queueDao.find(pid, WorkQueueEntity.TYPE_THUMB, media.pathB64)!!
        assertEquals(WorkQueueEntity.STATUS_DONE, thumbJob.status)
        assertEquals(newDiskMtime, thumbJob.enqueuedMtime)
    }

    @Test
    fun `no keywords means done without touching the file`() = runTest {
        val (_, processor) = buildStack()
        val entry = firstFamilyJpeg()
        val before = (provider.download(entry.pathB64) as FbxResult.Ok).value

        queueDao.upsert(
            WorkQueueEntity(
                providerId = pid, type = WorkQueueEntity.TYPE_XMP, pathB64 = entry.pathB64,
                displayPath = entry.displayPath, enqueuedMtime = entry.modifiedEpochSeconds,
                status = WorkQueueEntity.STATUS_PENDING, attempts = 0, lastError = null,
            ),
        )
        processor.process(10)

        assertEquals(
            WorkQueueEntity.STATUS_DONE,
            queueDao.find(pid, WorkQueueEntity.TYPE_XMP, entry.pathB64)!!.status,
        )
        val after = (provider.download(entry.pathB64) as FbxResult.Ok).value
        assertTrue(before.contentEquals(after))
    }

    @Test
    fun `real mode on metered network leaves the queue untouched`() = runTest {
        val (_, processor) = buildStack(fakeMode = false)
        queueDao.upsert(
            WorkQueueEntity(
                providerId = TrashRepository.PROVIDER_FREEBOX, type = WorkQueueEntity.TYPE_XMP,
                pathB64 = "x", displayPath = "/Archive 1/x.jpg", enqueuedMtime = 0,
                status = WorkQueueEntity.STATUS_PENDING, attempts = 0, lastError = null,
            ),
        )
        processor.process(10)

        assertEquals(
            WorkQueueEntity.STATUS_PENDING,
            queueDao.find(TrashRepository.PROVIDER_FREEBOX, WorkQueueEntity.TYPE_XMP, "x")!!.status,
        )
    }
}
