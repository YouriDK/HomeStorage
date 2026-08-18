package com.boxpix.app.data.fake

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Produces the fake library's "photos": real decodable JPEG bytes with a real
 * embedded EXIF capture date, so the M3 pipeline (range-read EXIF, downsample,
 * sidecar generation) is honestly exercised without a Freebox.
 */
fun interface FakeImageSynthesizer {
    fun jpegWithExif(seed: Int, takenAtEpochSeconds: Long): ByteArray
}

/** Android implementation: colored bitmap → JPEG → EXIF DateTimeOriginal. */
class AndroidFakeImageSynthesizer : FakeImageSynthesizer {

    private val cache = object : LinkedHashMap<Long, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > 128
    }

    private val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    @Synchronized
    override fun jpegWithExif(seed: Int, takenAtEpochSeconds: Long): ByteArray {
        val key = seed.toLong() shl 32 or (takenAtEpochSeconds and 0xFFFFFFFFL)
        cache[key]?.let { return it }

        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (abs(seed) % 360).toFloat()
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.55f)))
        val paint = Paint().apply {
            color = Color.HSVToColor(floatArrayOf((hue + 40f) % 360, 0.45f, 0.75f))
            strokeWidth = SIZE / 5f
        }
        canvas.drawLine(0f, SIZE.toFloat(), SIZE.toFloat(), 0f, paint)

        val plainJpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
        bitmap.recycle()

        // ExifInterface can only write to a file: round-trip through a temp file.
        val bytes = File.createTempFile("boxpix_fake", ".jpg").let { file ->
            try {
                file.writeBytes(plainJpeg)
                ExifInterface(file.absolutePath).apply {
                    setAttribute(
                        ExifInterface.TAG_DATETIME_ORIGINAL,
                        exifDateFormat.format(Date(takenAtEpochSeconds * 1000)),
                    )
                    saveAttributes()
                }
                file.readBytes()
            } finally {
                file.delete()
            }
        }
        cache[key] = bytes
        return bytes
    }

    private companion object {
        const val SIZE = 640
    }
}
