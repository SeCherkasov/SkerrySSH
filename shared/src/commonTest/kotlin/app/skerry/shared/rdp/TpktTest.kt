package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Framing tests for [Tpkt]: the server interleaves TPKT-framed slow-path packets and fast-path
 * updates on one socket, and only the first byte tells them apart — so the reader is driven here
 * over a source that hands out bytes in awkward chunks, the way a real socket does.
 */
class TpktTest {

    @Test
    fun `reads a whole tpkt packet by its declared length`() = runTest {
        val packet = byteArrayOf(0x03, 0x00, 0x00, 0x08, 0x11, 0x22, 0x33, 0x44)
        val source = ChunkedSource(packet, chunk = 1)

        assertContentEquals(packet, Tpkt.readPacket(source))
    }

    @Test
    fun `reads a fast-path update with a one-byte length`() = runTest {
        // action = FASTPATH_OUTPUT_ACTION_FASTPATH (0), one-byte length 6
        val packet = byteArrayOf(0x00, 0x06, 0x01, 0x02, 0x03, 0x04)

        assertContentEquals(packet, Tpkt.readPacket(ChunkedSource(packet, chunk = 3)))
    }

    @Test
    fun `reads a fast-path update with a two-byte length`() = runTest {
        val body = ByteArray(300) { (it and 0xFF).toByte() }
        val total = 3 + body.size
        val packet = byteArrayOf(0x00, (0x80 or (total shr 8)).toByte(), (total and 0xFF).toByte()) + body

        val read = Tpkt.readPacket(ChunkedSource(packet, chunk = 64))

        assertEquals(total, read.size)
        assertContentEquals(packet, read)
    }

    @Test
    fun `fast-path detection keys on the action bits, not on a guess`() {
        assertTrue(Tpkt.isFastPath(0x00))
        assertTrue(Tpkt.isFastPath(0x84)) // encryption + secure-checksum flags set, action still 0
        assertTrue(!Tpkt.isFastPath(0x03)) // TPKT version 3
    }

    @Test
    fun `an oversized length is refused before the buffer is allocated`() = runTest {
        val header = byteArrayOf(0x03, 0x00, 0xFF.toByte(), 0xFF.toByte())

        assertFailsWith<RdpProtocolException> { Tpkt.readPacket(ChunkedSource(header, chunk = 4)) }
    }

    @Test
    fun `a length that cannot even cover its own header is refused`() = runTest {
        val tpkt = byteArrayOf(0x03, 0x00, 0x00, 0x02)
        val fastPath = byteArrayOf(0x00, 0x01)

        assertFailsWith<RdpProtocolException> { Tpkt.readPacket(ChunkedSource(tpkt, chunk = 4)) }
        assertFailsWith<RdpProtocolException> { Tpkt.readPacket(ChunkedSource(fastPath, chunk = 2)) }
    }

    @Test
    fun `a packet whose declared length disagrees with its size is rejected`() {
        val lying = byteArrayOf(0x03, 0x00, 0x00, 0x20, 0x01, 0x02)

        assertFailsWith<RdpProtocolException> { Tpkt.reader(lying) }
    }

    @Test
    fun `data payload starts after the x224 data header`() {
        val packet = byteArrayOf(0x03, 0x00, 0x00, 0x09, 0x02, 0xF0.toByte(), 0x80.toByte(), 0x41, 0x42)

        val payload = X224.dataPayload(packet)

        assertContentEquals(byteArrayOf(0x41, 0x42), payload.rest())
    }

    /** Hands out [chunk] bytes at a time, so decoders can't accidentally rely on one big read. */
    private class ChunkedSource(private val data: ByteArray, private val chunk: Int) : RdpSource {
        private var pos = 0

        override suspend fun readFully(dst: ByteArray, offset: Int, len: Int) {
            var written = 0
            while (written < len) {
                if (pos >= data.size) throw RdpProtocolException("end of stream")
                val n = minOf(chunk, len - written, data.size - pos)
                data.copyInto(dst, offset + written, pos, pos + n)
                pos += n
                written += n
            }
        }
    }
}
