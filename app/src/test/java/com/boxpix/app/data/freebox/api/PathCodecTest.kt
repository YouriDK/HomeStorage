package com.boxpix.app.data.freebox.api

import org.junit.Assert.assertEquals
import org.junit.Test

class PathCodecTest {

    @Test
    fun `encodes the documented example`() {
        // Example from the official fs API documentation.
        assertEquals("RnJlZWJveC9WTXM=", PathCodec.encode("Freebox/VMs"))
    }

    @Test
    fun `root is the encoded slash`() {
        assertEquals("Lw==", PathCodec.ROOT)
    }

    @Test
    fun `decode inverts encode`() {
        val path = "Disque 1/Photos/Été 2026/plage & mer.jpg"
        assertEquals(path, PathCodec.decode(PathCodec.encode(path)))
    }

    @Test
    fun `decodes utf8 paths returned by the box`() {
        assertEquals("Freebox/VMs", PathCodec.decode("RnJlZWJveC9WTXM="))
    }
}
