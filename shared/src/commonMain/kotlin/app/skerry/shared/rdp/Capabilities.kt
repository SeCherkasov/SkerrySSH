package app.skerry.shared.rdp

/** Capability set types (MS-RDPBCGR 2.2.7). Only the ones this client reads or writes are named. */
object CapabilitySetType {
    const val GENERAL = 1
    const val BITMAP = 2
    const val ORDER = 3
    const val BITMAP_CACHE = 4
    const val POINTER = 8
    const val SHARE = 9
    const val COLOR_CACHE = 10
    const val SOUND = 12
    const val INPUT = 13
    const val FONT = 14
    const val BRUSH = 15
    const val GLYPH_CACHE = 16
    const val OFFSCREEN_CACHE = 17
    const val BITMAP_CACHE_HOST_SUPPORT = 18
    const val BITMAP_CACHE_V2 = 19
    const val VIRTUAL_CHANNEL = 20
    const val DRAW_NINE_GRID_CACHE = 21
    const val DRAW_GDIPLUS = 22
    const val RAIL = 23
    const val WINDOW = 24
    const val COMP_DESK = 25
    const val MULTIFRAGMENT_UPDATE = 26
    const val LARGE_POINTER = 27
    const val SURFACE_COMMANDS = 28
    const val BITMAP_CODECS = 29
    const val FRAME_ACKNOWLEDGE = 30
}

/**
 * What the server said it can do, read out of its Demand Active PDU (MS-RDPBCGR 2.2.1.13.1).
 *
 * Only the fields that change client behaviour are kept. [desktopWidth]/[desktopHeight] are the
 * session's real size — the server may hand back something other than what was asked for, and
 * everything downstream (framebuffer, coordinates, resize) has to follow the server, not the wish.
 */
data class ServerCapabilities(
    /** The share this session runs in; every data PDU we send has to carry it back. */
    val shareId: Int,
    val desktopWidth: Int,
    val desktopHeight: Int,
    val preferredBitsPerPixel: Int,
    val desktopResizeSupported: Boolean,
    val refreshRectSupported: Boolean,
    val suppressOutputSupported: Boolean,
    val fastPathOutputSupported: Boolean,
    val noBitmapCompressionHeader: Boolean,
    val surfaceCommandsSupported: Boolean,
    val frameAcknowledgeSupported: Boolean,
    val maxRequestSize: Int,
    val supportedCodecs: List<RdpCodecId>,
)

/** Bitmap codecs that can be negotiated in the Bitmap Codecs capability set (MS-RDPBCGR 2.2.7.2.10). */
enum class RdpCodecId { RemoteFx, NsCodec, ImageRemoteFx }

/** Parsing of capability sets out of a Demand Active PDU. */
object Capabilities {
    // TS_GENERAL_CAPABILITYSET::extraFlags.
    private const val FASTPATH_OUTPUT_SUPPORTED = 0x0001
    private const val NO_BITMAP_COMPRESSION_HDR = 0x0400

    // TS_SURFCMDS_CAPABILITYSET::cmdFlags.
    const val SURFCMDS_SET_SURFACE_BITS = 0x00000002
    const val SURFCMDS_FRAME_MARKER = 0x00000010
    const val SURFCMDS_STREAM_SURFACE_BITS = 0x00000040

    /**
     * GUIDs of the codecs this client recognises (MS-RDPRFX 2.2.2.1.1 / MS-RDPNSC 2.2.1). They are
     * written as their wire bytes, which is how they appear in the capability set — not as the
     * mixed-endian rendering a GUID normally prints as.
     */
    val GUID_REMOTEFX = guid("122F7776 72BD6344 AFB3B73C 9C6F7886")
    val GUID_NSCODEC = guid("B91B8DCA 0F004F15 589FAE2D 1A87E2D6")
    val GUID_IMAGE_REMOTEFX = guid("D4CC4427 8A9D744E 803C0ECB EEA19C54")

