package app.skerry.shared.rdp

/**
 * The capability sets this client confirms (MS-RDPBCGR 2.2.1.13.2). What is advertised here decides
 * how the session's pixels arrive:
 *
 * - No drawing orders are claimed (`orderSupport` is all zeros). This client paints from bitmap
 *   updates and surface commands, and a server told otherwise would send GDI primitives nothing
 *   here can execute. One that sends them regardless has its orders skipped and the desktop
 *   repainted (see `FastPathDecoder` and `RdpSessionCodec.repaintDropped`).
 * - Surface commands plus RemoteFX are claimed, which is what makes a modern server stream tiles
 *   instead of legacy bitmaps.
 * - Fast-path input and output are claimed in both directions: it is the low-latency framing, and
 *   every server since Vista prefers it.
 */
object ClientCapabilities {
    private const val OS_MAJOR_TYPE_WINDOWS = 1
    private const val OS_MINOR_TYPE_WINDOWS_NT = 3
    private const val PROTOCOL_VERSION = 0x0200

    // TS_GENERAL_CAPABILITYSET::extraFlags.
    private const val FASTPATH_OUTPUT_SUPPORTED = 0x0001
    private const val LONG_CREDENTIALS_SUPPORTED = 0x0004
    private const val AUTORECONNECT_SUPPORTED = 0x0008
    private const val ENC_SALTED_CHECKSUM = 0x0010
    private const val NO_BITMAP_COMPRESSION_HDR = 0x0400

    // TS_INPUT_CAPABILITYSET::inputFlags.
    private const val INPUT_FLAG_SCANCODES = 0x0001
    private const val INPUT_FLAG_MOUSEX = 0x0004
    private const val INPUT_FLAG_FASTPATH_INPUT = 0x0008
    private const val INPUT_FLAG_UNICODE = 0x0010
    private const val INPUT_FLAG_FASTPATH_INPUT2 = 0x0020

    // TS_BITMAP_CAPABILITYSET::drawingFlags.
    private const val DRAW_ALLOW_DYNAMIC_COLOR_FIDELITY = 0x02
    private const val DRAW_ALLOW_COLOR_SUBSAMPLING = 0x04
    private const val DRAW_ALLOW_SKIP_ALPHA = 0x08

    // TS_LARGE_POINTER_CAPABILITYSET::largePointerSupportFlags.
    private const val LARGE_POINTER_FLAG_96x96 = 0x00000001

    /** Codec ids this client assigns; the server quotes them back in every surface command. */
    const val CODEC_ID_REMOTEFX = 3
    const val CODEC_ID_NSCODEC = 1

    /**
     * Largest single update we accept before the server has to fragment it. RemoteFX frames of a
     * full-screen change run to hundreds of kilobytes, and a small value here turns each of them
     * into a fragment storm.
     */
    private const val MAX_REQUEST_SIZE = 0x3F0000

    /** Frames the server may have in flight before we acknowledge; it is what paces the stream. */
    private const val MAX_UNACKNOWLEDGED_FRAMES = 2

    /**
     * Build the Confirm Active PDU body for a session of [width]×[height], echoing [shareId].
     *
     * [remoteFx] is set only when the server advertised the codec: claiming a codec the peer does
     * not have is harmless, but claiming one *we* cannot decode is not, so this follows the
     * negotiation rather than an ambition.
     */
    fun confirmActive(shareId: Int, userId: Int, width: Int, height: Int, remoteFx: Boolean): ByteArray {
        val sets = RdpWriter(1024)
        var count = 0
        fun add(type: Int, body: ByteArray) {
            sets.u16le(type).u16le(body.size + 4).bytes(body)
            count++
        }

        add(CapabilitySetType.GENERAL, generalCapabilities())
        add(CapabilitySetType.BITMAP, bitmapCapabilities(width, height))
        add(CapabilitySetType.ORDER, orderCapabilities())
        add(CapabilitySetType.BITMAP_CACHE, noBitmapCache())
        add(CapabilitySetType.COLOR_CACHE, RdpWriter(4).u16le(6).u16le(0).toByteArray())
        add(CapabilitySetType.POINTER, pointerCapabilities())
        add(CapabilitySetType.INPUT, inputCapabilities())
        add(CapabilitySetType.BRUSH, RdpWriter(4).u32le(0).toByteArray()) // BRUSH_DEFAULT
        add(CapabilitySetType.GLYPH_CACHE, glyphCacheCapabilities())
        add(CapabilitySetType.OFFSCREEN_CACHE, RdpWriter(8).u32le(0).u16le(0).u16le(0).toByteArray())
        add(CapabilitySetType.VIRTUAL_CHANNEL, RdpWriter(8).u32le(0).u32le(CHANNEL_CHUNK_SIZE).toByteArray())
        add(CapabilitySetType.SOUND, RdpWriter(4).u16le(0).u16le(0).toByteArray())
        add(CapabilitySetType.SHARE, RdpWriter(4).u16le(0).u16le(0).toByteArray())
        add(CapabilitySetType.FONT, RdpWriter(4).u16le(1).u16le(0).toByteArray()) // FONTSUPPORT_FONTLIST
        add(CapabilitySetType.MULTIFRAGMENT_UPDATE, RdpWriter(4).u32le(MAX_REQUEST_SIZE).toByteArray())
        add(CapabilitySetType.LARGE_POINTER, RdpWriter(2).u16le(LARGE_POINTER_FLAG_96x96).toByteArray())
        add(CapabilitySetType.SURFACE_COMMANDS, surfaceCommandsCapabilities())
        add(CapabilitySetType.FRAME_ACKNOWLEDGE, RdpWriter(4).u32le(MAX_UNACKNOWLEDGED_FRAMES).toByteArray())
        if (remoteFx) add(CapabilitySetType.BITMAP_CODECS, bitmapCodecsCapabilities())

        val capabilities = sets.toByteArray()
        val sourceDescriptor = SOURCE_DESCRIPTOR
        val body = RdpWriter(capabilities.size + 32)
        body.u32le(shareId)
        body.u16le(ORIGINATOR_ID)
        body.u16le(sourceDescriptor.size)
        body.u16le(capabilities.size + 4) // lengthCombinedCapabilities: the count and pad included
        body.bytes(sourceDescriptor)
        body.u16le(count)
        body.u16le(0) // pad2Octets
        body.bytes(capabilities)

        val content = body.toByteArray()
        val writer = RdpWriter(content.size + RdpShare.CONTROL_HEADER_SIZE)
        RdpShare.controlHeader(
            writer,
            content.size + RdpShare.CONTROL_HEADER_SIZE,
            RdpShare.PDUTYPE_CONFIRM_ACTIVE,
            userId,
        )
        writer.bytes(content)
        return writer.toByteArray()
    }

