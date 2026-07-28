package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * RDP 8.0 bulk decompression, driven by bit streams assembled here from the token table so the
 * decoder is checked against the code the specification defines rather than against itself.
 */
class ZgfxTest {

    @Test
    fun `an uncompressed single segment is passed straight through`() {
        val message = singleSegment(compressed = false, "hello".encodeToByteArray())

        assertEquals("hello", Zgfx().decompress(message).decodeToString())
    }

    @Test
    fun `a multipart message is the concatenation of its segments`() {
        val body = RdpWriter(64).apply {
            u8(0xE1) // SEGMENTED_MULTIPART
            u16le(2)
            u32le(6)
            val first = rawSegment("abc")
            u32le(first.size)
            bytes(first)
            val second = rawSegment("def")
            u32le(second.size)
            bytes(second)
        }.toByteArray()

        assertEquals("abcdef", Zgfx().decompress(body).decodeToString())
    }

    @Test
    fun `literal tokens decode to their bytes`() {
        // Two literals: the one-bit prefix 0 followed by the byte itself.
        val bits = literal('A') + literal('B')

        assertEquals("AB", Zgfx().decompress(compressedSegment(bits)).decodeToString())
    }

    @Test
    fun `a match repeats bytes from the history, overlapping itself as it goes`() {
        // "AB", then a match two bytes back of the default length three: the copy reads bytes it is
        // still writing, which is what turns a two-byte history into "ABA".
        val bits = literal('A') + literal('B') + "10001" + "00010" + "0"

        assertEquals("ABABA", Zgfx().decompress(compressedSegment(bits)).decodeToString())
    }

    @Test
    fun `a longer match reads its length from the run of one bits`() {
        // Length coding: one 1 bit means "four or more", then two bits of offset — 4 + 2 = 6 here.
        val bits = literal('A') + literal('B') + "10001" + "00010" + "1" + "0" + "10"

        assertEquals("ABABABAB", Zgfx().decompress(compressedSegment(bits)).decodeToString())
    }

    @Test
    fun `a match distance of zero introduces an unencoded run`() {
        // The distance token with a zero value means the next 15 bits are a byte count and the run
        // itself follows on the next byte boundary.
        val bits = literal('A') + "10001" + "00000" + "000000000000010"
        val segment = compressedSegment(bits, trailing = "xy".encodeToByteArray())

        assertEquals("Axy", Zgfx().decompress(segment).decodeToString())
    }

    @Test
    fun `the history survives from one message to the next`() {
        val decompressor = Zgfx()
        decompressor.decompress(singleSegment(compressed = false, "AB".encodeToByteArray()))

        val second = compressedSegment(literal('C') + "10001" + "00011" + "0")

        // The match reaches three bytes back — past the start of this message and into the last one.
        assertEquals("CABC", decompressor.decompress(second).decodeToString())
    }

    @Test
    fun `an unknown segment descriptor is refused`() {
        assertFailsWith<RdpProtocolException> { Zgfx().decompress(byteArrayOf(0x42, 0x00)) }
    }

    @Test
    fun `a segment claiming more bytes than the message holds is refused`() {
        val body = RdpWriter(16).apply {
            u8(0xE1)
            u16le(1)
            u32le(10)
            u32le(9999)
        }.toByteArray()

        assertFailsWith<RdpProtocolException> { Zgfx().decompress(body) }
    }

    @Test
    fun `an unencoded run reaching past the segment is refused`() {
        val bits = literal('A') + "10001" + "00000" + "000000000001111"

        assertFailsWith<RdpProtocolException> { Zgfx().decompress(compressedSegment(bits)) }
    }

    // ---- building blocks ----

    /** The literal token: a single zero bit, then the byte. */
    private fun literal(value: Char): String =
        "0" + value.code.toString(2).padStart(8, '0')

    private fun rawSegment(text: String): ByteArray =
        byteArrayOf(0x04) + text.encodeToByteArray() // PACKET_COMPR_TYPE_RDP8, not compressed

    private fun singleSegment(compressed: Boolean, data: ByteArray): ByteArray =
        byteArrayOf(0xE0.toByte(), if (compressed) 0x24 else 0x04) + data

    /**
     * Wrap a bit string as a compressed single segment: the bits are packed MSB first and the
     * segment ends with a byte counting the padding bits after the last token. Bytes of an
     * unencoded run go in [trailing] — they end the segment on a byte edge, so the count is zero
     * and the bits that pad the token stream before them are simply skipped, as the encoder does.
     */
    private fun compressedSegment(bits: String, trailing: ByteArray = ByteArray(0)): ByteArray {
        val padding = (8 - bits.length % 8) % 8
        val padded = bits + "0".repeat(padding)
        val packed = ByteArray(padded.length / 8) { index ->
            padded.substring(index * 8, index * 8 + 8).toInt(2).toByte()
        }
        val trailingPadding = if (trailing.isEmpty()) padding else 0
        return singleSegment(compressed = true, packed + trailing + byteArrayOf(trailingPadding.toByte()))
    }
}
