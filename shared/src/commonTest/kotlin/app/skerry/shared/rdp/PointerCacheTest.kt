package app.skerry.shared.rdp

import app.skerry.shared.graphics.RemoteFramebuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pointer cache (MS-RDPBCGR 2.2.9.1.1.4.6). A server that has already sent a shape switches back
 * to it with a Cached Pointer Update carrying nothing but a slot index — which is how the cursor
 * returns to the arrow after an I-beam. Ignoring those updates leaves the last explicit shape on
 * screen forever.
 */
class PointerCacheTest {

    @Test
    fun a_cached_pointer_replays_the_shape_parked_in_that_slot() {
        val decoder = FastPathDecoder(RemoteFramebuffer(4, 4), SessionPalette(), DroppedGraphics(), PointerCache())
        val surfaces = SurfaceDecoder(RdpCodecs(null))

        val shape = decoder.decode(fastPathPacket(UPDATETYPE_COLOR_POINTER, colorPointer(slot = 3)), surfaces)
            .single() as RdpUpdate.PointerShape

        val replayed = decoder.decode(fastPathPacket(UPDATETYPE_CACHED_POINTER, cachedPointer(3)), surfaces)

        assertEquals(listOf<RdpUpdate>(shape), replayed, "the cached slot has to produce the shape again")
    }

    @Test
    fun a_cached_pointer_for_an_empty_slot_leaves_the_cursor_alone() {
        val decoder = FastPathDecoder(RemoteFramebuffer(4, 4), SessionPalette(), DroppedGraphics(), PointerCache())

        val updates = decoder.decode(fastPathPacket(UPDATETYPE_CACHED_POINTER, cachedPointer(7)), SurfaceDecoder(RdpCodecs(null)))

        assertTrue(updates.isEmpty(), "a slot the server never filled is the one case with nothing to draw")
    }

