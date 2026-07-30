package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpReader
import app.skerry.shared.rdp.RdpRect
import app.skerry.shared.rdp.RdpWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The wrappers the graphics pipeline puts around an H.264 frame: which parts of the picture the
 * server redrew, and — in 4:4:4 — where one stream ends and the other begins.
 */
class AvcStreamTest {

    @Test
    fun `a 4 to 2 to 0 stream carries its regions and then the frame`() {
        val data = avc420Message(
            regions = listOf(RdpRect(16, 32, 48, 16)),
            bitstream = byteArrayOf(1, 2, 3, 4),
        )

        val stream = readAvc420Stream(RdpReader(data))

        assertEquals(listOf(RdpRect(16, 32, 48, 16)), stream.regions)
        assertEquals(listOf<Byte>(1, 2, 3, 4), stream.bitstream.toList())
    }

    @Test
    fun `region rectangles arrive as edges and become width and height`() {
        val data = avc420Message(regions = listOf(RdpRect(4, 8, 12, 20)), bitstream = ByteArray(0))

        val region = readAvc420Stream(RdpReader(data)).regions.single()

        assertEquals(RdpRect(4, 8, 12, 20), region, "left/top/right/bottom was not turned into a size")
    }

    @Test
    fun `a region list longer than the message is refused before it is allocated`() {
        val lying = RdpWriter(8).u32le(Int.MAX_VALUE).u32le(0).toByteArray()

        assertFailsWith<RdpProtocolException> { readAvc420Stream(RdpReader(lying)) }
    }

    @Test
    fun `a negative region count is refused`() {
        val lying = RdpWriter(8).u32le(-1).u32le(0).toByteArray()

        assertFailsWith<RdpProtocolException> { readAvc420Stream(RdpReader(lying)) }
    }

    @Test
    fun `both halves of a 4 to 4 to 4 message are split on the declared length`() {
        val luma = avc420Message(listOf(RdpRect(0, 0, 32, 16)), byteArrayOf(11, 12))
        val chroma = avc420Message(listOf(RdpRect(0, 0, 16, 16)), byteArrayOf(21, 22, 23))

        val streams = readAvc444Stream(avc444Message(contents = 0, first = luma, second = chroma))

        assertEquals(listOf<Byte>(11, 12), streams.luma?.bitstream?.toList())
        assertEquals(listOf(RdpRect(0, 0, 32, 16)), streams.luma?.regions)
        assertEquals(listOf<Byte>(21, 22, 23), streams.chroma?.bitstream?.toList())
        assertEquals(listOf(RdpRect(0, 0, 16, 16)), streams.chroma?.regions)
    }

    @Test
    fun `a 4 to 4 to 4 message may carry the luma alone`() {
        val luma = avc420Message(listOf(RdpRect(0, 0, 32, 16)), byteArrayOf(7, 8, 9))

        val streams = readAvc444Stream(avc444Message(contents = 1, first = luma, second = null))

        assertEquals(listOf<Byte>(7, 8, 9), streams.luma?.bitstream?.toList())
        assertNull(streams.chroma, "a luma-only message produced a chroma stream")
    }

    @Test
    fun `a 4 to 4 to 4 message may carry the chroma alone`() {
        val chroma = avc420Message(listOf(RdpRect(0, 0, 32, 16)), byteArrayOf(7, 8, 9))

        val streams = readAvc444Stream(avc444Message(contents = 2, first = chroma, second = null))

        assertEquals(listOf<Byte>(7, 8, 9), streams.chroma?.bitstream?.toList())
        assertNull(streams.luma, "a chroma-only message produced a luma stream")
    }

    @Test
    fun `a 4 to 4 to 4 message that carries neither half is refused`() {
        val stream = avc444Message(contents = 3, first = avc420Message(emptyList(), ByteArray(0)), second = null)

        assertFailsWith<RdpProtocolException> { readAvc444Stream(stream) }
    }

    @Test
    fun `a declared length shorter than the region list it counts is refused`() {
        val luma = avc420Message(listOf(RdpRect(0, 0, 32, 16)), byteArrayOf(1, 2))
        // The length covers the first stream including its region list; two bytes cannot.
        val header = RdpWriter(4).u32le(2).toByteArray()

        assertFailsWith<RdpProtocolException> { readAvc444Stream(header + luma) }
    }

    @Test
    fun `a declared length past the end of the message is refused`() {
        val luma = avc420Message(listOf(RdpRect(0, 0, 32, 16)), byteArrayOf(1, 2))
        val header = RdpWriter(4).u32le(luma.size + 64).toByteArray()

        assertFailsWith<RdpProtocolException> { readAvc444Stream(header + luma) }
    }
}
