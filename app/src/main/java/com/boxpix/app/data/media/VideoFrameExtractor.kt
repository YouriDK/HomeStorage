package com.boxpix.app.data.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Poster-frame extraction from a streamed video — abstracted so the worker's
 * queue logic stays JVM-testable (MediaMetadataRetriever is device-only).
 */
fun interface VideoFrameExtractor {
    fun extract(url: String, headers: Map<String, String>): Extraction?

    data class Extraction(val thumbWebp: ByteArray, val durationSeconds: Long?)
}

class AndroidVideoFrameExtractor @Inject constructor() : VideoFrameExtractor {

    override fun extract(url: String, headers: Map<String, String>): VideoFrameExtractor.Extraction? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, headers)
            val durationSeconds = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.div(1000)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(-1)
                ?: return null
            VideoFrameExtractor.Extraction(toWebpThumb(frame), durationSeconds)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun toWebpThumb(frame: Bitmap): ByteArray {
        val scale = THUMB_SIZE.toFloat() / maxOf(frame.width, frame.height)
        val thumb = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).toInt().coerceAtLeast(1),
                (frame.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            frame
        }
        val bytes = ByteArrayOutputStream().use { out ->
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            thumb.compress(format, THUMB_QUALITY, out)
            out.toByteArray()
        }
        if (thumb !== frame) thumb.recycle()
        frame.recycle()
        return bytes
    }

    private companion object {
        const val THUMB_SIZE = 512
        const val THUMB_QUALITY = 80
    }
}
