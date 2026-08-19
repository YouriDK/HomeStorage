package com.boxpix.app.data.media

import com.boxpix.app.support.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmpTagWriterTest {

    private val writer = XmpTagWriter()

    @Test
    fun `merge into an empty packet yields the keywords`() {
        val packet = writer.mergeKeywords(null, listOf("test", "boxpix"))
        assertEquals(listOf("test", "boxpix"), writer.readKeywords(packet))
    }

    @Test
    fun `merge deduplicates case-insensitively and preserves existing`() {
        val first = writer.mergeKeywords(null, listOf("test", "boxpix"))
        val second = writer.mergeKeywords(first, listOf("TEST", "vacances"))
        assertEquals(listOf("test", "boxpix", "vacances"), writer.readKeywords(second))
    }

    @Test
    fun `container roundtrip on a real jpeg keeps it a jpeg and embeds keywords`() {
        val rewritten = writer.withKeywords(TestSupport.TINY_JPEG, listOf("test", "boxpix"))
        assertNotNull(rewritten)
        rewritten!!
        assertTrue(rewritten[0] == 0xFF.toByte() && rewritten[1] == 0xD8.toByte())

        val packet = XmpPackets.extract(rewritten)
        assertNotNull(packet)
        assertEquals(listOf("test", "boxpix"), writer.readKeywords(packet))
    }

    @Test
    fun `second container write is additive`() {
        val once = writer.withKeywords(TestSupport.TINY_JPEG, listOf("test", "boxpix"))!!
        val twice = writer.withKeywords(once, listOf("vacances"))!!
        assertEquals(
            listOf("test", "boxpix", "vacances"),
            writer.readKeywords(XmpPackets.extract(twice)),
        )
    }

    @Test
    fun `size stays tight`() {
        val rewritten = writer.withKeywords(TestSupport.TINY_JPEG, listOf("test", "boxpix"))!!
        assertTrue(rewritten.size - TestSupport.TINY_JPEG.size < 8 * 1024)
    }

    @Test
    fun `date and location land in the packet as ISO and text`() {
        // 2004-05-01T00:00:00Z — the scanned-photos correction use case.
        val packet = writer.mergeMetadata(
            null,
            XmpMetadata(takenAtEpochSeconds = 1_083_369_600L, location = "Lyon"),
        )
        assertEquals("2004-05-01T00:00:00", writer.readTakenAt(packet))
        assertEquals("Lyon", writer.readLocation(packet))
    }

    @Test
    fun `date correction overwrites, keywords stay additive`() {
        val first = writer.mergeMetadata(
            null,
            XmpMetadata(keywords = listOf("scan"), takenAtEpochSeconds = 0L, location = "Paris"),
        )
        val second = writer.mergeMetadata(
            first,
            XmpMetadata(keywords = listOf("famille"), takenAtEpochSeconds = 1_083_369_600L, location = "Lyon"),
        )
        assertEquals(listOf("scan", "famille"), writer.readKeywords(second))
        assertEquals("2004-05-01T00:00:00", writer.readTakenAt(second))
        assertEquals("Lyon", writer.readLocation(second))
    }

    @Test
    fun `metadata-only container roundtrip keeps the jpeg intact`() {
        val rewritten = writer.withMetadata(
            TestSupport.TINY_JPEG,
            XmpMetadata(takenAtEpochSeconds = 1_083_369_600L, location = "Lyon"),
        )
        assertNotNull(rewritten)
        rewritten!!
        assertTrue(rewritten[0] == 0xFF.toByte() && rewritten[1] == 0xD8.toByte())
        val packet = XmpPackets.extract(rewritten)
        assertEquals("2004-05-01T00:00:00", writer.readTakenAt(packet))
        assertEquals("Lyon", writer.readLocation(packet))
    }
}
