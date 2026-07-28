package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Confirm Active PDU, checked field-width by field-width against MS-RDPBCGR 2.2.7.
 *
 * Every capability set has a size the specification fixes, and a set that is one field short still
 * looks plausible: the bytes after it simply shift, so the values a lenient server reads are wrong
 * rather than missing. xrdp accepted exactly that; Windows answered ERRINFO_BAD_CAPABILITIES
 * (0x10EA) and dropped the session. Pinning the sizes is what turns that class of bug into a test
 * failure instead of a live one.
 */
class ClientCapabilitiesTest {

    @Test
    fun `the general capability set of a Demand Active is read at the offsets the specification fixes`() {
        // TS_GENERAL_CAPABILITYSET, MS-RDPBCGR 2.2.7.1.1, field by field. The one that is easy to
        // lose is generalCompressionTypes: without it every field after it shifts by two bytes, and
        // the flags a server really sent read as the zeros of its neighbours.
        val general = RdpWriter(24).apply {
            u16le(1) // osMajorType
            u16le(3) // osMinorType
            u16le(0x0200) // protocolVersion
            u16le(0) // pad2octetsA
            u16le(0) // generalCompressionTypes
            u16le(0x0001 or 0x0400) // extraFlags: FASTPATH_OUTPUT_SUPPORTED, NO_BITMAP_COMPRESSION_HDR
            u16le(0) // updateCapabilityFlag
            u16le(0) // remoteUnshareFlag
            u16le(0) // generalCompressionLevel
            u8(1) // refreshRectSupport
            u8(1) // suppressOutputSupport
        }.toByteArray()

        val capabilities = Capabilities.parseDemandActive(RdpReader(demandActive(type = 1, set = general)))

        assertTrue(capabilities.fastPathOutputSupported, "the server's fast-path output flag was misread")
        assertTrue(capabilities.noBitmapCompressionHeader, "the server's compression-header flag was misread")
        assertTrue(capabilities.refreshRectSupported, "the server's refresh-rect support was misread")
        assertTrue(capabilities.suppressOutputSupported, "the server's suppress-output support was misread")
    }

    /**
     * A Demand Active body — what [Capabilities.parseDemandActive] is handed — carrying [set] and
     * the bitmap set the parse insists on, since a server that announces no desktop size is refused.
     */
    private fun demandActive(type: Int, set: ByteArray): ByteArray {
        val bitmap = RdpWriter(16).apply {
            u16le(32) // preferredBitsPerPixel
            u16le(1).u16le(1).u16le(1) // receive1BitPerPixel, receive4BitsPerPixel, receive8BitsPerPixel
            u16le(1024) // desktopWidth
            u16le(768) // desktopHeight
            u16le(0) // pad2octets
            u16le(1) // desktopResizeFlag
        }.toByteArray()
        val capabilities = RdpWriter(set.size + bitmap.size + 12).apply {
            u16le(2) // numberCapabilities
            u16le(0) // pad2Octets
            u16le(type)
            u16le(set.size + 4)
            bytes(set)
            u16le(CapabilitySetType.BITMAP)
            u16le(bitmap.size + 4)
            bytes(bitmap)
        }.toByteArray()
        return RdpWriter(capabilities.size + 8).apply {
            u32le(0x000103EA) // shareId
            u16le(0) // lengthSourceDescriptor
            u16le(capabilities.size)
            bytes(capabilities)
        }.toByteArray()
    }

    /** Declared length of every capability set the client confirms, by type. */
    private fun capabilitySetLengths(remoteFx: Boolean): Map<Int, Int> {
        val pdu = ClientCapabilities.confirmActive(
            shareId = 0x000103EA,
            userId = 1007,
            width = 1920,
            height = 1080,
            remoteFx = remoteFx,
        )
        val reader = RdpReader(pdu)
        RdpShare.readControlHeader(reader)
        reader.u32le() // shareId
        reader.u16le() // originatorId
        val sourceLength = reader.u16le()
        reader.u16le() // lengthCombinedCapabilities
        reader.skip(sourceLength)
        val count = reader.u16le()
        reader.u16le() // pad
        return buildMap {
            repeat(count) {
                val type = reader.u16le()
                val length = reader.u16le()
                put(type, length)
                reader.skip(length - 4)
            }
        }
    }

