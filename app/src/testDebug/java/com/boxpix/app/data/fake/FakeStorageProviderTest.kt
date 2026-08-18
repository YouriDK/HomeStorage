package com.boxpix.app.data.fake

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.StorageProvider
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeStorageProviderTest {

    private fun provider(wakeDelayMillis: Long = 0) = FakeStorageProvider(
        FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = wakeDelayMillis),
    )

    private fun b64(path: String) = PathCodec.encode(path)

    private suspend fun FakeStorageProvider.names(path: String): List<String> =
        (list(b64(path)) as FbxResult.Ok).value.map { it.name }

    @Test
    fun `root exposes only Photos`() = runTest {
        assertEquals(listOf("Photos"), provider().names("/"))
    }

    @Test
    fun `photos has the six seeded folders and inbox has exactly 38 items`() = runTest {
        val fake = provider()
        assertEquals(
            listOf("_Inbox", "Family", "Trips 2026", "Scans", "Screenshots", "Video"),
            fake.names("/Photos"),
        )
        assertEquals(38, fake.names("/Photos/_Inbox").size)
    }

    @Test
    fun `same seed produces the same tree`() = runTest {
        assertEquals(provider().names("/Photos/Family"), provider().names("/Photos/Family"))
    }

    @Test
    fun `onlyFolders filters out files`() = runTest {
        val fake = provider()
        val folders = (fake.list(b64("/Photos/Trips 2026"), onlyFolders = true) as FbxResult.Ok).value
        assertEquals(listOf("Corsica", "Lisbon"), folders.map { it.name })
        assertTrue(folders.all { it.isDirectory })
    }

    @Test
    fun `mkdir creates a folder and rejects duplicates`() = runTest {
        val fake = provider()
        val made = fake.mkdir(b64("/Photos"), "Archive")
        assertEquals("/Photos/Archive", (made as FbxResult.Ok).value.displayPath)
        assertTrue(fake.names("/Photos").contains("Archive"))

        val duplicate = fake.mkdir(b64("/Photos"), "Archive")
        assertEquals(
            StorageProvider.ERROR_CONFLICT,
            ((duplicate as FbxResult.Err).error as FreeboxError.Api).code,
        )
    }

    @Test
    fun `rename updates the paths of descendants`() = runTest {
        val fake = provider()
        val renamed = fake.rename(b64("/Photos/Scans"), "Documents")
        assertEquals("/Photos/Documents", (renamed as FbxResult.Ok).value.displayPath)

        val children = (fake.list(b64("/Photos/Documents")) as FbxResult.Ok).value
        assertEquals(12, children.size)
        assertTrue(children.all { it.displayPath.startsWith("/Photos/Documents/") })
        assertTrue(fake.list(b64("/Photos/Scans")) is FbxResult.Err)
    }

    @Test
    fun `move transfers files between folders`() = runTest {
        val fake = provider()
        val inbox = (fake.list(b64("/Photos/_Inbox")) as FbxResult.Ok).value
        val toMove = inbox.take(3).map { it.pathB64 }

        val moved = fake.move(toMove, b64("/Photos/Family"))
        assertTrue(moved is FbxResult.Ok)
        assertEquals(35, fake.names("/Photos/_Inbox").size)
        assertEquals(58, fake.names("/Photos/Family").size)
    }

    @Test
    fun `move rejects a folder into its own descendant`() = runTest {
        val fake = provider()
        val moved = fake.move(listOf(b64("/Photos/Trips 2026")), b64("/Photos/Trips 2026/Corsica"))
        assertEquals(
            "destination_inside_source",
            ((moved as FbxResult.Err).error as FreeboxError.Api).code,
        )
    }

    @Test
    fun `move rejects a name conflict in the destination`() = runTest {
        val fake = provider()
        fake.mkdir(b64("/Photos"), "Copy")
        fake.mkdir(b64("/Photos/Family"), "Copy")
        val moved = fake.move(listOf(b64("/Photos/Copy")), b64("/Photos/Family"))
        assertEquals(
            StorageProvider.ERROR_CONFLICT,
            ((moved as FbxResult.Err).error as FreeboxError.Api).code,
        )
    }

    @Test
    fun `delete removes entries`() = runTest {
        val fake = provider()
        val screenshots = (fake.list(b64("/Photos/Screenshots")) as FbxResult.Ok).value
        val deleted = fake.delete(screenshots.take(5).map { it.pathB64 })
        assertTrue(deleted is FbxResult.Ok)
        assertEquals(15, fake.names("/Photos/Screenshots").size)
    }

    @Test
    fun `videos carry a duration and images do not`() = runTest {
        val fake = provider()
        val videos = (fake.list(b64("/Photos/Video")) as FbxResult.Ok).value
        assertTrue(videos.isNotEmpty())
        videos.forEach { assertNotNull(it.durationSeconds) }

        val scans = (fake.list(b64("/Photos/Scans")) as FbxResult.Ok).value
        scans.forEach { assertNull(it.durationSeconds) }
    }

    @Test
    fun `sleep mode stalls exactly the next request`() = runTest {
        val fake = provider(wakeDelayMillis = 6_000)
        fake.sleepDisk()

        val before = currentTime
        fake.list(b64("/Photos"))
        assertEquals(6_000, currentTime - before)

        val secondStart = currentTime
        fake.list(b64("/Photos"))
        assertEquals(0, currentTime - secondStart)
    }

    @Test
    fun `resetData restores the seeded tree`() = runTest {
        val fake = provider()
        fake.delete(listOf(b64("/Photos/Video")))
        assertEquals(5, fake.names("/Photos").size)

        fake.resetData()
        assertEquals(6, fake.names("/Photos").size)
    }
}
