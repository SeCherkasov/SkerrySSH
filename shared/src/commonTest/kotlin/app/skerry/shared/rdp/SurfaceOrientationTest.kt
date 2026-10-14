package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertEquals

class SurfaceOrientationTest {
    @Test
    fun `legacy raw surface rows land bottom up at every requested depth`() {
        for ((depth, data) in listOf(
            16 to byteArrayOf(0xE0.toByte(), 7, 0, 0xF8.toByte()),
            24 to byteArrayOf(0, 0xFF.toByte(), 0, 0, 0, 0xFF.toByte()),
            32 to byteArrayOf(0, 0xFF.toByte(), 0, 0, 0, 0, 0xFF.toByte(), 0),
        )) {
            val pixels = RdpCodecs().decode(0, data, 1, 2, depth)!!.pixels
            assertEquals(0xFFFF0000.toInt(), pixels[0], "depth $depth")
            assertEquals(0xFF00FF00.toInt(), pixels[1], "depth $depth")
        }
    }

    @Test
    fun `legacy NSCodec surface rows land bottom up`() {
        val data = RdpWriter(26).apply {
            u32le(2).u32le(2).u32le(2).u32le(0)
            u8(1).u8(0).u16le(0)
            bytes(byteArrayOf(0x20, 0x60, 0, 0, 0, 0))
        }.toByteArray()
        val pixels = RdpCodecs().decode(ClientCapabilities.CODEC_ID_NSCODEC, data, 1, 2, 32)!!.pixels
        assertEquals(0xFF606060.toInt(), pixels[0])
        assertEquals(0xFF202020.toInt(), pixels[1])
    }

    @Test
    fun `a short raw surface clears the missing top row after scratch reuse`() {
        val codecs = RdpCodecs()
        codecs.decode(0, ByteArray(16) { 0xFF.toByte() }, 2, 2, 32)
        val pixels = codecs.decode(0, byteArrayOf(0, 0, 0xFF.toByte(), 0), 2, 2, 32)!!.pixels
        assertEquals(listOf(0, 0, 0xFFFF0000.toInt(), 0), pixels.take(4))
    }
}
