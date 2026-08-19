package com.boxpix.app.ui.gallery

import com.boxpix.app.ui.viewer.MediaRef
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Buckets the index into the design's timeline sections: recent days one by one
 * ("Today · 18 August", "Yesterday", "Saturday 15 August"), then whole months
 * ("July 2026"). Pure and clock-injected, so it is unit-tested.
 */
object TimelineGrouper {

    sealed interface Row {
        data class Header(val label: String, val count: Int) : Row
        data class Media(val item: MediaRef) : Row
    }

    private sealed interface Bucket {
        data class Day(val date: LocalDate) : Bucket
        data class Month(val month: YearMonth) : Bucket
    }

    fun rows(
        items: List<MediaRef>,
        today: LocalDate,
        zone: ZoneId,
        locale: Locale = Locale.ENGLISH,
    ): List<Row> {
        val dayCutoff = today.minusDays(6)
        val grouped = LinkedHashMap<Bucket, MutableList<MediaRef>>()
        for (item in items) {
            val date = LocalDate.ofInstant(
                java.time.Instant.ofEpochSecond(item.takenAtEpochSeconds ?: item.mtime),
                zone,
            )
            val bucket = if (!date.isBefore(dayCutoff) && !date.isAfter(today)) {
                Bucket.Day(date)
            } else {
                Bucket.Month(YearMonth.from(date))
            }
            grouped.getOrPut(bucket) { mutableListOf() } += item
        }

        val rows = mutableListOf<Row>()
        for ((bucket, bucketItems) in grouped) {
            rows += Row.Header(label(bucket, today, locale), bucketItems.size)
            bucketItems.forEach { rows += Row.Media(it) }
        }
        return rows
    }

    private fun label(bucket: Bucket, today: LocalDate, locale: Locale): String = when (bucket) {
        is Bucket.Day -> when (bucket.date) {
            today -> "Today · " + bucket.date.format(DateTimeFormatter.ofPattern("d MMMM", locale))
            today.minusDays(1) -> "Yesterday"
            else -> bucket.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))
        }
        is Bucket.Month -> bucket.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
    }
}
