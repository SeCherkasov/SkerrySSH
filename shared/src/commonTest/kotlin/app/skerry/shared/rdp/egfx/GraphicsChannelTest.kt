package app.skerry.shared.rdp.egfx

import app.skerry.shared.graphics.RemoteFramebuffer
import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpRect
import app.skerry.shared.rdp.RdpUpdate
import app.skerry.shared.rdp.RdpWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The graphics pipeline channel: surfaces, the mapping that decides what is on screen, the cache,
 * and the frame acknowledgements the server paces itself on.
 */
class GraphicsChannelTest {

    private val framebuffer = RemoteFramebuffer(64, 32)
    private val sent = mutableListOf<ByteArray>()
    private val progressive = RecordingProgressive()
    private val channel = GraphicsChannel(framebuffer, GraphicsCodecs(progressive = progressive)) { data ->
        sent.add(data)
    }

    @Test
    fun `the client advertises its capabilities as soon as the channel opens`() = runTest {
        channel.onOpen()

        val advertise = sent.single()
        assertEquals(0x0012, advertise.u16(0), "caps advertise command")
        assertEquals(advertise.size, advertise.u32(4), "the PDU declares its own length")
        assertEquals(1, advertise.u16(8), "one capability set")
        assertEquals(0x00080004, advertise.u32(10), "version 8, which has no H.264")
    }

    @Test
    fun `a client with an H 264 decoder advertises the versions that let the server use it`() = runTest {
        val avc = RecordingAvc()
        val withH264 = GraphicsChannel(framebuffer, GraphicsCodecs(avc = avc)) { data -> sent.add(data) }

        withH264.onOpen()

        val advertise = sent.single()
        assertEquals(3, advertise.u16(8), "three capability sets")
        assertEquals(0x00080004, advertise.u32(10), "version 8, which the server falls back to")
        assertEquals(0x00080105, advertise.u32(22), "version 8.1, which adds 4:2:0 H.264")
        assertEquals(0x10, advertise.u32(30) and 0x10, "8.1 carries H.264 only with the flag set")
        assertEquals(0x000A0400, advertise.u32(34), "version 10.4, which adds 4:4:4 H.264")
        assertEquals(0, advertise.u32(42) and 0x20, "10.4 must not carry the flag that disables AVC")
    }

    @Test
    fun `the version the server confirmed is reported, because nothing else says it took H 264`() = runTest {
        val lines = mutableListOf<String>()
        val channel = GraphicsChannel(framebuffer, GraphicsCodecs(), trace = { lines += it }) { }

        channel.onMessage(bulk(pdu(0x0013, RdpWriter(4).u32le(0x000A0400).toByteArray())))

        assertTrue(lines.single().contains("a0400"), "the confirmed version was not reported: $lines")
    }

    @Test
    fun `a 4 to 2 to 0 bitmap goes to the H 264 codec and its regions reach the screen`() = runTest {
        val avc = RecordingAvc()
        avc.touched += RdpRect(0, 0, 4, 4)
        val channel = GraphicsChannel(framebuffer, GraphicsCodecs(avc = avc)) { }
        channel.onMessage(bulk(createSurface(id = 1, width = 8, height = 8)))
        channel.onMessage(bulk(mapToOutput(surfaceId = 1, x = 0, y = 0)))
        channel.drainUpdates()

        channel.onMessage(
            bulk(wireToSurfaceRaw(surfaceId = 1, codecId = 0x000B, payload = byteArrayOf(9), rect = RdpRect(0, 0, 8, 8))),
        )

        assertEquals(listOf<Byte>(9), avc.decoded420.single().toList())
        val regions = channel.drainUpdates().filterIsInstance<RdpUpdate.Region>()
        assertEquals(listOf(RdpRect(0, 0, 4, 4)), regions.single().rects)
    }

    @Test
    fun `both 4 to 4 to 4 codec ids reach the decoder, and only one of them is the second packing`() = runTest {
        val avc = RecordingAvc()
        val channel = GraphicsChannel(framebuffer, GraphicsCodecs(avc = avc)) { }
        channel.onMessage(bulk(createSurface(id = 1, width = 8, height = 8)))

        channel.onMessage(bulk(wireToSurfaceRaw(1, codecId = 0x000E, payload = byteArrayOf(1))))
        channel.onMessage(bulk(wireToSurfaceRaw(1, codecId = 0x000F, payload = byteArrayOf(2))))

        assertEquals(listOf(false, true), avc.decoded444)
    }

