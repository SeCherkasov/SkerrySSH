package app.skerry.shared.rdp

import app.skerry.shared.graphics.RemoteFramebuffer
import app.skerry.shared.rdp.egfx.GraphicsCodecs
import kotlin.test.Test
import kotlin.test.assertEquals

class BitmapOrientationTest {
    @Test
    fun `legacy planar bitmap rows land bottom up`() {
        val framebuffer = RemoteFramebuffer(1, 2)
        val data = byteArrayOf(0x20, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0)
        val body = RdpWriter(32).apply {
            u16le(1)
            u16le(0).u16le(0).u16le(0).u16le(1)
            u16le(1).u16le(2).u16le(32).u16le(0x0401).u16le(data.size)
            bytes(data)
        }.toByteArray()
        BitmapUpdate.apply(RdpReader(body), framebuffer, null, DroppedGraphics())
        assertEquals(0xFFFF0000.toInt(), framebuffer.pixels[0])
        assertEquals(0xFF00FF00.toInt(), framebuffer.pixels[1])
    }

    @Test
    fun `graphics pipeline planar rows keep their top down order`() {
        val data = byteArrayOf(0x20, 0xFF.toByte(), 0, 0, 0xFF.toByte(), 0, 0)
        val image = GraphicsCodecs().decode(GraphicsCodecs.CODEC_PLANAR, data, 1, 2)!!
        assertEquals(0xFFFF0000.toInt(), image.pixels[0])
        assertEquals(0xFF00FF00.toInt(), image.pixels[1])
    }
}
