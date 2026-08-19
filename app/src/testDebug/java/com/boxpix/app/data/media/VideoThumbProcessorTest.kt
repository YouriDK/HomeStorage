package com.boxpix.app.data.media

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.db.MediaItemEntity
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageFolders
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.support.InMemoryMediaDao
import com.boxpix.app.support.InMemoryWorkQueueDao
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoThumbProcessorTest {

    private val provider = FakeStorageProvider(
        FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0),
    )
    private val queueDao = InMemoryWorkQueueDao()
    private val mediaDao = InMemoryMediaDao()
    private val pid = TrashRepository.PROVIDER_FREEBOX

    private fun processor(
        fakeMode: Boolean = false,
        extractor: VideoFrameExtractor = VideoFrameExtractor { _, _ ->
            VideoFrameExtractor.Extraction(byteArrayOf(7, 7, 7), 142)
        },
    ) = VideoThumbProcessor(
        provider = provider,
        queueDao = queueDao,
        mediaDao = mediaDao,
        folders = StorageFolders(provider),
        extractor = extractor,
        streaming = { "http://base" to "token" },
        env = StorageEnv(useFakeProvider = flowOf(fakeMode), fakeControls = provider),
    )

    private suspend fun seedJob(): Pair<String, String> {
        val video = (provider.list(PathCodec.encode("/Photos/Video")) as FbxResult.Ok).value.first()
        mediaDao.upsert(
            listOf(
                MediaItemEntity(
                    providerId = pid, pathB64 = video.pathB64, displayPath = video.displayPath,
                    name = video.name, folderDisplayPath = "/Photos/Video", sizeBytes = video.sizeBytes,
                    mtime = video.modifiedEpochSeconds, takenAtEpochSeconds = null,
                    mimeType = video.mimeType, isVideo = true, durationSeconds = null, hasThumb = false,
                ),
            ),
        )
        queueDao.upsert(
            WorkQueueEntity(
                providerId = pid, type = WorkQueueEntity.TYPE_VIDEO_THUMB, pathB64 = video.pathB64,
                displayPath = video.displayPath, enqueuedMtime = video.modifiedEpochSeconds,
                status = WorkQueueEntity.STATUS_PENDING, attempts = 0, lastError = null,
            ),
        )
        return video.pathB64 to video.displayPath
    }

    @Test
    fun `poster frame lands as a sidecar and duration reaches the index`() = runTest {
        val (pathB64, displayPath) = seedJob()

        processor().process(10)

        val job = queueDao.find(pid, WorkQueueEntity.TYPE_VIDEO_THUMB, pathB64)!!
        assertEquals(WorkQueueEntity.STATUS_DONE, job.status)

        val sidecarName = displayPath.substringAfterLast('/') + ".webp"
        val sidecars = (provider.list(PathCodec.encode("/.thumbs/Photos/Video")) as FbxResult.Ok).value
        assertTrue(sidecars.any { it.name == sidecarName })

        val row = mediaDao.byPath(pid, pathB64)!!
        assertTrue(row.hasThumb)
        assertEquals(142L, row.durationSeconds)
    }

    @Test
    fun `extraction failure retries then fails`() = runTest {
        val (pathB64, _) = seedJob()
        val failing = processor(extractor = VideoFrameExtractor { _, _ -> null })

        repeat(WorkQueueEntity.MAX_ATTEMPTS) { failing.process(10) }

        val job = queueDao.find(pid, WorkQueueEntity.TYPE_VIDEO_THUMB, pathB64)!!
        assertEquals(WorkQueueEntity.STATUS_FAILED, job.status)
    }

    @Test
    fun `fake mode never processes video jobs`() = runTest {
        val (pathB64, _) = seedJob()

        processor(fakeMode = true).process(10)

        assertEquals(
            WorkQueueEntity.STATUS_PENDING,
            queueDao.find(pid, WorkQueueEntity.TYPE_VIDEO_THUMB, pathB64)!!.status,
        )
    }
}