    private fun generalCapabilities(): ByteArray = RdpWriter(20).apply {
        u16le(OS_MAJOR_TYPE_WINDOWS)
        u16le(OS_MINOR_TYPE_WINDOWS_NT)
        u16le(PROTOCOL_VERSION)
        u16le(0) // pad2octetsA
        u16le(0) // compressionTypes, always zero
        u16le(
            FASTPATH_OUTPUT_SUPPORTED or LONG_CREDENTIALS_SUPPORTED or
                AUTORECONNECT_SUPPORTED or ENC_SALTED_CHECKSUM or NO_BITMAP_COMPRESSION_HDR,
        )
        u16le(0) // updateCapabilityFlag
        u16le(0) // remoteUnshareFlag
        u16le(0) // compressionLevel
        u8(1) // refreshRectSupport
        u8(1) // suppressOutputSupport
    }.toByteArray()

    private fun bitmapCapabilities(width: Int, height: Int): ByteArray = RdpWriter(24).apply {
        u16le(32) // preferredBitsPerPixel
        u16le(1) // receive1BitPerPixel
        u16le(1) // receive4BitsPerPixel
        u16le(1) // receive8BitsPerPixel
        u16le(width)
        u16le(height)
        u16le(0) // pad2octets
        u16le(1) // desktopResizeFlag
        u16le(1) // bitmapCompressionFlag
        u8(0) // highColorFlags
        u8(DRAW_ALLOW_DYNAMIC_COLOR_FIDELITY or DRAW_ALLOW_COLOR_SUBSAMPLING or DRAW_ALLOW_SKIP_ALPHA)
        u16le(1) // multipleRectangleSupport
        u16le(0) // pad2octetsB
    }.toByteArray()

    /**
     * Orders are declared unsupported (the 32-byte support array stays zero) but the set itself is
     * still sent: a server that receives no Order capability at all treats the client as pre-RDP5
     * and refuses the connection.
     */
    private fun orderCapabilities(): ByteArray = RdpWriter(84).apply {
        zeros(16) // terminalDescriptor
        u32le(0) // pad4octetsA
        u16le(1) // desktopSaveXGranularity
        u16le(20) // desktopSaveYGranularity
        u16le(0) // pad2octetsA
        u16le(1) // maximumOrderLevel
        u16le(0) // numberFonts
        u16le(ORDER_FLAG_NEGOTIATE or ORDER_FLAG_ZERO_BOUNDS_DELTAS)
        zeros(32) // orderSupport: nothing
        u16le(0) // textFlags
        u16le(0) // orderSupportExFlags
        u32le(0) // pad4octetsB
        u32le(0) // desktopSaveSize
        u16le(0) // pad2octetsC
        u16le(0) // pad2octetsD
        u16le(0) // textANSICodePage
        u16le(0) // pad2octetsE
    }.toByteArray()

    /** A cache with no entries: this client decodes every bitmap on arrival. */
    private fun noBitmapCache(): ByteArray = RdpWriter(36).apply {
        zeros(24) // pad1..pad6
        u16le(0).u16le(0) // Cache0Entries, Cache0MaximumCellSize
        u16le(0).u16le(0)
        u16le(0).u16le(0)
    }.toByteArray()

    private fun pointerCapabilities(): ByteArray = RdpWriter(6).apply {
        u16le(1) // colorPointerFlag
        u16le(PointerCache.CAPACITY) // colorPointerCacheSize
        u16le(PointerCache.CAPACITY) // pointerCacheSize
    }.toByteArray()

