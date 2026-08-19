package com.boxpix.app.data.media

import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.options.PropertyOptions
import com.adobe.internal.xmp.options.SerializeOptions
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** What the write-through embeds: additive keywords, plus explicit corrections. */
data class XmpMetadata(
    val keywords: List<String> = emptyList(),
    /** Manual capture date — overwrites exif:DateTimeOriginal / photoshop:DateCreated. */
    val takenAtEpochSeconds: Long? = null,
    /** Free-text place — overwrites Iptc4xmpCore:Location. */
    val location: String? = null,
) {
    val isEmpty: Boolean
        get() = keywords.isEmpty() && takenAtEpochSeconds == null && location == null
}

/**
 * XMP write-through, exactly per the spike contract (docs/spike-xmp.md):
 * XMPCore merges into the existing packet (other properties preserved, keywords
 * deduplicated, never removed), Commons Imaging rewrites ONLY the XMP segment —
 * pixels, EXIF, thumbnails and proprietary segments stay byte-identical.
 * JPEG only. Keywords are additive; date and location are deliberate
 * corrections, so those properties are overwritten.
 */
@Singleton
class XmpTagWriter @Inject constructor() {

    /** Returns the rewritten JPEG bytes, or null if the container can't be processed. */
    fun withKeywords(source: ByteArray, keywords: List<String>): ByteArray? =
        withMetadata(source, XmpMetadata(keywords = keywords))

    /** Returns the rewritten JPEG bytes, or null if the container can't be processed. */
    fun withMetadata(source: ByteArray, metadata: XmpMetadata): ByteArray? = try {
        val xml = mergeMetadata(XmpPackets.extract(source), metadata)
        val out = ByteArrayOutputStream()
        JpegXmpRewriter().updateXmpXml(source, out, xml)
        out.toByteArray()
    } catch (_: Exception) {
        null
    }

    /** Pure packet merge — existing properties kept, keywords appended, deduped. */
    fun mergeKeywords(existingPacket: String?, keywords: List<String>): String =
        mergeMetadata(existingPacket, XmpMetadata(keywords = keywords))

    fun mergeMetadata(existingPacket: String?, metadata: XmpMetadata): String {
        val meta = existingPacket
            ?.let { XMPMetaFactory.parseFromString(it) }
            ?: XMPMetaFactory.create()
        val current = readKeywords(existingPacket)
        metadata.keywords
            .filterNot { keyword -> current.any { it.equals(keyword, ignoreCase = true) } }
            .forEach { keyword ->
                meta.appendArrayItem(NS_DC, "subject", PropertyOptions().setArray(true), keyword, null)
            }
        metadata.takenAtEpochSeconds?.let { epochSeconds ->
            val iso = LocalDateTime.ofEpochSecond(epochSeconds, 0, ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            meta.setProperty(NS_EXIF, "DateTimeOriginal", iso)
            meta.setProperty(NS_PHOTOSHOP, "DateCreated", iso)
        }
        metadata.location?.let { meta.setProperty(NS_IPTC_CORE, "Location", it) }
        return XMPMetaFactory.serializeToString(
            meta,
            SerializeOptions().setUseCompactFormat(true).setOmitPacketWrapper(true),
        )
    }

    fun readTakenAt(packet: String?): String? =
        packet?.let { XMPMetaFactory.parseFromString(it).getPropertyString(NS_EXIF, "DateTimeOriginal") }

    fun readLocation(packet: String?): String? =
        packet?.let { XMPMetaFactory.parseFromString(it).getPropertyString(NS_IPTC_CORE, "Location") }

    fun readKeywords(packet: String?): List<String> {
        if (packet == null) return emptyList()
        val meta = XMPMetaFactory.parseFromString(packet)
        val count = meta.countArrayItems(NS_DC, "subject")
        return (1..count).map { meta.getArrayItem(NS_DC, "subject", it).value }
    }

    private companion object {
        const val NS_DC = "http://purl.org/dc/elements/1.1/"
        const val NS_EXIF = "http://ns.adobe.com/exif/1.0/"
        const val NS_PHOTOSHOP = "http://ns.adobe.com/photoshop/1.0/"
        const val NS_IPTC_CORE = "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/"
    }
}
