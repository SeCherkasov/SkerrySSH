package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Dynamic virtual channels (MS-RDPEDYC): negotiation, opening by name, fragmentation both ways. */
class DynamicChannelsTest {

    private val sent = mutableListOf<ByteArray>()
    private val received = mutableListOf<ByteArray>()
    private val channels = DynamicChannels(send = { data -> sent.add(data) })

    private fun registerGraphics() = channels.register(
        CHANNEL_NAME,
        object : DynamicChannelHandler {
            override suspend fun onOpen() {
                opened = true
            }

            override suspend fun onMessage(data: ByteArray) {
                received.add(data)
            }
        },
    )

    private var opened = false

    @Test
    fun `the version the server offers is echoed back`() = runTest {
        channels.onData(RdpWriter(8).u8(0x50).u8(0).u16le(3).u16le(0).u16le(0).toByteArray())

        assertContentEquals(byteArrayOf(0x50, 0x00, 0x03, 0x00), sent.single())
    }

    @Test
    fun `a channel this client speaks is accepted`() = runTest {
        registerGraphics()

        channels.onData(createRequest(channelId = 7, name = CHANNEL_NAME))

        val response = sent.single()
        assertEquals(0x10, response[0].toInt() and 0xFF, "create response header")
        assertEquals(7, response[1].toInt())
        assertEquals(0, statusOf(response))
        assertTrue(opened, "the channel's owner was not told it may speak")
    }

    @Test
    fun `a server opening channels without end is refused once the client is full`() = runTest {
        registerGraphics()

        // Every accepted channel is a reassembly buffer the server decides the size of, so the
        // number of them cannot be the server's to choose without limit either.
        repeat(64) { id -> channels.onData(createRequest(channelId = id, name = CHANNEL_NAME)) }
        sent.clear()
        channels.onData(createRequest(channelId = 64, name = CHANNEL_NAME))

        assertTrue(statusOf(sent.single()) != 0, "an unbounded number of open channels was accepted")
    }

    @Test
    fun `a channel this client does not speak is refused rather than ignored`() = runTest {
        channels.onData(createRequest(channelId = 7, name = "Microsoft::Windows::RDS::Telemetry"))

        assertEquals(0xC0000225.toInt(), statusOf(sent.single()))
    }

    @Test
    fun `a message split across PDUs reaches the handler whole`() = runTest {
        registerGraphics()
        channels.onData(createRequest(channelId = 7, name = CHANNEL_NAME))

        channels.onData(RdpWriter(16).u8(0x24).u8(7).u16le(6).bytes("abc".encodeToByteArray()).toByteArray())
        assertTrue(received.isEmpty(), "delivered before the message was complete")

        channels.onData(RdpWriter(16).u8(0x30).u8(7).bytes("def".encodeToByteArray()).toByteArray())

        assertEquals("abcdef", received.single().decodeToString())
    }

    @Test
    fun `a compressed message is decompressed before the handler sees it`() = runTest {
        registerGraphics()
        channels.onData(createRequest(channelId = 7, name = CHANNEL_NAME))

        // A bulk-encoded segment carrying its bytes uncompressed is still bulk framing.
        val bulk = byteArrayOf(0xE0.toByte(), 0x04) + "hello".encodeToByteArray()
        channels.onData(RdpWriter(32).u8(0x70).u8(7).bytes(bulk).toByteArray())

        assertEquals("hello", received.single().decodeToString())
    }

    @Test
    fun `data for a channel that was never opened is dropped`() = runTest {
        registerGraphics()

        channels.onData(RdpWriter(16).u8(0x30).u8(9).bytes("abc".encodeToByteArray()).toByteArray())

        assertTrue(received.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `closing a channel is acknowledged and stops delivery`() = runTest {
        registerGraphics()
        channels.onData(createRequest(channelId = 7, name = CHANNEL_NAME))
        sent.clear()

        channels.onData(RdpWriter(4).u8(0x40).u8(7).toByteArray())
        channels.onData(RdpWriter(16).u8(0x30).u8(7).bytes("abc".encodeToByteArray()).toByteArray())

        assertEquals(0x40, sent.single()[0].toInt() and 0xFF)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `a message too large for one PDU goes out as a first chunk and the rest`() = runTest {
        registerGraphics()
        channels.onData(createRequest(channelId = 7, name = CHANNEL_NAME))
        sent.clear()

        val payload = ByteArray(4000) { (it % 251).toByte() }
        channels.sendTo(CHANNEL_NAME, payload)

        assertEquals(3, sent.size)
        assertEquals(0x28, sent[0][0].toInt() and 0xFF, "first chunk announces the total length")
        assertEquals(0x30, sent[1][0].toInt() and 0xFF)
        val reassembled = sent[0].drop(6) + sent[1].drop(2) + sent[2].drop(2)
        assertContentEquals(payload, reassembled.toByteArray())
    }

    @Test
    fun `sending on a channel the server never opened is a no-op`() = runTest {
        registerGraphics()

        channels.sendTo(CHANNEL_NAME, byteArrayOf(1, 2, 3))

        assertTrue(sent.isEmpty())
    }

    private fun createRequest(channelId: Int, name: String): ByteArray =
        RdpWriter(64).apply {
            u8(0x10) // create, one-byte channel id
            u8(channelId)
            bytes(name.encodeToByteArray())
            u8(0)
        }.toByteArray()

    private fun statusOf(response: ByteArray): Int {
        val offset = response.size - 4
        return (response[offset].toInt() and 0xFF) or ((response[offset + 1].toInt() and 0xFF) shl 8) or
            ((response[offset + 2].toInt() and 0xFF) shl 16) or (response[offset + 3].toInt() shl 24)
    }

    private companion object {
        const val CHANNEL_NAME = "Microsoft::Windows::RDS::Graphics"
    }
}
