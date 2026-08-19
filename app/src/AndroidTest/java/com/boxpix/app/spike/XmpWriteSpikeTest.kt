package com.boxpix.app.spike

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.options.PropertyOptions
import com.adobe.internal.xmp.options.SerializeOptions
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * SPIKE — can we write XMP dc:subject keywords into real S21+/iPhone JPEGs on
 * Android without degrading anything? Candidate: Commons Imaging (container
 * rewrite) + Adobe XMPCore (packet build/merge). Findings go to
 * files/spike-xmp-report.txt on the device; docs/spike-xmp.md is the verdict.
 * Assets are never modified — every pass works on a copy.
 */
@RunWith(AndroidJUnit4::class)
class XmpWriteSpikeTest {

    private val report = StringBuilder()

    data class Baseline(
        val size: Int,
        val pixelHash: String,
        val dateTimeOriginal: String?,
        val gpsLat: String?,
        val gpsLon: String?,
        val orientation: Int,
        val hasThumbnail: Boolean,
        val thumbHash: String?,
        val xmpPacket: String?,
    )

    @Test
    fun xmpWriteSpike() {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val names = testCtx.assets.list("")!!
            .filter { it.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "heic") }
            .sorted()
        report.appendLine("XMP write spike — ${names.size} real asset(s)")

        var failures = 0
        for (name in names) {
            try {
                val original = testCtx.assets.open(name).use { it.readBytes() }
                runFile(name, original)
            } catch (e: Throwable) {
                failures++
                report.appendLine("[$name] KO — ${e.javaClass.simpleName}: ${e.message}")
            }
            report.appendLine()
        }

