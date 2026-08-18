package com.boxpix.app.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

/**
 * Pure byte-level media work, abstracted so the reconciler stays JVM-testable:
 * the Android implementation uses ExifInterface + BitmapFactory, tests stub it.
 */
interface MediaProcessor {
    /** EXIF DateTimeOriginal as epoch seconds, or null when absent/unreadable. */
    fun readTakenAtEpochSeconds(imageBytes: ByteArray): Long?

    /** 512 px (longest side) WebP q80, EXIF orientation applied; null if undecodable. */
    fun makeThumbnail(imageBytes: ByteArray): ByteArray?
}

class AndroidMediaProcessor @Inject constructor() : MediaProcessor {

    override fun readTakenAtEpochSeconds(imageBytes: ByteArray): Long? = try {
        val exif = ExifInterface(ByteArrayInputStream(imageBytes))
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        raw?.let {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                .parse(it, ParsePosition(0))
                ?.time?.div(1000)
        }
    } catch (_: Exception) {
        null
    }

    override fun makeThumbnail(imageBytes: ByteArray): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        // Downsample while decoding: never hold the full-size bitmap in memory.
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= THUMB_SIZE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            ?: return@runCatching null

        val oriented = applyOrientation(decoded, imageBytes)
        val scale = THUMB_SIZE.toFloat() / maxOf(oriented.width, oriented.height)
        val thumb = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * scale).toInt().coerceAtLeast(1),
                (oriented.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            oriented
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
        if (thumb !== oriented) thumb.recycle()
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
        bytes
    }.getOrNull()

    private fun applyOrientation(bitmap: Bitmap, imageBytes: ByteArray): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(imageBytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val THUMB_SIZE = 512
        const val THUMB_QUALITY = 80
    }
}
