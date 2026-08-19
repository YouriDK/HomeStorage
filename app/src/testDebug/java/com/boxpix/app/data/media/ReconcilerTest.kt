package com.boxpix.app.data.media

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.db.MediaDao
import com.boxpix.app.data.db.MediaItemEntity
import com.boxpix.app.data.db.WorkQueueDao
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageFolders
import com.boxpix.app.support.InMemoryMediaDao
import com.boxpix.app.support.InMemoryWorkQueueDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class StubProcessor : MediaProcessor {
    override fun readTakenAtEpochSeconds(imageBytes: ByteArray): Long? = STUB_TAKEN_AT
    override fun makeThumbnail(imageBytes: ByteArray): ByteArray = byteArrayOf(9, 9, 9)

    companion object {
        const val STUB_TAKEN_AT = 1_700_000_000L
    }
}

class ReconcilerTest {

    private val provider = FakeStorageProvider(
        FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0),
    )
    private val mediaDao = InMemoryMediaDao()
    private val queueDao = InMemoryWorkQueueDao()
    private val env = StorageEnv(useFakeProvider = flowOf(true), fakeControls = provider)
    private val thumbnails = ThumbnailRepository(
        provider, mediaDao, StubProcessor(), StorageFolders(provider), env,
    )
    private val reconciler = Reconciler(
        provider, mediaDao, queueDao, thumbnails, env,
        rootLocator = { PathCodec.encode("/Photos") },
    )

    private fun b64(path: String) = PathCodec.encode(path)

    private suspend fun names(path: String): List<String> =
        (provider.list(b64(path)) as? FbxResult.Ok)?.value?.map { it.name } ?: emptyList()

    @Test
    fun `scan indexes the whole tree without processing`() = runTest {
        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 0)

        val rows = mediaDao.allRows()
        assertEquals(182, rows.size)
        assertEquals(10, rows.count { it.isVideo })
        // Images queued for thumbnails, videos not (worker profile is v1.5).
        assertEquals(172, queueDao.allJobs().count { it.status == WorkQueueEntity.STATUS_PENDING })
        assertTrue(rows.all { it.takenAtEpochSeconds == null && !it.hasThumb })
    }

    @Test
    fun `processing generates sidecars and fills exif dates`() = runTest {
        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 500)

        assertEquals(0, queueDao.allJobs().count { it.status == WorkQueueEntity.STATUS_PENDING })
        val images = mediaDao.allRows().filterNot { it.isVideo }
        assertTrue(images.all { it.hasThumb })
        assertTrue(images.all { it.takenAtEpochSeconds == StubProcessor.STUB_TAKEN_AT })

        // The sidecars physically exist under the /.thumbs mirror.
        val scanThumbs = names("/.thumbs/Photos/Scans")
        assertEquals(12, scanThumbs.size)
        assertTrue(scanThumbs.all { it.endsWith(".webp") })
    }

    @Test
    fun `a second pass is idempotent`() = runTest {
        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 500)
        val rowsAfterFirst = mediaDao.allRows().toSet()

        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 500)

        assertEquals(rowsAfterFirst, mediaDao.allRows().toSet())
        assertEquals(0, queueDao.allJobs().count { it.status == WorkQueueEntity.STATUS_PENDING })
    }

    @Test
    fun `a changed file is re-indexed and re-thumbed`() = runTest {
        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 500)

        val scan = (provider.list(b64("/Photos/Scans")) as FbxResult.Ok).value.first()
        provider.rename(scan.pathB64, "renamed.png") // new path + new mtime

        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 0)
        val pending = queueDao.allJobs().filter { it.status == WorkQueueEntity.STATUS_PENDING }
        assertEquals(1, pending.size)
        assertTrue(pending.single().displayPath.endsWith("renamed.png"))

        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 500)
        val row = mediaDao.allRows().single { it.name == "renamed.png" }
        assertTrue(row.hasThumb)
        // The old path's row is gone from the index.
        assertEquals(12, mediaDao.allRows().count { it.folderDisplayPath == "/Photos/Scans" })
    }

    @Test
    fun `a deleted file leaves the index`() = runTest {
        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 0)
        val before = mediaDao.allRows().size

        val victim = (provider.list(b64("/Photos/Screenshots")) as FbxResult.Ok).value.first()
        provider.delete(listOf(victim.pathB64))

        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 0)
        assertEquals(before - 1, mediaDao.allRows().size)
    }
}