    @Test
    fun `a server that sends H 264 to a client without a decoder is reported`() = runTest {
        deliver(createSurface(id = 1, width = 8, height = 8))

        val failure = assertFailsWith<RdpProtocolException> {
            deliver(wireToSurfaceRaw(surfaceId = 1, codecId = 0x000B, payload = byteArrayOf(1)))
        }

        assertTrue(failure.message.orEmpty().contains("H.264"), "the reason has to name the codec")
    }

    @Test
    fun `closing the channel gives back the decoders the server never asked to delete`() = runTest {
        val avc = RecordingAvc()
        val channel = GraphicsChannel(framebuffer, GraphicsCodecs(avc = avc)) { }
        channel.onMessage(bulk(createSurface(id = 1, width = 8, height = 8)))

        channel.close()

        assertTrue(avc.closed, "the session ended holding an H.264 decoder")
    }

    @Test
    fun `a deleted surface takes its H 264 decoder with it`() = runTest {
        val avc = RecordingAvc()
        val channel = GraphicsChannel(framebuffer, GraphicsCodecs(avc = avc)) { }
        channel.onMessage(bulk(createSurface(id = 3, width = 8, height = 8)))
        avc.forgotten.clear()

        channel.onMessage(bulk(deleteSurface(id = 3)))

        assertEquals(listOf(3), avc.forgotten)
    }

    @Test
    fun `pixels reach the screen only once the surface is mapped to an output`() = runTest {
        deliver(createSurface(id = 1, width = 8, height = 8))
        deliver(wireToSurface(surfaceId = 1, rect = RdpRect(0, 0, 8, 8), colour = 0x123456))

        assertEquals(0, framebuffer.pixels[0], "an unmapped surface painted the desktop")

        deliver(mapToOutput(surfaceId = 1, x = 4, y = 2))

        assertEquals(0xFF123456.toInt(), framebuffer.pixels[2 * 64 + 4])
        assertEquals(0, framebuffer.pixels[0], "the surface painted outside its mapping")
    }

    @Test
    fun `a frame is acknowledged and its damage is emitted once`() = runTest {
        deliver(createSurface(id = 1, width = 8, height = 8))
        deliver(mapToOutput(surfaceId = 1, x = 0, y = 0))
        channel.drainUpdates()
        sent.clear()

        deliver(
            startFrame(frameId = 5),
            wireToSurface(surfaceId = 1, rect = RdpRect(0, 0, 4, 4), colour = 0x00FF00),
            endFrame(frameId = 5),
        )

        val acknowledge = sent.single()
        assertEquals(0x000D, acknowledge.u16(0), "frame acknowledge command")
        assertEquals(5, acknowledge.u32(12), "the frame that was acknowledged")
        assertEquals(1, acknowledge.u32(16), "frames decoded so far")

        val regions = channel.drainUpdates().filterIsInstance<RdpUpdate.Region>()
        assertEquals(listOf(RdpRect(0, 0, 4, 4)), regions.single().rects)
    }

    @Test
    fun `a reset resizes the desktop and drops what was drawn at the old resolution`() = runTest {
        // The server redraws the new desktop from an empty screen and sends only what changed
        // since then, so anything kept here is a rectangle of the old resolution that nothing
        // will ever paint over.
        deliver(createSurface(id = 1, width = 8, height = 8))
        deliver(wireToSurface(surfaceId = 1, rect = RdpRect(0, 0, 8, 8), colour = 0xABCDEF))
        deliver(mapToOutput(surfaceId = 1, x = 0, y = 0))
        channel.drainUpdates()

        deliver(resetGraphics(width = 100, height = 50))

        assertEquals(100, framebuffer.width)
        assertEquals(50, framebuffer.height)
        assertEquals(0, framebuffer.pixels[0], "the old frame was repainted onto the new desktop")
        assertTrue(channel.drainUpdates().any { it is RdpUpdate.Resize })

        // The surface survives the reset, but empty: re-presenting it must not bring the old
        // pixels back either.
        deliver(mapToOutput(surfaceId = 1, x = 0, y = 0))
        assertEquals(0, framebuffer.pixels[0], "the surface kept the pixels of the old resolution")
    }

