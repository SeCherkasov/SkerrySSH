package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * BER and PER primitives, asserted against the encodings in the annotated connection sequence of
 * MS-RDPBCGR (section 4.1.3/4.1.4). Both are hand-rolled here rather than pulled from a general ASN.1
 * library: MCS uses a fixed handful of BER types, GCC a fixed handful of aligned-PER ones, and a
 * general codec would be more code than the subset — plus it would have to be taught the same
 * unsigned-integer quirk the wire actually carries (see [ber writes integers the way the wire does]).
 */
class BerPerTest {

    @Test
    fun `ber writes the application tag of Connect-Initial with a long length`() {
        val writer = RdpWriter()
        Ber.applicationTag(writer, tag = 101, length = 404)

        assertContentEquals(byteArrayOf(0x7F, 0x65, 0x82.toByte(), 0x01, 0x94.toByte()), writer.toByteArray())
    }

    @Test
    fun `ber lengths use the short form below 128 and the two-byte long form above`() {
        assertContentEquals(byteArrayOf(0x19), lengthBytes(25))
        assertContentEquals(byteArrayOf(0x7F), lengthBytes(127))
        assertContentEquals(byteArrayOf(0x81.toByte(), 0x80.toByte()), lengthBytes(128))
        assertContentEquals(byteArrayOf(0x82.toByte(), 0x01, 0x33), lengthBytes(307))
    }

    @Test
    fun `ber writes integers the way the wire does`() {
        // The dumps carry maxMCSPDUsize = 65535 as `02 02 ff ff`: unsigned, no leading zero. A
        // strictly signed BER encoder would emit `02 03 00 ff ff`, which is what a conformant server
        // sends back — so we write what the client on the wire writes and read both forms.
        assertContentEquals(byteArrayOf(0x02, 0x01, 0x22), Ber.integer(34))
        assertContentEquals(byteArrayOf(0x02, 0x02, 0xFF.toByte(), 0xFF.toByte()), Ber.integer(65535))
        assertContentEquals(byteArrayOf(0x02, 0x02, 0x04, 0x20), Ber.integer(1056))
        assertContentEquals(byteArrayOf(0x02, 0x01, 0x00), Ber.integer(0))
    }

    @Test
    fun `ber reads unsigned integers of any width the server chooses`() {
        assertEquals(34, Ber.readInteger(RdpReader(byteArrayOf(0x02, 0x01, 0x22))))
        assertEquals(65535, Ber.readInteger(RdpReader(byteArrayOf(0x02, 0x02, 0xFF.toByte(), 0xFF.toByte()))))
        // 65528 as a server sends it: an extra leading zero so the value stays positive.
        assertEquals(
            65528,
            Ber.readInteger(RdpReader(byteArrayOf(0x02, 0x03, 0x00, 0xFF.toByte(), 0xF8.toByte()))),
        )
    }

    @Test
    fun `ber rejects an integer wider than the values this protocol has`() {
        // A length field is the peer's claim; a 9-byte "integer" is either a bug or an attempt to
        // make us allocate and misparse, and no MCS field is wider than 4 bytes.
        assertFailsWith<RdpProtocolException> {
            Ber.readInteger(RdpReader(byteArrayOf(0x02, 0x09) + ByteArray(9)))
        }
    }

    @Test
    fun `ber octet strings, booleans and enumerated values match the dump`() {
        assertContentEquals(byteArrayOf(0x04, 0x01, 0x01), Ber.octetString(byteArrayOf(0x01)))
        assertContentEquals(byteArrayOf(0x01, 0x01, 0xFF.toByte()), Ber.boolean(true))
        assertEquals(0, Ber.readEnumerated(RdpReader(byteArrayOf(0x0A, 0x01, 0x00))))
    }

    @Test
    fun `ber readers refuse a tag they were not expecting`() {
        assertFailsWith<RdpProtocolException> {
            Ber.readInteger(RdpReader(byteArrayOf(0x04, 0x01, 0x22))) // an octet string, not an integer
        }
    }

    @Test
    fun `per lengths switch to the two-byte form at 128`() {
        assertContentEquals(byteArrayOf(0x2A), perLengthBytes(42))
        assertContentEquals(byteArrayOf(0x81.toByte(), 0x2A), perLengthBytes(298))
        assertEquals(42, Per.readLength(RdpReader(byteArrayOf(0x2A))))
        assertEquals(298, Per.readLength(RdpReader(byteArrayOf(0x81.toByte(), 0x2A))))
    }

    @Test
    fun `per encodes the T124 object identifier as the dump does`() {
        val writer = RdpWriter()
        Per.objectIdentifier(writer, intArrayOf(0, 0, 20, 124, 0, 1))

        assertContentEquals(byteArrayOf(0x05, 0x00, 0x14, 0x7C, 0x00, 0x01), writer.toByteArray())
    }

    @Test
    fun `per reads a user id back from its 1001 offset`() {
        // ConferenceCreateResponse::nodeID from the dump: 0x760a + 1001 = 31219.
        assertEquals(31219, Per.readUserId(RdpReader(byteArrayOf(0x76, 0x0A))))
    }

    private fun lengthBytes(length: Int): ByteArray =
        RdpWriter().also { Ber.length(it, length) }.toByteArray()

    private fun perLengthBytes(length: Int): ByteArray =
        RdpWriter().also { Per.length(it, length) }.toByteArray()
}
