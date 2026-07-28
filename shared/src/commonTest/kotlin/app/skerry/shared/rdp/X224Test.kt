package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Wire-level tests for the connection-establishment layer (MS-RDPBCGR 2.2.1.1/2.2.1.2): the byte
 * layouts are fixed by the spec, so they are asserted byte for byte rather than round-tripped.
 */
class X224Test {

    @Test
    fun `connection request carries tpkt, x224 CR and the negotiation block`() {
        val bytes = X224.connectionRequest(
            requestedProtocols = RdpSecurityProtocol.SSL or RdpSecurityProtocol.HYBRID,
            cookie = null,
        )

        assertEquals(
            listOf(
                // TPKT: version 3, reserved 0, length 19 (big-endian)
                0x03, 0x00, 0x00, 0x13,
                // X.224 CR: LI 14, CR|CDT 0xE0, DST-REF 0, SRC-REF 0, class 0
                0x0E, 0xE0, 0x00, 0x00, 0x00, 0x00, 0x00,
                // RDP_NEG_REQ: type 1, flags 0, length 8 (LE), requestedProtocols 3 (LE)
                0x01, 0x00, 0x08, 0x00, 0x03, 0x00, 0x00, 0x00,
            ),
            bytes.toUnsignedList(),
        )
    }

    @Test
    fun `connection request prefixes the routing cookie and counts it in both lengths`() {
        val bytes = X224.connectionRequest(requestedProtocols = RdpSecurityProtocol.SSL, cookie = "elton")

        val cookie = "Cookie: mstshash=elton\r\n".encodeToByteArray()
        assertEquals(4 + 7 + cookie.size + 8, bytes.size)
        // TPKT length covers the whole packet; the X.224 length indicator covers everything after itself.
        assertEquals(bytes.size, (bytes[2].toInt() and 0xFF shl 8) or (bytes[3].toInt() and 0xFF))
        assertEquals(bytes.size - 5, bytes[4].toInt() and 0xFF)
        assertEquals(cookie.toList(), bytes.copyOfRange(11, 11 + cookie.size).toList())
    }

    @Test
    fun `a cookie is only sent when it is safe to put on the wire`() {
        // The cookie is server-visible and CRLF-delimited: a newline in the user name would let the
        // profile inject a second header line, and an over-long one overflows the 255-byte X.224 LI.
        val injected = X224.connectionRequest(RdpSecurityProtocol.SSL, cookie = "elton\r\nCookie: x=y")
        val tooLong = X224.connectionRequest(RdpSecurityProtocol.SSL, cookie = "e".repeat(300))
        val plain = X224.connectionRequest(RdpSecurityProtocol.SSL, cookie = null)

        assertEquals(plain.toList(), injected.toList())
        assertEquals(plain.toList(), tooLong.toList())
    }

    @Test
    fun `a load balancer token replaces the user cookie verbatim`() {
        // A farm's broker reads its own token, not `Cookie: mstshash=`: the value goes on the wire
        // exactly as the .rdp file spelled it, only CRLF-terminated.
        val bytes = X224.connectionRequest(
            requestedProtocols = RdpSecurityProtocol.SSL,
            cookie = "elton",
            loadBalanceInfo = "tsv://MS Terminal Services Plugin.1.Employees",
        )

        val token = "tsv://MS Terminal Services Plugin.1.Employees\r\n".encodeToByteArray()
        assertEquals(4 + 7 + token.size + 8, bytes.size)
        assertEquals(bytes.size, (bytes[2].toInt() and 0xFF shl 8) or (bytes[3].toInt() and 0xFF))
        assertEquals(bytes.size - 5, bytes[4].toInt() and 0xFF)
        assertEquals(token.toList(), bytes.copyOfRange(11, 11 + token.size).toList())
    }