    @Test
    fun `a cached region is restored wherever the server asks`() = runTest {
        deliver(createSurface(id = 1, width = 16, height = 16))
        deliver(mapToOutput(surfaceId = 1, x = 0, y = 0))
        deliver(wireToSurface(surfaceId = 1, rect = RdpRect(0, 0, 4, 4), colour = 0x00AABB))

        deliver(surfaceToCache(surfaceId = 1, slot = 3, rect = RdpRect(0, 0, 4, 4)))
        deliver(cacheToSurface(slot = 3, surfaceId = 1, x = 8, y = 8))

        assertEquals(0xFF00AABB.toInt(), framebuffer.pixels[8 * 64 + 8])
        assertEquals(0xFF00AABB.toInt(), framebuffer.pixels[11 * 64 + 11])
        assertEquals(0, framebuffer.pixels[12 * 64 + 12], "the cached region spilled past its size")
    }

    @Test
    fun `a solid fill paints the rectangles it names`() = runTest {
        deliver(createSurface(id = 1, width = 16, height = 16))
        deliver(mapToOutput(surfaceId = 1, x = 0, y = 0))

        val body = RdpWriter(32).apply {
            u16le(1) // surfaceId
            u8(0x11).u8(0x22).u8(0x33).u8(0xFF) // blue, green, red, alpha
            u16le(1)
            u16le(2).u16le(2).u16le(6).u16le(6)
        }.toByteArray()
        deliver(pdu(0x0004, body))

        assertEquals(0xFF332211.toInt(), framebuffer.pixels[2 * 64 + 2])
        assertEquals(0, framebuffer.pixels[6 * 64 + 6], "the fill covered the exclusive edge")
    }

    @Test
    fun `a codec this client cannot decode ends the session with the codec's name`() = runTest {
        deliver(createSurface(id = 1, width = 8, height = 8))

        val failure = assertFailsWith<RdpProtocolException> {
            deliver(wireToSurfaceRaw(surfaceId = 1, codecId = 0x000B, payload = ByteArray(4)))
        }

        assertTrue(failure.message.orEmpty().contains("H.264"), failure.message.orEmpty())
    }

    @Test
    fun `a persistent codec context draws over the whole surface`() = runTest {
        deliver(createSurface(id = 1, width = 8, height = 4))

        deliver(wireToSurfaceProgressive(surfaceId = 1, payload = byteArrayOf(1, 2, 3), declareLength = true))

        assertEquals(listOf<Byte>(1, 2, 3), progressive.data.single().toList())
        // The message carries no rectangle of its own — the stream's regions are surface-relative.
        assertEquals(RdpRect(0, 0, 8, 4), progressive.destinations.single())
    }

    @Test
    fun `a persistent codec context without a length field is still decoded`() = runTest {
        deliver(createSurface(id = 1, width = 8, height = 4))

        deliver(wireToSurfaceProgressive(surfaceId = 1, payload = byteArrayOf(1, 2, 3), declareLength = false))

        assertEquals(listOf<Byte>(1, 2, 3), progressive.data.single().toList())
    }

    @Test
    fun `a deleted encoding context keeps the tile history the server still refines`() = runTest {
        // The context identifies a stream, not the picture: the server goes on describing tiles as
        // differences from the ones it sent before. Forgetting them here is what turns an unchanged
        // tile into a difference added to nothing, which is a mid-grey square nothing repaints.
        deliver(createSurface(id = 1, width = 8, height = 8))
        progressive.forgotten.clear()

        deliver(deleteEncodingContext(surfaceId = 1, codecContextId = 0x1234))

        assertTrue(progressive.forgotten.isEmpty(), "the surface's tile history was dropped")
    }