    @Test
    fun a_slot_past_the_advertised_cache_is_not_fatal() {
        val decoder = FastPathDecoder(RemoteFramebuffer(4, 4), SessionPalette(), DroppedGraphics(), PointerCache())
        val surfaces = SurfaceDecoder(RdpCodecs(null))

        decoder.decode(fastPathPacket(UPDATETYPE_COLOR_POINTER, colorPointer(slot = PointerCache.CAPACITY + 1)), surfaces)
        val updates = decoder.decode(
            fastPathPacket(UPDATETYPE_CACHED_POINTER, cachedPointer(PointerCache.CAPACITY + 1)),
            surfaces,
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun both_paths_share_one_cache() = runTest {
        // A shape can arrive fast-path and be recalled slow-path, exactly like the session palette.
        val written = mutableListOf<ByteArray>()
        val incoming = fastPathPacket(UPDATETYPE_COLOR_POINTER, colorPointer(slot = 1)) +
            slowPathPointerPdu(PTR_MSGTYPE_CACHED, RdpWriter(2).u16le(1).toByteArray())
        val session = codec(written, incoming)

        val shape = session.readMessage().single() as RdpUpdate.PointerShape
        val replayed = session.readMessage()

        assertEquals(listOf<RdpUpdate>(shape), replayed)
    }

    @Test
    fun a_shape_filed_on_the_slow_path_is_recalled_on_the_fast_one() = runTest {
        // The mirror of the test above: the two paths are one cache in both directions, so a server
        // that sends shapes as share PDUs and switches with fast-path updates works the same.
        val incoming = slowPathPointerPdu(PTR_MSGTYPE_COLOR, colorPointer(slot = 2)) +
            fastPathPacket(UPDATETYPE_CACHED_POINTER, cachedPointer(2))
        val session = codec(mutableListOf(), incoming)

        val shape = session.readMessage().single() as RdpUpdate.PointerShape

        assertEquals(listOf<RdpUpdate>(shape), session.readMessage())
    }

    @Test
    fun a_slot_filled_twice_replays_the_newer_shape() {
        // Windows reuses slots: the cursor that comes back must be the one filed last, not the first
        // shape that ever landed there.
        val decoder = FastPathDecoder(RemoteFramebuffer(4, 4), SessionPalette(), DroppedGraphics(), PointerCache())
        val surfaces = SurfaceDecoder(RdpCodecs(null))

        decoder.decode(fastPathPacket(UPDATETYPE_COLOR_POINTER, colorPointer(slot = 5)), surfaces)
        val newer = decoder.decode(fastPathPacket(UPDATETYPE_COLOR_POINTER, colorPointer(slot = 5, hotspotX = 0)), surfaces)
            .single() as RdpUpdate.PointerShape

        val replayed = decoder.decode(fastPathPacket(UPDATETYPE_CACHED_POINTER, cachedPointer(5)), surfaces)

        assertEquals(listOf<RdpUpdate>(newer), replayed)
        assertEquals(0, (replayed.single() as RdpUpdate.PointerShape).hotspotX)
    }

    @Test
    fun the_advertised_cache_size_matches_the_one_we_keep() {
        // colorPointerCacheSize / pointerCacheSize in the Pointer capability set: a server is free to
        // use every slot it was promised, and a smaller cache here would drop the ones past the end.
        val body = ClientCapabilities.confirmActive(shareId = 1, userId = 2, width = 800, height = 600, remoteFx = false)
        val pointerSet = capabilitySet(body, CapabilitySetType.POINTER)

        val reader = RdpReader(pointerSet)
        reader.u16le() // colorPointerFlag
        assertEquals(PointerCache.CAPACITY, reader.u16le(), "colorPointerCacheSize")
        assertEquals(PointerCache.CAPACITY, reader.u16le(), "pointerCacheSize")
    }

    /** A 2×2 opaque-red-corner sprite (the shape [GraphicsTest] pins), parked in [slot]. */
    private fun colorPointer(slot: Int, hotspotX: Int = 1): ByteArray = RdpWriter(64).apply {
        u16le(slot) // cacheIndex
        u16le(hotspotX).u16le(1) // hotspot
        u16le(2).u16le(2) // width, height
        u16le(4) // lengthAndMask
        u16le(12) // lengthXorMask
        u8(0).u8(0).u8(0)
        u8(0).u8(0).u8(0)
        u8(0).u8(0).u8(0xFF) // BGR red at the top-left
        u8(0).u8(0).u8(0)
        u8(0xFF).u8(0x00)
        u8(0x7F).u8(0x00)
    }.toByteArray()

    private fun cachedPointer(slot: Int): ByteArray = RdpWriter(2).u16le(slot).toByteArray()

    /** One fast-path update of [updateCode], single fragment, uncompressed. */
    private fun fastPathPacket(updateCode: Int, body: ByteArray): ByteArray {
        val update = RdpWriter(body.size + 3).apply {
            u8(updateCode)
            u16le(body.size)
            bytes(body)
        }.toByteArray()
        val total = update.size + 3
        return RdpWriter(total).apply {
            u8(0) // action = fast-path output
            u16be(total or 0x8000)
            bytes(update)
        }.toByteArray()
    }

    private fun slowPathPointerPdu(messageType: Int, attribute: ByteArray): ByteArray {
        val body = RdpWriter(attribute.size + 4).apply {
            u16le(messageType)
            u16le(0) // pad2Octets
            bytes(attribute)
        }.toByteArray()
        val share = RdpShare.dataPdu(SHARE_ID, userId = 1002, pduType2 = RdpShare.PDUTYPE2_POINTER, body = body)
        return Mcs.sendDataRequest(userId = 1002, channelId = 1003, payload = share)
    }

    private fun codec(written: MutableList<ByteArray>, incoming: ByteArray): RdpSessionCodec {
        var offset = 0
        val caps = ServerCapabilities(
            shareId = SHARE_ID,
            desktopWidth = 640,
            desktopHeight = 480,
            preferredBitsPerPixel = 32,
            desktopResizeSupported = false,
            refreshRectSupported = false,
            suppressOutputSupported = false,
            fastPathOutputSupported = true,
            noBitmapCompressionHeader = false,
            surfaceCommandsSupported = false,
            frameAcknowledgeSupported = false,
            maxRequestSize = 65535,
            supportedCodecs = emptyList(),
        )
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

    /** The body of capability set [type] inside a Confirm Active PDU, header stripped. */
    private fun capabilitySet(pdu: ByteArray, type: Int): ByteArray {
        val reader = RdpReader(pdu, RdpShare.CONTROL_HEADER_SIZE)
        reader.skip(4) // shareId
        reader.skip(2) // originatorId
        val descriptorLength = reader.u16le()
        reader.skip(2) // lengthCombinedCapabilities
        reader.skip(descriptorLength)
        val count = reader.u16le()
        reader.skip(2) // pad2Octets
        repeat(count) {
            val setType = reader.u16le()
            val length = reader.u16le()
            val body = reader.bytes(length - 4)
            if (setType == type) return body
        }
        error("capability set $type is not in the Confirm Active PDU")
    }

    private companion object {
        const val SHARE_ID = 0x10001
        const val UPDATETYPE_COLOR_POINTER = 0x9
        const val UPDATETYPE_CACHED_POINTER = 0xA
        const val PTR_MSGTYPE_CACHED = 0x0007
        const val PTR_MSGTYPE_COLOR = 0x0006
    }
}