        val out = File(appCtx.filesDir, "spike-xmp-report.txt")
        out.writeText(report.toString())
        Log.i("XmpSpike", "\n$report")
        assertTrue("Some files failed the spike:\n$report", failures == 0)
    }

    private fun runFile(name: String, original: ByteArray) {
        val base = capture(original)
        report.appendLine(
            "[$name] baseline: ${base.size} B, dto=${base.dateTimeOriginal}, " +
                "gps=${base.gpsLat != null}, orient=${base.orientation}, " +
                "exifThumb=${base.hasThumbnail}, existingXmp=${base.xmpPacket != null}",
        )

        // Pass 1: write "test" + "boxpix"
        val afterPass1 = writeKeywords(original, listOf("test", "boxpix"))
        val expected1 = existingKeywords(base.xmpPacket) + listOf("test", "boxpix")
        verify(name, "pass1", original, base, afterPass1, expected1)

        // Pass 2 on the already-tagged file: ADD "vacances", keep the rest
        val afterPass2 = writeKeywords(afterPass1, listOf("vacances"))
        val expected2 = expected1 + "vacances"
        verify(name, "pass2", original, base, afterPass2, expected2)
    }

    /** XMPCore merges into the existing packet (or a fresh one), keeping order. */
    private fun writeKeywords(source: ByteArray, keywords: List<String>): ByteArray {
        val existingPacket = extractXmpPacket(source)
        val meta = existingPacket
            ?.let { XMPMetaFactory.parseFromString(it) }
            ?: XMPMetaFactory.create()
        val current = existingKeywords(existingPacket)
        keywords.filterNot { it in current }.forEach { keyword ->
            meta.appendArrayItem(
                NS_DC,
                "subject",
                PropertyOptions().setArray(true),
                keyword,
                null,
            )
        }
        val xml = XMPMetaFactory.serializeToString(
            meta,
            SerializeOptions().setUseCompactFormat(true).setOmitPacketWrapper(true),
        )
        val out = ByteArrayOutputStream()
        JpegXmpRewriter().updateXmpXml(source, out, xml)
        return out.toByteArray()
    }

    private fun verify(
        name: String,
        pass: String,
        original: ByteArray,
        base: Baseline,
        rewritten: ByteArray,
        expectedKeywords: List<String>,
    ) {
        val after = capture(rewritten)
        val problems = mutableListOf<String>()

        // a. identical pixels
        if (after.pixelHash != base.pixelHash) problems += "PIXELS CHANGED"

        // b. original metadata intact
        if (after.dateTimeOriginal != base.dateTimeOriginal) problems += "DateTimeOriginal changed"
        if (after.gpsLat != base.gpsLat || after.gpsLon != base.gpsLon) problems += "GPS changed"
        if (after.orientation != base.orientation) problems += "orientation changed"
        if (after.hasThumbnail != base.hasThumbnail) problems += "EXIF thumbnail lost"
        if (after.thumbHash != base.thumbHash) problems += "EXIF thumbnail bytes changed"

        // c. keywords readable — independent raw-segment parse AND XMPCore
        val rawKeywords = rawSubjectKeywords(rewritten)
        val libKeywords = existingKeywords(extractXmpPacket(rewritten))
        expectedKeywords.forEach {
            if (it !in rawKeywords) problems += "keyword '$it' missing (raw parse)"
            if (it !in libKeywords) problems += "keyword '$it' missing (XMPCore parse)"
        }

        // d. size delta
        val delta = rewritten.size - original.size
        if (delta > 8 * 1024) problems += "size delta too big: $delta B"

        if (problems.isEmpty()) {
            report.appendLine(
                "[$name] $pass OK — keywords=$libKeywords, size delta=+$delta B",
            )
        } else {
            report.appendLine("[$name] $pass PROBLEMS: $problems")
            throw AssertionError("[$name] $pass: $problems")
        }
    }

    // Baseline capture

    private fun capture(bytes: ByteArray): Baseline {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw AssertionError("undecodable image")
        val pixelHash = bitmapHash(bitmap)
        bitmap.recycle()

        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val latLon = exif.latLong
        return Baseline(
            size = bytes.size,
            pixelHash = pixelHash,
            dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            gpsLat = latLon?.get(0)?.toString(),
            gpsLon = latLon?.get(1)?.toString(),
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1),
            hasThumbnail = exif.hasThumbnail(),
            thumbHash = exif.thumbnailBytes?.let(::md5),
            xmpPacket = extractXmpPacket(bytes),
        )
    }

    private fun bitmapHash(bitmap: Bitmap): String {
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        return md5(buffer.array())
    }

    // Independent XMP handling (no candidate lib involved)

    /** Walks the JPEG APP1 segments for the XMP packet — candidate-lib-free. */
    private fun extractXmpPacket(bytes: ByteArray): String? {
        var i = 2
        val header = "http://ns.adobe.com/xap/1.0/".toByteArray()
        while (i + 4 < bytes.size && bytes[i] == 0xFF.toByte()) {
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xDA) break
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (marker == 0xE1) {
                val start = i + 4
                if (bytes.size >= start + header.size &&
                    bytes.copyOfRange(start, start + header.size).contentEquals(header)
                ) {
                    return String(
                        bytes.copyOfRange(start + header.size + 1, i + 2 + length),
                        Charsets.UTF_8,
                    )
                }
            }
            i += 2 + length
        }
        return null
    }

    private fun rawSubjectKeywords(bytes: ByteArray): List<String> {
        val packet = extractXmpPacket(bytes) ?: return emptyList()
        val subject = Regex("<dc:subject>(.*?)</dc:subject>", RegexOption.DOT_MATCHES_ALL)
            .find(packet)?.groupValues?.get(1) ?: return emptyList()
        return Regex("<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL)
            .findAll(subject).map { it.groupValues[1].trim() }.toList()
    }

    private fun existingKeywords(packet: String?): List<String> {
        if (packet == null) return emptyList()
        val meta = XMPMetaFactory.parseFromString(packet)
        val count = meta.countArrayItems(NS_DC, "subject")
        return (1..count).map { meta.getArrayItem(NS_DC, "subject", it).value }
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val NS_DC = "http://purl.org/dc/elements/1.1/"
    }
}
