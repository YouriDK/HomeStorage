package com.boxpix.app.data.download

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.support.InMemoryWorkQueueDao
import com.boxpix.app.support.TestSupport
import com.boxpix.app.ui.viewer.toMediaRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

private class RecordingSaver : DeviceSaver {
    val saved = mutableMapOf<String, ByteArray>()
    var failNext = false

    override suspend fun save(
        displayName: String,
        mimeType: String?,
        write: suspend (OutputStream) -> Unit,
    ): Boolean {
        if (failNext) {
            failNext = false
            return false
        }
        val out = ByteArrayOutputStream()
        write(out)
        saved[displayName] = out.toByteArray()
        return true
    }
}

private class SilentNotifier : DownloadNotifier {
    var doneSaved = -1
    override fun progress(fileName: String, index: Int, total: Int) = Unit
    override fun done(savedCount: Int, failedCount: Int) {
        doneSaved = savedCount
    }
}

class DownloadTest {

    private val provider = FakeStorageProvider(
        FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0),
        synthesizer = { _, _ -> TestSupport.TINY_JPEG },
    )
    private val queueDao = InMemoryWorkQueueDao()
    private val env = StorageEnv(useFakeProvider = flowOf(true), fakeControls = provider)
    private val pid = TrashRepository.PROVIDER_FAKE

    private suspend fun someJpegs(count: Int) =
        (provider.list(PathCodec.encode("/Photos/Family")) as FbxResult.Ok).value
            .filter { it.mimeType == "image/jpeg" }
            .take(count)
            .map { it.toMediaRef() }

    private fun requester(unmetered: Boolean, fakeMode: Boolean = true) = DownloadRequester(
        queueDao = queueDao,
        network = { unmetered },
        env = StorageEnv(useFakeProvider = flowOf(fakeMode), fakeControls = provider),
    )

    @Test
    fun `small batch enqueues directly even on metered`() = runTest {
        val outcome = requester(unmetered = false, fakeMode = false).request(someJpegs(2))
        assertTrue(outcome is DownloadRequester.Outcome.Enqueued)
        assertEquals(2, queueDao.allJobs().count { it.type == WorkQueueEntity.TYPE_DOWNLOAD })
    }

    @Test
    fun `metered over the threshold asks first and enqueues nothing`() = runTest {
        val big = someJpegs(1).map { it.copy(sizeBytes = 60L * 1024 * 1024) }
        val outcome = requester(unmetered = false, fakeMode = false).request(big)
        assertTrue(outcome is DownloadRequester.Outcome.NeedsConfirmation)
        assertEquals(0, queueDao.allJobs().size)
        assertEquals(
            60L * 1024 * 1024,
            (outcome as DownloadRequester.Outcome.NeedsConfirmation).totalBytes,
        )
    }

    @Test
    fun `unmetered never asks`() = runTest {
        val big = someJpegs(1).map { it.copy(sizeBytes = 500L * 1024 * 1024) }
        assertTrue(requester(unmetered = true, fakeMode = false).request(big) is DownloadRequester.Outcome.Enqueued)
    }

    @Test
    fun `processor saves queued files and survives a per-file failure`() = runTest {
        val saver = RecordingSaver()
        val notifier = SilentNotifier()
        val processor = DownloadProcessor(
            provider = provider,
            queueDao = queueDao,
            saver = saver,
            notifier = notifier,
            progress = DownloadProgress(),
            streaming = { null },
            http = HttpClient(MockEngine { respondOk("unused") }),
            env = env,
        )
        val medias = someJpegs(2)
        requester(unmetered = true).enqueue(medias)

        saver.failNext = true // first file fails once — the queue is the resume
        processor.process(10)

        val jobs = queueDao.allJobs().filter { it.type == WorkQueueEntity.TYPE_DOWNLOAD }
        assertEquals(1, jobs.count { it.status == WorkQueueEntity.STATUS_DONE })
        assertEquals(1, jobs.count { it.status == WorkQueueEntity.STATUS_PENDING })

        processor.process(10) // resumed kick finishes the failed one
        assertTrue(
            queueDao.allJobs().filter { it.type == WorkQueueEntity.TYPE_DOWNLOAD }
                .all { it.status == WorkQueueEntity.STATUS_DONE },
        )
        assertEquals(2, saver.saved.size)
        assertTrue(saver.saved.values.all { it.contentEquals(TestSupport.TINY_JPEG) })
    }
}
