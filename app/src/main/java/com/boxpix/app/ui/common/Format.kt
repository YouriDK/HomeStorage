package com.boxpix.app.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault())

fun formatDate(epochSeconds: Long): String = dateFormatter.format(Instant.ofEpochSecond(epochSeconds))

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(Locale.ENGLISH, bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(Locale.ENGLISH, bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.0f KB".format(Locale.ENGLISH, bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

fun formatDuration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