    @Test
    fun `an unsafe load balancer token falls back to the user cookie`() {
        val injected = X224.connectionRequest(
            RdpSecurityProtocol.SSL,
            cookie = "elton",
            loadBalanceInfo = "tsv://x\r\nCookie: mstshash=root",
        )

        assertEquals(
            X224.connectionRequest(RdpSecurityProtocol.SSL, cookie = "elton").toList(),
            injected.toList(),
        )
    }

    @Test
    fun `connection confirm reports the protocol the server selected`() {
        val confirm = byteArrayOf(
            0x03, 0x00, 0x00, 0x13,
            0x0E, 0xD0.toByte(), 0x00, 0x00, 0x12, 0x34, 0x00,
            // RDP_NEG_RSP: type 2, flags EXTENDED_CLIENT_DATA_SUPPORTED|DYNVC_GFX, length 8, protocol HYBRID
            0x02, 0x03, 0x08, 0x00, 0x02, 0x00, 0x00, 0x00,
        )

        val response = X224.parseConnectionConfirm(confirm)

        assertEquals(RdpSecurityProtocol.HYBRID, response.selectedProtocol)
        assertTrue(response.supportsGraphicsPipeline)
        assertTrue(response.supportsExtendedClientData)
    }

    @Test
    fun `a confirm without a negotiation block means the server stayed on standard rdp security`() {
        val confirm = byteArrayOf(
            0x03, 0x00, 0x00, 0x0B,
            0x06, 0xD0.toByte(), 0x00, 0x00, 0x12, 0x34, 0x00,
        )

        val response = X224.parseConnectionConfirm(confirm)

        assertEquals(RdpSecurityProtocol.RDP, response.selectedProtocol)
        assertTrue(!response.supportsGraphicsPipeline)
    }

    @Test
    fun `a negotiation failure names the reason instead of dropping the connection blindly`() {
        val confirm = byteArrayOf(
            0x03, 0x00, 0x00, 0x13,
            0x0E, 0xD0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
            // RDP_NEG_FAILURE: type 3, flags 0, length 8, HYBRID_REQUIRED_BY_SERVER (5)
            0x03, 0x00, 0x08, 0x00, 0x05, 0x00, 0x00, 0x00,
        )

        val failure = assertFailsWith<RdpNegotiationException> { X224.parseConnectionConfirm(confirm) }

        assertEquals(RdpNegotiationFailure.HYBRID_REQUIRED_BY_SERVER, failure.reason)
    }

    @Test
    fun `an unknown failure code is still reported as a negotiation failure`() {
        val confirm = byteArrayOf(
            0x03, 0x00, 0x00, 0x13,
            0x0E, 0xD0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
            0x03, 0x00, 0x08, 0x00, 0x63, 0x00, 0x00, 0x00,
        )

        val failure = assertFailsWith<RdpNegotiationException> { X224.parseConnectionConfirm(confirm) }

        assertEquals(null, failure.reason)
        assertTrue(failure.message!!.contains("99"))
    }

    @Test
    fun `a truncated confirm is rejected rather than read past its end`() {
        // Claims a negotiation block but carries only half of it.
        val truncated = byteArrayOf(
            0x03, 0x00, 0x00, 0x0F,
            0x0A, 0xD0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x08, 0x00,
        )

        assertFailsWith<RdpProtocolException> { X224.parseConnectionConfirm(truncated) }
    }

    @Test
    fun `a confirm that is not a connection confirm is rejected`() {
        val disconnectRequest = byteArrayOf(
            0x03, 0x00, 0x00, 0x0B,
            0x06, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
        )

        assertFailsWith<RdpProtocolException> { X224.parseConnectionConfirm(disconnectRequest) }
    }

    @Test
    fun `the data header wraps a payload in tpkt and x224 DT`() {
        val header = X224.dataHeader(payloadLength = 5)

        assertEquals(listOf(0x03, 0x00, 0x00, 0x0C, 0x02, 0xF0, 0x80), header.toUnsignedList())
    }

    private fun ByteArray.toUnsignedList(): List<Int> = map { it.toInt() and 0xFF }
}
