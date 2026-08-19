package com.boxpix.app.ui.gallery

import com.boxpix.app.ui.viewer.MediaRef
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class TimelineGrouperTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 8, 19)

    private fun media(name: String, takenAt: LocalDate): MediaRef = MediaRef(
        pathB64 = name,
        displayPath = "/Photos/$name",
        name = name,
        mtime = 0,
        sizeBytes = 1,
        mimeType = "image/jpeg",
        takenAtEpochSeconds = takenAt.atStartOfDay(zone).toEpochSecond() + 3600,
        isVideo = false,
        durationSeconds = null,
    )

    private fun headers(rows: List<TimelineGrouper.Row>) =
        rows.filterIsInstance<TimelineGrouper.Row.Header>()

    @Test
    fun `recent days get day sections with the design labels`() {
        val rows = TimelineGrouper.rows(
            listOf(
                media("a.jpg", today),
                media("b.jpg", today),
                media("c.jpg", today.minusDays(1)),
                media("d.jpg", LocalDate.of(2026, 8, 15)), // Saturday, 4 days ago
            ),
            today = today,
            zone = zone,
        )
        assertEquals(
            listOf("Today · 19 August" to 2, "Yesterday" to 1, "Saturday 15 August" to 1),
            headers(rows).map { it.label to it.count },
        )
    }

    @Test
    fun `older items collapse into month sections`() {
        val rows = TimelineGrouper.rows(
            listOf(
                media("a.jpg", LocalDate.of(2026, 7, 30)),
                media("b.jpg", LocalDate.of(2026, 7, 2)),
                media("c.jpg", LocalDate.of(2025, 12, 25)),
            ),
            today = today,
            zone = zone,
        )
        assertEquals(
            listOf("July 2026" to 2, "December 2025" to 1),
            headers(rows).map { it.label to it.count },
        )
    }

    @Test
    fun `a file without exif date falls back to its mtime`() {
        val noExif = media("x.jpg", today).copy(
            takenAtEpochSeconds = null,
            mtime = today.minusDays(1).atStartOfDay(zone).toEpochSecond(),
        )
        val rows = TimelineGrouper.rows(listOf(noExif), today = today, zone = zone)
        assertEquals(listOf("Yesterday"), headers(rows).map { it.label })
    }

    @Test
    fun `media rows follow their header in input order`() {
        val rows = TimelineGrouper.rows(
            listOf(media("a.jpg", today), media("b.jpg", today)),
            today = today,
            zone = zone,
        )
        assertEquals(3, rows.size)
        assertEquals(
            listOf("a.jpg", "b.jpg"),
            rows.filterIsInstance<TimelineGrouper.Row.Media>().map { it.item.name },
        )
    }
}