    private fun guid(text: String): ByteArray {
        val digits = text.filterNot { it == ' ' }
        return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * Read the capability sets of a Demand Active PDU body (positioned right after the Share Control
     * header) and return what matters, with defaults for anything the server left out.
     *
     * Unknown sets are skipped by their own length: the list grows with every RDP version.
     */
    fun parseDemandActive(reader: RdpReader): ServerCapabilities {
        val shareId = reader.u32le()
        val sourceDescriptorLength = reader.u16le()
        val combinedCapabilitiesLength = reader.u16le()
        reader.skip(sourceDescriptorLength)
        val body = reader.slice(minOf(combinedCapabilitiesLength, reader.remaining))
        val count = body.u16le()
        body.skip(2) // pad2Octets

        var width = 0
        var height = 0
        var bpp = 16
        var resize = false
        var refreshRect = false
        var suppressOutput = false
        var fastPathOutput = false
        var noCompressionHeader = false
        var surfaceCommands = false
        var frameAcknowledge = false
        var maxRequestSize = 0
        val codecs = mutableListOf<RdpCodecId>()

        repeat(count) {
            if (body.remaining < 4) return@repeat
            val type = body.u16le()
            val length = body.u16le()
            if (length < 4) throw RdpProtocolException("capability set of length $length")
            val set = body.slice(minOf(length - 4, body.remaining))
            when (type) {
                CapabilitySetType.GENERAL -> {
                    set.skip(4) // osMajorType, osMinorType
                    set.skip(2) // protocolVersion
                    set.skip(2) // pad2octetsA
                    set.skip(2) // generalCompressionTypes, which the client writes as zero too
                    val extraFlags = set.u16le()
                    fastPathOutput = extraFlags and FASTPATH_OUTPUT_SUPPORTED != 0
                    noCompressionHeader = extraFlags and NO_BITMAP_COMPRESSION_HDR != 0
                    set.skip(2) // updateCapabilityFlag
                    set.skip(2) // remoteUnshareFlag
                    set.skip(2) // compressionLevel
                    refreshRect = set.u8() != 0
                    suppressOutput = set.u8() != 0
                }

                CapabilitySetType.BITMAP -> {
                    bpp = set.u16le()
                    set.skip(6) // receive1BitPerPixel, receive4BitsPerPixel, receive8BitsPerPixel
                    width = set.u16le()
                    height = set.u16le()
                    set.skip(2) // pad2octets
                    resize = set.u16le() != 0
                }

                CapabilitySetType.MULTIFRAGMENT_UPDATE -> maxRequestSize = set.u32le()

                CapabilitySetType.SURFACE_COMMANDS -> {
                    val cmdFlags = set.u32le()
                    surfaceCommands = cmdFlags and SURFCMDS_SET_SURFACE_BITS != 0
                }

                CapabilitySetType.FRAME_ACKNOWLEDGE -> frameAcknowledge = true

                CapabilitySetType.BITMAP_CODECS -> codecs.addAll(parseCodecs(set))
            }
        }
        if (width <= 0 || height <= 0) throw RdpProtocolException("server announced no desktop size")
        return ServerCapabilities(
            shareId = shareId,
            desktopWidth = width,
            desktopHeight = height,
            preferredBitsPerPixel = bpp,
            desktopResizeSupported = resize,
            refreshRectSupported = refreshRect,
            suppressOutputSupported = suppressOutput,
            fastPathOutputSupported = fastPathOutput,
            noBitmapCompressionHeader = noCompressionHeader,
            surfaceCommandsSupported = surfaceCommands,
            frameAcknowledgeSupported = frameAcknowledge,
            maxRequestSize = maxRequestSize,
            supportedCodecs = codecs,
        )
    }

    private fun parseCodecs(set: RdpReader): List<RdpCodecId> {
        val count = set.u8()
        val found = mutableListOf<RdpCodecId>()
        repeat(count) {
            if (set.remaining < 19) return found
            val guid = set.bytes(16)
            set.u8() // codecId as this server numbers it
            val propertiesLength = set.u16le()
            if (propertiesLength > set.remaining) return found
            set.skip(propertiesLength)
            when {
                guid.contentEquals(GUID_REMOTEFX) -> found.add(RdpCodecId.RemoteFx)
                guid.contentEquals(GUID_NSCODEC) -> found.add(RdpCodecId.NsCodec)
                guid.contentEquals(GUID_IMAGE_REMOTEFX) -> found.add(RdpCodecId.ImageRemoteFx)
            }
        }
        return found
    }
}