    @Test
    fun `every capability set has the size the specification fixes`() {
        val lengths = capabilitySetLengths(remoteFx = false)

        assertEquals(24, lengths[CapabilitySetType.GENERAL], "TS_GENERAL_CAPABILITYSET")
        assertEquals(28, lengths[CapabilitySetType.BITMAP], "TS_BITMAP_CAPABILITYSET")
        assertEquals(88, lengths[CapabilitySetType.ORDER], "TS_ORDER_CAPABILITYSET")
        assertEquals(40, lengths[CapabilitySetType.BITMAP_CACHE], "TS_BITMAPCACHE_CAPABILITYSET")
        assertEquals(8, lengths[CapabilitySetType.COLOR_CACHE], "TS_COLORTABLE_CAPABILITYSET")
        assertEquals(10, lengths[CapabilitySetType.POINTER], "TS_POINTER_CAPABILITYSET")
        assertEquals(88, lengths[CapabilitySetType.INPUT], "TS_INPUT_CAPABILITYSET")
        assertEquals(8, lengths[CapabilitySetType.BRUSH], "TS_BRUSH_CAPABILITYSET")
        assertEquals(52, lengths[CapabilitySetType.GLYPH_CACHE], "TS_GLYPHCACHE_CAPABILITYSET")
        assertEquals(12, lengths[CapabilitySetType.OFFSCREEN_CACHE], "TS_OFFSCREEN_CAPABILITYSET")
        assertEquals(12, lengths[CapabilitySetType.VIRTUAL_CHANNEL], "TS_VIRTUALCHANNEL_CAPABILITYSET")
        assertEquals(8, lengths[CapabilitySetType.SOUND], "TS_SOUND_CAPABILITYSET")
        assertEquals(8, lengths[CapabilitySetType.SHARE], "TS_SHARE_CAPABILITYSET")
        assertEquals(8, lengths[CapabilitySetType.FONT], "TS_FONT_CAPABILITYSET")
        assertEquals(8, lengths[CapabilitySetType.MULTIFRAGMENT_UPDATE], "TS_MULTIFRAGMENTUPDATE_CAPABILITYSET")
        assertEquals(6, lengths[CapabilitySetType.LARGE_POINTER], "TS_LARGE_POINTER_CAPABILITYSET")
        assertEquals(12, lengths[CapabilitySetType.SURFACE_COMMANDS], "TS_SURFCMDS_CAPABILITYSET")
        assertEquals(8, lengths[CapabilitySetType.FRAME_ACKNOWLEDGE], "TS_FRAME_ACKNOWLEDGE_CAPABILITYSET")
    }

    @Test
    fun `the codecs set is added only when the server offered RemoteFX`() {
        assertTrue(CapabilitySetType.BITMAP_CODECS !in capabilitySetLengths(remoteFx = false))
        val length = capabilitySetLengths(remoteFx = true)[CapabilitySetType.BITMAP_CODECS]
        assertTrue(length != null && length > 4, "the codecs set carries the RemoteFX container")
    }

    @Test
    fun `the general set carries the flags after the compression fields, not in them`() {
        val pdu = ClientCapabilities.confirmActive(0, 1007, 1024, 768, remoteFx = false)
        val reader = RdpReader(pdu)
        RdpShare.readControlHeader(reader)
        reader.u32le()
        reader.u16le()
        val sourceLength = reader.u16le()
        reader.u16le()
        reader.skip(sourceLength)
        reader.u16le()
        reader.u16le()
        assertEquals(CapabilitySetType.GENERAL, reader.u16le())
        reader.u16le() // lengthCapability
        reader.skip(6) // osMajorType, osMinorType, protocolVersion
        assertEquals(0, reader.u16le(), "pad2octetsA")
        assertEquals(0, reader.u16le(), "compressionTypes")
        // FASTPATH_OUTPUT_SUPPORTED lives in extraFlags; reading it here means the field order is
        // right, and a missing compressionTypes would put the flags two bytes early.
        assertTrue(reader.u16le() and 0x0001 != 0, "extraFlags advertises fast-path output")
    }

    @Test
    fun `the combined capability length covers the sets, the count and the padding`() {
        val pdu = ClientCapabilities.confirmActive(0, 1007, 1024, 768, remoteFx = true)
        val reader = RdpReader(pdu)
        RdpShare.readControlHeader(reader)
        reader.u32le()
        reader.u16le()
        val sourceLength = reader.u16le()
        val combined = reader.u16le()
        reader.skip(sourceLength)
        // What follows the source descriptor is exactly what the field declares: a server sizes its
        // read on it, so an off-by-four here truncates the last set.
        assertEquals(combined, reader.remaining)
    }
}
