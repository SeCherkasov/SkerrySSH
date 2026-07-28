package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The MCS domain PDUs of the connection sequence (MS-RDPBCGR 2.2.1.5 – 2.2.1.9), checked against the
 * dumps in section 4.1. These are PER-encoded by hand: each is a fixed handful of bytes, and the
 * choice byte carries packed flags no generic encoder would produce more clearly.
 */
class McsPduTest {

    @Test
    fun `erect domain request matches the dump`() {
        assertContentEquals(hex("03 00 00 0c 02 f0 80 04 01 00 01 00"), Mcs.erectDomainRequest())
    }

    @Test
    fun `attach user request matches the dump`() {
        assertContentEquals(hex("03 00 00 08 02 f0 80 28"), Mcs.attachUserRequest())
    }

    @Test
    fun `attach user confirm yields the user channel id`() {
        val confirm = X224.dataPayload(hex("03 00 00 0b 02 f0 80 2e 00 00 06"))

        assertEquals(1007, Mcs.parseAttachUserConfirm(confirm))
    }

    @Test
    fun `a refused attach user confirm is an error, not a channel id`() {
        // result = rt-too-many-channels; the initiator field is absent, so reading on would be a lie.
        val refused = X224.dataPayload(hex("03 00 00 09 02 f0 80 2c 20"))

        assertFailsWith<RdpProtocolException> { Mcs.parseAttachUserConfirm(refused) }
    }

    @Test
    fun `channel join request names the initiator and the channel`() {
        assertContentEquals(
            hex("03 00 00 0c 02 f0 80 38 00 06 03 ef"),
            Mcs.channelJoinRequest(userId = 1007, channelId = 1007),
        )
    }

    @Test
    fun `channel join confirm returns the joined channel`() {
        val confirm = X224.dataPayload(hex("03 00 00 0f 02 f0 80 3e 00 00 06 03 ef 03 ef"))

        assertEquals(1007, Mcs.parseChannelJoinConfirm(confirm))
    }

    @Test
    fun `a refused channel join is reported`() {
        val refused = X224.dataPayload(hex("03 00 00 0f 02 f0 80 3e 10 00 06 03 ef 03 ef"))

        assertFailsWith<RdpProtocolException> { Mcs.parseChannelJoinConfirm(refused) }
    }

    @Test
    fun `send data request wraps a payload for a channel`() {
        val packet = Mcs.sendDataRequest(userId = 1007, channelId = 1003, payload = byteArrayOf(0x41, 0x42))

        assertContentEquals(hex("03 00 00 10 02 f0 80 64 00 06 03 eb 70 02 41 42"), packet)
    }

    @Test
    fun `a payload of 128 bytes or more takes the two-byte per length`() {
        val packet = Mcs.sendDataRequest(userId = 1007, channelId = 1003, payload = ByteArray(200))

        // 0x8000 | 200 = 0x80c8
        assertEquals("80 c8", packet.copyOfRange(13, 15).toHex())
        assertEquals(7 + SEND_DATA_OVERHEAD + 2 + 200, packet.size)
    }

    @Test
    fun `send data indication is split into its channel and payload`() {
        val indication = Mcs.parseDomainPdu(X224.dataPayload(hex("03 00 00 10 02 f0 80 68 00 06 03 eb 70 02 41 42")))

        val data = indication as McsDomainPdu.Data
        assertEquals(1003, data.channelId)
        assertContentEquals(byteArrayOf(0x41, 0x42), data.payload.rest())
    }

    @Test
    fun `a disconnect provider ultimatum is recognised rather than parsed as data`() {
        val pdu = Mcs.parseDomainPdu(X224.dataPayload(hex("03 00 00 09 02 f0 80 21 80")))

        // rn-user-requested (3), the reason in the spec's own disconnect dump.
        assertEquals(McsDomainPdu.Disconnect(reason = 3), pdu)
    }

    private companion object {
        /** choice + initiator + channel + flags, before the PER length determinant. */
        const val SEND_DATA_OVERHEAD = 6
    }

    @Test
    fun `an indication claiming more payload than it carries is refused`() {
        val truncated = X224.dataPayload(hex("03 00 00 10 02 f0 80 68 00 06 03 eb 70 40 41 42"))

        assertFailsWith<RdpProtocolException> {
            (Mcs.parseDomainPdu(truncated) as McsDomainPdu.Data).payload.rest()
        }
    }
}
