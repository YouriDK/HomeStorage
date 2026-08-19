package com.boxpix.app.data.media

import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.options.PropertyOptions
import com.adobe.internal.xmp.options.SerializeOptions
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * dc:subject write-through, exactly per the spike contract (docs/spike-xmp.md):
 * XMPCore merges into the existing packet (other properties preserved, keywords
 * deduplicated, never removed), Commons Imaging rewrites ONLY the XMP segment —
 * pixels, EXIF, thumbnails and proprietary segments stay byte-identical.
 * JPEG only.
 */
@Singleton
class XmpTagWriter @Inject constructor() {

    /** Returns the rewritten JPEG bytes, or null if the container can't be processed. */
    fun withKeywords(source: ByteArray, keywords: List<String>): ByteArray? = try {
        val xml = mergeKeywords(XmpPackets.extract(source), keywords)
        val out = ByteArrayOutputStream()
        JpegXmpRewriter().updateXmpXml(source, out, xml)
        out.toByteArray()
    } catch (_: Exception) {
        null
    }

    /** Pure packet merge — existing properties kept, keywords appended, deduped. */
    fun mergeKeywords(existingPacket: String?, keywords: List<String>): String {
        val meta = existingPacket
            ?.let { XMPMetaFactory.parseFromString(it) }
            ?: XMPMetaFactory.create()
        val current = readKeywords(existingPacket)
        keywords.filterNot { keyword -> current.any { it.equals(keyword, ignoreCase = true) } }
            .forEach { keyword ->
                meta.appendArrayItem(NS_DC, "subject", PropertyOptions().setArray(true), keyword, null)
            }
        return XMPMetaFactory.serializeToString(
            meta,
            SerializeOptions().setUseCompactFormat(true).setOmitPacketWrapper(true),
        )
    }

    fun readKeywords(packet: String?): List<String> {
        if (packet == null) return emptyList()
        val meta = XMPMetaFactory.parseFromString(packet)
        val count = meta.countArrayItems(NS_DC, "subject")
        return (1..count).map { meta.getArrayItem(NS_DC, "subject", it).value }
    }

    private companion object {
        const val NS_DC = "http://purl.org/dc/elements/1.1/"
    }
}