    @Test
    fun `a deleted surface takes its tile history with it`() = runTest {
        deliver(createSurface(id = 1, width = 8, height = 8))
        progressive.forgotten.clear()

        deliver(deleteSurface(id = 1))

        assertEquals(listOf(1), progressive.forgotten)
    }

    @Test
    fun `drawing on a surface the server never created is ignored`() = runTest {
        deliver(wireToSurface(surfaceId = 9, rect = RdpRect(0, 0, 4, 4), colour = 0x00FF00))

        assertTrue(channel.drainUpdates().isEmpty())
    }

    @Test
    fun `a PDU claiming more bytes than the message holds is refused`() = runTest {
        val truncated = RdpWriter(8).u16le(0x0009).u16le(0).u32le(9999).toByteArray()

        assertFailsWith<RdpProtocolException> { deliver(truncated) }
    }

    // ---- PDU builders ----

    @Test
    fun `a surface bitmap of a size no screen has is refused before it is allocated`() = runTest {
        deliver(createSurface(id = 1, width = 8, height = 8))

        assertFailsWith<RdpProtocolException> {
            deliver(
                wireToSurfaceRaw(
                    surfaceId = 1,
                    codecId = 0x0000,
                    payload = ByteArray(0),
                    // The rectangle is the server's to declare and is not clipped to the surface
                    // before the codec allocates the pixels it asks for.
                    rect = RdpRect(0, 0, 65535, 32000),
                ),
            )
        }
    }

    @Test
    fun `surfaces stop being created once they no longer fit the memory budget`() = runTest {
        val budgeted = GraphicsChannel(framebuffer, GraphicsCodecs(), surfaceBudgetPixels = 128) { }
        budgeted.onMessage(bulk(createSurface(id = 1, width = 8, height = 8)))

        assertFailsWith<RdpProtocolException> {
            budgeted.onMessage(bulk(createSurface(id = 2, width = 16, height = 16)))
        }
    }

    @Test
    fun `a deleted surface gives its budget back`() = runTest {
        val budgeted = GraphicsChannel(framebuffer, GraphicsCodecs(), surfaceBudgetPixels = 128) { }
        budgeted.onMessage(bulk(createSurface(id = 1, width = 8, height = 8)))
        budgeted.onMessage(bulk(deleteSurface(id = 1)))

        budgeted.onMessage(bulk(createSurface(id = 2, width = 8, height = 8)))
    }

    private fun pdu(commandId: Int, body: ByteArray): ByteArray =
        RdpWriter(body.size + 8).u16le(commandId).u16le(0).u32le(body.size + 8).bytes(body).toByteArray()

    /**
     * Deliver PDUs the way the channel carries them: one bulk-encoded message, here with its
     * contents stored uncompressed, which is legitimate framing and what a server falls back to.
     */
    private suspend fun deliver(vararg pdus: ByteArray) =
        channel.onMessage(bulk(pdus.fold(ByteArray(0)) { all, pdu -> all + pdu }))

    private fun bulk(payload: ByteArray): ByteArray = byteArrayOf(0xE0.toByte(), 0x04) + payload

    private fun createSurface(id: Int, width: Int, height: Int): ByteArray =
        pdu(0x0009, RdpWriter(8).u16le(id).u16le(width).u16le(height).u8(0x20).toByteArray())

    private fun deleteSurface(id: Int): ByteArray = pdu(0x000A, RdpWriter(2).u16le(id).toByteArray())

    private fun deleteEncodingContext(surfaceId: Int, codecContextId: Int): ByteArray =
        pdu(0x0003, RdpWriter(6).u16le(surfaceId).u32le(codecContextId).toByteArray())

    private fun mapToOutput(surfaceId: Int, x: Int, y: Int): ByteArray =
        pdu(0x000F, RdpWriter(12).u16le(surfaceId).u16le(0).u32le(x).u32le(y).toByteArray())

    private fun startFrame(frameId: Int): ByteArray =
        pdu(0x000B, RdpWriter(8).u32le(0).u32le(frameId).toByteArray())

    private fun endFrame(frameId: Int): ByteArray = pdu(0x000C, RdpWriter(4).u32le(frameId).toByteArray())