    private fun inputCapabilities(): ByteArray = RdpWriter(84).apply {
        u16le(
            INPUT_FLAG_SCANCODES or INPUT_FLAG_MOUSEX or INPUT_FLAG_UNICODE or
                INPUT_FLAG_FASTPATH_INPUT or INPUT_FLAG_FASTPATH_INPUT2,
        )
        u16le(0) // pad2octetsA
        u32le(RdpClientSettings.KEYBOARD_LAYOUT_US)
        u32le(RdpClientSettings.KEYBOARD_TYPE_IBM_ENHANCED)
        u32le(0) // keyboardSubType
        u32le(12) // keyboardFunctionKey
        zeros(64) // imeFileName
    }.toByteArray()

    private fun glyphCacheCapabilities(): ByteArray = RdpWriter(48).apply {
        repeat(10) { u16le(0).u16le(0) } // GlyphCache entries
        u32le(0) // FragCache
        u16le(0) // GlyphSupportLevel: GLYPH_SUPPORT_NONE
        u16le(0) // pad2octets
    }.toByteArray()

    private fun surfaceCommandsCapabilities(): ByteArray = RdpWriter(8).apply {
        u32le(Capabilities.SURFCMDS_SET_SURFACE_BITS or Capabilities.SURFCMDS_FRAME_MARKER)
        u32le(0) // reserved
    }.toByteArray()

    /**
     * The Bitmap Codecs set for RemoteFX. The capture flags say what the *client* can decode:
     * image mode (whole-frame tiles) and video mode (differential frames), both entropy-coded.
     */
    private fun bitmapCodecsCapabilities(): ByteArray {
        val properties = remoteFxClientCapabilities()
        return RdpWriter(properties.size + 24).apply {
            u8(1) // bitmapCodecCount
            bytes(Capabilities.GUID_REMOTEFX)
            u8(CODEC_ID_REMOTEFX)
            u16le(properties.size)
            bytes(properties)
        }.toByteArray()
    }

    /** TS_RFX_CLNT_CAPS_CONTAINER (MS-RDPRFX 2.2.1.1): one capability set, one icap entry. */
    private fun remoteFxClientCapabilities(): ByteArray {
        val icap = RdpWriter(8).apply {
            u16le(RFX_CAPS_VERSION)
            u8(RFX_TILE_SIZE)
            u8(0) // pad
            u8(RFX_CODEC_MODE_IMAGE or RFX_CODEC_MODE_VIDEO)
            u8(RFX_ENTROPY_RLGR3)
        }.toByteArray()

        val capset = RdpWriter(icap.size + 16).apply {
            u8(RFX_CAPSET_BLOCK_TYPE)
            u32le(icap.size + 13) // blockLen
            u8(RFX_CAPSET_CODEC_ID)
            u16le(RFX_CAPSET_TYPE)
            u16le(1) // numIcaps
            u16le(icap.size)
            bytes(icap)
        }.toByteArray()

        val caps = RdpWriter(capset.size + 8).apply {
            u16le(RFX_CAPS_BLOCK_TYPE)
            u32le(8) // blockLen of the caps header
            u16le(1) // numCapsets
            bytes(capset)
        }.toByteArray()

        return RdpWriter(caps.size + 16).apply {
            u32le(0) // length, patched below
            u32le(RFX_CAPTURE_FLAG_IMAGE_MODE)
            u32le(caps.size)
            bytes(caps)
        }.toByteArray().also { encoded ->
            // The container declares its own total size, which is only known now.
            encoded[0] = encoded.size.toByte()
            encoded[1] = (encoded.size ushr 8).toByte()
            encoded[2] = (encoded.size ushr 16).toByte()
            encoded[3] = (encoded.size ushr 24).toByte()
        }
    }

    private const val ORDER_FLAG_NEGOTIATE = 0x0002
    private const val ORDER_FLAG_ZERO_BOUNDS_DELTAS = 0x0008
    private const val ORIGINATOR_ID = 0x03EA
    private const val CHANNEL_CHUNK_SIZE = 1600
    private val SOURCE_DESCRIPTOR = byteArrayOf(0x4D, 0x53, 0x54, 0x53, 0x43, 0x00) // "MSTSC\0"

    // MS-RDPRFX 2.2.1.1 constants.
    private const val RFX_CAPS_BLOCK_TYPE = 0xCBC0
    private const val RFX_CAPSET_BLOCK_TYPE = 0xCBC1
    private const val RFX_CAPSET_CODEC_ID = 0x01
    private const val RFX_CAPSET_TYPE = 0xCFC0
    private const val RFX_CAPS_VERSION = 0x0100
    private const val RFX_TILE_SIZE = 0x40
    private const val RFX_CODEC_MODE_IMAGE = 0x01
    private const val RFX_CODEC_MODE_VIDEO = 0x02
    private const val RFX_ENTROPY_RLGR3 = 0x04
    private const val RFX_CAPTURE_FLAG_IMAGE_MODE = 0x00000001
}
