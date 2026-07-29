package app.skerry.shared.rdp

import app.skerry.shared.graphics.RemoteFramebuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Client PDUs the server has to opt into (MS-RDPBCGR 2.2.11.2 / 2.2.11.3): sending one that was not
 * advertised is a protocol violation, and a Windows host answers it by dropping the connection.
 */
class OptionalClientPdusTest {

    private fun capabilities(refreshRect: Boolean, suppressOutput: Boolean) = ServerCapabilities(
        shareId = 0x10001,
        desktopWidth = 1024,
        desktopHeight = 768,
        preferredBitsPerPixel = 32,
        desktopResizeSupported = false,
        refreshRectSupported = refreshRect,
        suppressOutputSupported = suppressOutput,
        fastPathOutputSupported = true,
        noBitmapCompressionHeader = false,
        surfaceCommandsSupported = false,
        frameAcknowledgeSupported = false,
        maxRequestSize = 65535,
        supportedCodecs = emptyList(),
    )

    private fun codec(
        caps: ServerCapabilities,
        written: MutableList<ByteArray>,
        incoming: ByteArray = ByteArray(0),
    ): RdpSessionCodec {
        var offset = 0
        return RdpSessionCodec(
            source = { dst, dstOffset, len ->
                if (offset + len > incoming.size) error("the test fixture ran out of bytes")
                incoming.copyInto(dst, dstOffset, offset, offset + len)
                offset += len
            },
            sink = { bytes -> written += bytes },
            framebuffer = RemoteFramebuffer(caps.desktopWidth, caps.desktopHeight),
            state = RdpSessionState(userId = 1007, ioChannelId = 1003, channels = emptyMap(), capabilities = caps),
            settings = RdpClientSettings(
                desktopWidth = caps.desktopWidth,
                desktopHeight = caps.desktopHeight,
                clientName = "Skerry",
                selectedProtocol = 1,
            ),
            logon = RdpLogonInfo(domain = "", username = "u"),
        )
    }

    /** A fast-path packet carrying one drawing-orders update, which this client cannot execute. */
    private fun ordersPacket(): ByteArray {
        val update = RdpWriter(8).apply {
            u8(0) // updateCode 0 = orders, single fragment, uncompressed
            u16le(1)
            u8(1) // numberOrders, and nothing this client reads past it
        }.toByteArray()
        val total = update.size + 3
        return RdpWriter(total).apply {
            u8(0) // action = fast-path output
            u16be(total or 0x8000)
            bytes(update)
        }.toByteArray()
    }

    @Test
    fun a_refresh_declares_the_length_of_the_whole_packet() = runTest {
        // Windows checks this one PDU against its declared length and ends the session with
        // ERRINFO_INVALIDREFRESHRECTPDU (0x10D1) when the two disagree. uncompressedLength counts
        // the whole packet — Share Control Header included — not just what follows it.
        val written = mutableListOf<ByteArray>()
        codec(capabilities(refreshRect = true, suppressOutput = true), written)
            .requestRefresh(listOf(RdpRect(0, 0, 1024, 768)))

        // Past the MCS Send Data Request header to the Share Control PDU it carries.
        val pdu = written.single()
        val share = RdpReader(pdu, pdu.size - REFRESH_RECT_PDU_SIZE)
        val totalLength = share.u16le()
        share.skip(4) // pduType, pduSource
        share.skip(4) // shareId
        share.skip(2) // pad1, streamId
        assertEquals(totalLength, share.u16le(), "uncompressedLength does not cover the whole packet")
        assertEquals(REFRESH_RECT_PDU_SIZE, totalLength, "the Share Control PDU declares its own size")
    }

    @Test
    fun a_refresh_is_not_sent_to_a_server_that_did_not_advertise_it() = runTest {
        val written = mutableListOf<ByteArray>()
        codec(capabilities(refreshRect = false, suppressOutput = true), written)
            .requestRefresh(listOf(RdpRect(0, 0, 1024, 768)))

        assertTrue(written.isEmpty())
    }

    @Test
    fun a_refresh_is_sent_when_the_server_advertised_it() = runTest {
        val written = mutableListOf<ByteArray>()
        codec(capabilities(refreshRect = true, suppressOutput = true), written)
            .requestRefresh(listOf(RdpRect(0, 0, 1024, 768)))

        assertEquals(1, written.size)
    }

    @Test
    fun drawing_orders_are_skipped_and_the_screen_asked_for_again() = runTest {
        val written = mutableListOf<ByteArray>()
        val session = codec(capabilities(refreshRect = true, suppressOutput = true), written, ordersPacket())

        assertTrue(session.readMessage().isEmpty(), "orders carry nothing the UI can act on")
        assertEquals(1, written.size, "the pixels the orders drew have to be asked for as bitmaps")
        // The refresh covers the whole desktop, as inclusive right/bottom coordinates.
        val pdu = written.single()
        val rect = RdpReader(pdu, pdu.size - 8)
        assertEquals(listOf(0, 0, 1023, 767), listOf(rect.u16le(), rect.u16le(), rect.u16le(), rect.u16le()))
    }

    @Test
    fun a_second_orders_update_does_not_pile_up_another_repaint() = runTest {
        // A server that ignores the negotiation once will do it every frame; one full-screen repaint
        // may be outstanding at a time, or the answer to a broken server is a traffic storm.
        val written = mutableListOf<ByteArray>()
        val session = codec(
            capabilities(refreshRect = true, suppressOutput = true),
            written,
            ordersPacket() + ordersPacket(),
        )

        session.readMessage()
        session.readMessage()

        assertEquals(1, written.size)
    }

    @Test
    fun output_suppression_follows_the_same_rule() = runTest {
        val silent = mutableListOf<ByteArray>()
        codec(capabilities(refreshRect = true, suppressOutput = false), silent).setOutputVisible(false)
        assertTrue(silent.isEmpty())

        val allowed = mutableListOf<ByteArray>()
        codec(capabilities(refreshRect = true, suppressOutput = true), allowed).setOutputVisible(false)
        assertEquals(1, allowed.size)
    }

    private companion object {
        /** Share Control (6) + Share Data (12) + numberOfAreas with padding (4) + one rectangle (8). */
        const val REFRESH_RECT_PDU_SIZE = 30
    }
}
