package com.boxpix.app.data.vault

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.fake.FakeStorageProvider
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageCapabilities
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.support.TestSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Counts inner-provider calls so tests can observe the resolution cache. */
class CountingStorageProvider(private val inner: StorageProvider) : StorageProvider {
    val downloads = AtomicInteger()
    val lists = AtomicInteger()
    var downloadedPaths = mutableListOf<String>()

    override val capabilities: StorageCapabilities get() = inner.capabilities

    override suspend fun list(pathB64: String?, onlyFolders: Boolean) =
        inner.list(pathB64, onlyFolders).also { lists.incrementAndGet() }

    override suspend fun download(pathB64: String, range: LongRange?) =
        inner.download(pathB64, range).also {
            downloads.incrementAndGet()
            downloadedPaths += runCatching { PathCodec.decode(pathB64) }.getOrDefault("?")
        }

    override suspend fun mkdir(parentB64: String, name: String) = inner.mkdir(parentB64, name)
    override suspend fun rename(pathB64: String, newName: String) = inner.rename(pathB64, newName)
    override suspend fun move(pathsB64: List<String>, destParentB64: String) = inner.move(pathsB64, destParentB64)
    override suspend fun delete(pathsB64: List<String>) = inner.delete(pathsB64)
    override suspend fun upload(parentB64: String, name: String, bytes: ByteArray) =
        inner.upload(parentB64, name, bytes)
}

class CryptomatorProviderTest {

    private fun instantFake() = FakeStorageProvider(
        config = FakeStorageProvider.FakeConfig(latencyMillis = 0L..0L, wakeDelayMillis = 0L),
    )

    private suspend fun unlockedProvider(
        counting: CountingStorageProvider,
    ): CryptomatorProvider {
        VaultFixture.install(counting)
        val session = VaultSession(
            inner = counting,
            rootLocator = RootLocator { PathCodec.encode("/Photos") },
            cryptoDispatcher = Dispatchers.Default,
        )
        session.probe()
        val unlocked = session.unlock(VaultFixture.PASSPHRASE)
        check(unlocked is UnlockResult.Success) { "fixture unlock failed: $unlocked" }
        return session.provider!!
    }

    @Test
    fun `listing decrypts names, maps sizes and filters all noise`() = runTest(timeout = 60.seconds) {
        val vault = unlockedProvider(CountingStorageProvider(instantFake()))
        val entries = (vault.list(null) as FbxResult.Ok).value.sortedBy { it.name }

        assertEquals(listOf("Holidays", "photo.jpg"), entries.map(StorageEntry::name))
        val folder = entries[0]
        assertTrue(folder.isDirectory)
        assertEquals("/Holidays", folder.displayPath)
        val photo = entries[1]
        assertEquals(TestSupport.TINY_JPEG.size.toLong(), photo.sizeBytes)
        assertEquals("image/jpeg", photo.mimeType)
        assertEquals("/photo.jpg", photo.displayPath)
    }

    @Test
    fun `downloaded photo is byte-identical to the cleartext`() = runTest(timeout = 60.seconds) {
        val vault = unlockedProvider(CountingStorageProvider(instantFake()))
        val bytes = (vault.download(PathCodec.encode("/photo.jpg")) as FbxResult.Ok).value
        assertArrayEquals(TestSupport.TINY_JPEG, bytes)
    }

    @Test
    fun `deep paths resolve and range slices the cleartext`() = runTest(timeout = 60.seconds) {
        val vault = unlockedProvider(CountingStorageProvider(instantFake()))

        val deep = (vault.download(PathCodec.encode("/Holidays/2024/deep.png")) as FbxResult.Ok).value
        assertArrayEquals(VaultFixture.deepPngBytes, deep)

        val listed = (vault.list(PathCodec.encode("/Holidays/2024")) as FbxResult.Ok).value
        assertEquals(listOf("deep.png"), listed.map(StorageEntry::name))

        val slice = (vault.download(PathCodec.encode("/Holidays/2024/deep.png"), 10L..19L) as FbxResult.Ok).value
        assertArrayEquals(VaultFixture.deepPngBytes.copyOfRange(10, 20), slice)
    }

    @Test
    fun `directory resolution is cached until invalidated`() = runTest(timeout = 60.seconds) {
        val counting = CountingStorageProvider(instantFake())
        val vault = unlockedProvider(counting)

        vault.list(PathCodec.encode("/Holidays/2024"))
        val dirIdDownloads = { counting.downloadedPaths.count { it.endsWith("/${VaultFormat.DIR_ID_FILE}") } }
        assertEquals("two segments resolved on first hit", 2, dirIdDownloads())

        vault.list(PathCodec.encode("/Holidays/2024"))
        assertEquals("cache hit: no new dir.c9r downloads", 2, dirIdDownloads())

        vault.invalidateResolutionCache()
        vault.list(PathCodec.encode("/Holidays/2024"))
        assertEquals("cache invalidated: resolved again", 4, dirIdDownloads())
    }

    @Test
    fun `missing file and missing folder map to not found`() = runTest(timeout = 60.seconds) {
        val vault = unlockedProvider(CountingStorageProvider(instantFake()))

        val file = vault.download(PathCodec.encode("/nope.jpg"))
        assertTrue(file is FbxResult.Err)

        val folder = vault.list(PathCodec.encode("/NoSuchFolder"))
        assertEquals(
            StorageProvider.ERROR_NOT_FOUND,
            ((folder as FbxResult.Err).error as FreeboxError.Api).code,
        )
    }
}