    private fun resetGraphics(width: Int, height: Int): ByteArray {
        val body = RdpWriter(340).apply {
            u32le(width)
            u32le(height)
            u32le(0) // monitorCount
            zeros(340 - 8 - 12) // the PDU is padded to a fixed size
        }.toByteArray()
        return pdu(0x000E, body)
    }

    /** An uncompressed rectangle of one colour. */
    private fun wireToSurface(surfaceId: Int, rect: RdpRect, colour: Int): ByteArray {
        val pixels = RdpWriter(rect.width * rect.height * 4)
        repeat(rect.width * rect.height) {
            pixels.u8(colour and 0xFF).u8((colour shr 8) and 0xFF).u8((colour shr 16) and 0xFF).u8(0xFF)
        }
        return wireToSurfaceRaw(surfaceId, codecId = 0x0000, payload = pixels.toByteArray(), rect = rect)
    }

    private fun wireToSurfaceRaw(
        surfaceId: Int,
        codecId: Int,
        payload: ByteArray,
        rect: RdpRect = RdpRect(0, 0, 1, 1),
    ): ByteArray {
        val body = RdpWriter(payload.size + 17).apply {
            u16le(surfaceId)
            u16le(codecId)
            u8(0x20) // XRGB
            u16le(rect.x).u16le(rect.y).u16le(rect.x + rect.width).u16le(rect.y + rect.height)
            u32le(payload.size)
            bytes(payload)
        }.toByteArray()
        return pdu(0x0001, body)
    }

    private fun wireToSurfaceProgressive(
        surfaceId: Int,
        payload: ByteArray,
        declareLength: Boolean,
    ): ByteArray {
        val body = RdpWriter(payload.size + 13).apply {
            u16le(surfaceId)
            u16le(GraphicsCodecs.CODEC_PROGRESSIVE)
            u32le(0x1234) // codecContextId
            u8(0x20) // XRGB
            if (declareLength) u32le(payload.size)
            bytes(payload)
        }.toByteArray()
        return pdu(0x0002, body)
    }

    /** Stands in for the progressive codec, which has its own tests. */
    private class RecordingProgressive : ProgressiveDecoder {
        val data = mutableListOf<ByteArray>()
        val destinations = mutableListOf<RdpRect>()
        val forgotten = mutableListOf<Int>()

        override fun decode(data: ByteArray, surface: GraphicsSurface, destination: RdpRect): List<RdpRect> {
            this.data += data
            destinations += destination
            return emptyList()
        }

        override fun forgetSurface(surfaceId: Int) {
            forgotten += surfaceId
        }
    }

    /** Stands in for the H.264 codecs, which have their own tests. */
    private class RecordingAvc : AvcDecoder {
        val decoded420 = mutableListOf<ByteArray>()
        val decoded444 = mutableListOf<Boolean>()
        val forgotten = mutableListOf<Int>()
        val touched = mutableListOf<RdpRect>()
        var closed = false

        override fun decodeAvc420(data: ByteArray, surface: GraphicsSurface): List<RdpRect> {
            decoded420 += data
            return touched
        }

        override fun decodeAvc444(data: ByteArray, surface: GraphicsSurface, version2: Boolean): List<RdpRect> {
            decoded444 += version2
            return touched
        }

        override fun forgetSurface(surfaceId: Int) {
            forgotten += surfaceId
        }

        override fun close() {
            closed = true
        }
    }

    private fun surfaceToCache(surfaceId: Int, slot: Int, rect: RdpRect): ByteArray =
        pdu(
            0x0006,
            RdpWriter(24).apply {
                u16le(surfaceId)
                u32le(0).u32le(0) // cacheKey
                u16le(slot)
                u16le(rect.x).u16le(rect.y).u16le(rect.x + rect.width).u16le(rect.y + rect.height)
            }.toByteArray(),
        )

    private fun cacheToSurface(slot: Int, surfaceId: Int, x: Int, y: Int): ByteArray =
        pdu(0x0007, RdpWriter(12).u16le(slot).u16le(surfaceId).u16le(1).u16le(x).u16le(y).toByteArray())

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(offset: Int): Int =
        u16(offset) or (u16(offset + 2) shl 16)
}
