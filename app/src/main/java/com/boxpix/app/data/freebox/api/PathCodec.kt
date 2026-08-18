package com.boxpix.app.data.freebox.api

import java.util.Base64

/**
 * The filesystem API exchanges paths as base64 (standard alphabet, with padding),
 * e.g. fs/ls/RnJlZWJveC9WTXM= for "Freebox/VMs". The API asks clients to reuse the
 * encoded paths it returns as-is, so decoded strings are only for display and the
 * encoded form is what gets stored and sent back.
 */
object PathCodec {

    fun encode(path: String): String =
        Base64.getEncoder().encodeToString(path.toByteArray(Charsets.UTF_8))

    fun decode(encodedPath: String): String =
        String(Base64.getDecoder().decode(encodedPath), Charsets.UTF_8)

    /** Encoded form of the filesystem root, the listing that exposes the disks. */
    val ROOT: String = encode("/")
}
