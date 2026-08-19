package com.boxpix.app.data.media

/**
 * Raw JPEG segment walking for the XMP APP1 packet — candidate-lib-free, proven
 * by the spike (docs/spike-xmp.md) against real S21+/iPhone files.
 */
object XmpPackets {

    private val HEADER = "http://ns.adobe.com/xap/1.0/".toByteArray()

    /** The XMP packet of a JPEG, or null when absent. */
    fun extract(bytes: ByteArray): String? {
        var i = 2
        while (i + 4 < bytes.size && bytes[i] == 0xFF.toByte()) {
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xDA) break
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (marker == 0xE1) {
                val start = i + 4
                if (bytes.size >= start + HEADER.size &&
                    bytes.copyOfRange(start, start + HEADER.size).contentEquals(HEADER)
                ) {
                    return String(
                        bytes.copyOfRange(start + HEADER.size + 1, i + 2 + length),
                        Charsets.UTF_8,
                    )
                }
            }
            i += 2 + length
        }
        return null
    }
}
