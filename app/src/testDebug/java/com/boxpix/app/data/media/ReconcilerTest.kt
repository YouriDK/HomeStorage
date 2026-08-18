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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class InMemoryMediaDao : MediaDao {
    val store = MutableStateFlow<Map<Pair<String, String>, MediaItemEntity>>(emptyMap())

    override suspend fun upsert(items: List<MediaItemEntity>) {
        store.value = store.value + items.associateBy { it.providerId to it.pathB64 }
    }

    override suspend fun folderItems(providerId: String, folder: String) =
        store.value.values.filter { it.providerId == providerId && it.folderDisplayPath == folder }

    override suspend fun deleteFolderRowsNotIn(providerId: String, folder: String, keepPathsB64: List<String>) {
        store.value = store.value.filterValues {
            !(it.providerId == providerId && it.folderDisplayPath == folder && it.pathB64 !in keepPathsB64)
        }
    }

    override suspend fun setHasThumb(providerId: String, pathB64: String, hasThumb: Boolean) {
        store.value[providerId to pathB64]?.let {
            store.value = store.value + ((providerId to pathB64) to it.copy(hasThumb = hasThumb))
        }
    }

    override suspend fun setTakenAt(providerId: String, pathB64: String, takenAt: Long?) {
        store.value[providerId to pathB64]?.let {
            store.value = store.value + ((providerId to pathB64) to it.copy(takenAtEpochSeconds = takenAt))
        }
    }

    override fun count(providerId: String) =
        store.map { map -> map.values.count { it.providerId == providerId } }

    override fun byCaptureDate(providerId: String) =
        store.map { map ->
            map.values.filter { it.providerId == providerId && !it.isVideo }
                .sortedWith(compareBy({ it.takenAtEpochSeconds == null }, { -(it.takenAtEpochSeconds ?: 0) }))
        }

    fun all() = store.value.values.toList()
}

private class InMemoryWorkQueueDao : WorkQueueDao {
    val store = MutableStateFlow<Map<Triple<String, String, String>, WorkQueueEntity>>(emptyMap())

    override suspend fun upsert(job: WorkQueueEntity) {
        store.value = store.value + (Triple(job.providerId, job.type, job.pathB64) to job)
    }

    override suspend fun pending(providerId: String, type: String, limit: Int) =
        store.value.values
            .filter { it.providerId == providerId && it.type == type && it.status == WorkQueueEntity.STATUS_PENDING }
            .take(limit)

    override suspend fun find(providerId: String, type: String, pathB64: String) =
        store.value[Triple(providerId, type, pathB64)]

    override fun pendingCount(providerId: String) =
        store.map { map ->
            map.values.count { it.providerId == providerId && it.status == WorkQueueEntity.STATUS_PENDING }
        }

    override suspend fun deleteForPath(providerId: String, pathB64: String) {
        store.value = store.value.filterKeys { !(it.first == providerId && it.third == pathB64) }
    }

    fun all() = store.value.values.toList()
}

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

        val rows = mediaDao.all()
        assertEquals(182, rows.size)
        assertEquals(10, rows.count { it.isVideo })
        // Images queued for thumbnails, videos not (worker profile is v1.5).
        assertEquals(172, queueDao.all().count { it.status == WorkQueueEntity.STATUS_PENDING })
        assertTrue(rows.all { it.takenAtEpochSeconds == null && !it.hasThumb })
    }

    @Test
    fun `processing generates sidecars and fills exif dates`() = runTest {
        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 500)

        assertEquals(0, queueDao.all().count { it.status == WorkQueueEntity.STATUS_PENDING })
        val images = mediaDao.all().filterNot { it.isVideo }
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
        val rowsAfterFirst = mediaDao.all().toSet()

        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 500)

        assertEquals(rowsAfterFirst, mediaDao.all().toSet())
        assertEquals(0, queueDao.all().count { it.status == WorkQueueEntity.STATUS_PENDING })
    }

    @Test
    fun `a changed file is re-indexed and re-thumbed`() = runTest {
        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 500)

        val scan = (provider.list(b64("/Photos/Scans")) as FbxResult.Ok).value.first()
        provider.rename(scan.pathB64, "renamed.png") // new path + new mtime

        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 0)
        val pending = queueDao.all().filter { it.status == WorkQueueEntity.STATUS_PENDING }
        assertEquals(1, pending.size)
        assertTrue(pending.single().displayPath.endsWith("renamed.png"))

        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 500)
        val row = mediaDao.all().single { it.name == "renamed.png" }
        assertTrue(row.hasThumb)
        // The old path's row is gone from the index.
        assertEquals(12, mediaDao.all().count { it.folderDisplayPath == "/Photos/Scans" })
    }

    @Test
    fun `a deleted file leaves the index`() = runTest {
        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 0)
        val before = mediaDao.all().size

        val victim = (provider.list(b64("/Photos/Screenshots")) as FbxResult.Ok).value.first()
        provider.delete(listOf(victim.pathB64))

        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = 0)
        assertEquals(before - 1, mediaDao.all().size)
    }
}
