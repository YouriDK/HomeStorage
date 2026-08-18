package com.boxpix.app.data.trash

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.db.TrashDao
import com.boxpix.app.data.db.TrashItemEntity
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private class FakeTrashDao : TrashDao {
    private val store = MutableStateFlow<List<TrashItemEntity>>(emptyList())

    override fun items() = store
    override fun count() = store.map { it.size }
    override suspend fun all() = store.value
    override suspend fun olderThan(cutoffEpochSeconds: Long) =
        store.value.filter { it.trashedAtEpochSeconds < cutoffEpochSeconds }

    override suspend fun insert(item: TrashItemEntity) {
        store.value = store.value.filterNot { it.trashPathB64 == item.trashPathB64 } + item
    }

    override suspend fun delete(trashPathB64: String) {
        store.value = store.value.filterNot { it.trashPathB64 == trashPathB64 }
    }
}

private class TestClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}

class TrashRepositoryTest {

    private val provider = FakeStorageProvider(
        FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0),
    )
    private val dao = FakeTrashDao()
    private val clock = TestClock(Instant.ofEpochSecond(1_755_000_000))
    private val repo = TrashRepository(provider, dao, clock)

    private fun b64(path: String) = PathCodec.encode(path)

    private suspend fun names(path: String): List<String> =
        (provider.list(b64(path)) as? FbxResult.Ok)?.value?.map { it.name } ?: emptyList()

    private suspend fun firstScan() =
        (provider.list(b64("/Photos/Scans")) as FbxResult.Ok).value.first { !it.isDirectory }

    @Test
    fun `trash moves the file to the path mirror and records it`() = runTest {
        val victim = firstScan()

        val trashed = repo.trash(listOf(victim))
        assertTrue(trashed is FbxResult.Ok)

        assertEquals(11, names("/Photos/Scans").size)
        assertEquals(listOf(victim.name), names("/.trash/Photos/Scans"))

        val record = dao.all().single()
        assertEquals("/Photos/Scans", record.originalParentPath)
        assertEquals(victim.name, record.name)
        assertEquals(clock.now.epochSecond, record.trashedAtEpochSeconds)
    }

    @Test
    fun `a mirror name conflict gets a suffixed name`() = runTest {
        provider.mkdir(b64("/Photos"), "A")
        val inbox = (provider.list(b64("/Photos/_Inbox")) as FbxResult.Ok).value
        provider.move(listOf(inbox[0].pathB64, inbox[1].pathB64), b64("/Photos/A"))
        val files = (provider.list(b64("/Photos/A")) as FbxResult.Ok).value
        provider.rename(files[0].pathB64, "dup.jpg")

        val first = (provider.list(b64("/Photos/A")) as FbxResult.Ok).value.first { it.name == "dup.jpg" }
        repo.trash(listOf(first))

        val remaining = (provider.list(b64("/Photos/A")) as FbxResult.Ok).value.single()
        provider.rename(remaining.pathB64, "dup.jpg")
        val second = (provider.list(b64("/Photos/A")) as FbxResult.Ok).value.single()
        repo.trash(listOf(second))

        assertEquals(listOf("dup.jpg", "dup (2).jpg").sorted(), names("/.trash/Photos/A").sorted())
        assertEquals(setOf("dup.jpg", "dup (2).jpg"), dao.all().map { it.name }.toSet())
    }

    @Test
    fun `restore recreates a deleted original folder`() = runTest {
        provider.mkdir(b64("/Photos"), "Temp")
        val scan = firstScan()
        provider.move(listOf(scan.pathB64), b64("/Photos/Temp"))
        val moved = (provider.list(b64("/Photos/Temp")) as FbxResult.Ok).value.single()

        repo.trash(listOf(moved))
        provider.delete(listOf(b64("/Photos/Temp")))
        assertTrue(provider.list(b64("/Photos/Temp")) is FbxResult.Err)

        val restored = repo.restore(dao.all().single())
        assertTrue(restored is FbxResult.Ok)
        assertEquals(listOf(moved.name), names("/Photos/Temp"))
        assertTrue(dao.all().isEmpty())
        assertEquals(emptyList<String>(), names("/.trash/Photos/Temp"))
    }

    @Test
    fun `purgeOlderThan removes only expired items`() = runTest {
        val scans = (provider.list(b64("/Photos/Scans")) as FbxResult.Ok).value
        repo.trash(listOf(scans[0]))

        clock.now = clock.now.plusSeconds(40L * 86_400)
        repo.trash(listOf(scans[1]))

        repo.purgeOlderThan(30)

        val remaining = dao.all().single()
        assertEquals(scans[1].name, remaining.name)
        assertEquals(listOf(scans[1].name), names("/.trash/Photos/Scans"))
    }

    @Test
    fun `purge deletes permanently`() = runTest {
        repo.trash(listOf(firstScan()))
        val record = dao.all().single()

        val purged = repo.purge(record)
        assertTrue(purged is FbxResult.Ok)
        assertTrue(dao.all().isEmpty())
        assertEquals(emptyList<String>(), names("/.trash/Photos/Scans"))
    }
}
