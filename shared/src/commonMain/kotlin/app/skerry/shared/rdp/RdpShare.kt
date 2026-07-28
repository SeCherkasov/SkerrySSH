package app.skerry.shared.rdp

/**
 * Share Control and Share Data headers (MS-RDPBCGR 2.2.8.1.1.1): the envelope every slow-path
 * session PDU travels in, inside an MCS send-data PDU.
 */
object RdpShare {
    /** TS_PROTOCOL_VERSION, carried in the high bits of pduType. */
    private const val PROTOCOL_VERSION = 0x10

    // Share Control pduType values (low four bits).
    const val PDUTYPE_DEMAND_ACTIVE = 0x1
    const val PDUTYPE_CONFIRM_ACTIVE = 0x3
    const val PDUTYPE_DEACTIVATE_ALL = 0x6
    const val PDUTYPE_DATA = 0x7
    const val PDUTYPE_SERVER_REDIRECT = 0xA

    // Share Data pduType2 values.
    const val PDUTYPE2_UPDATE = 2
    const val PDUTYPE2_CONTROL = 20
    const val PDUTYPE2_POINTER = 27
    const val PDUTYPE2_INPUT = 28
    const val PDUTYPE2_SYNCHRONIZE = 31
    const val PDUTYPE2_REFRESH_RECT = 33
    const val PDUTYPE2_PLAY_SOUND = 34
    const val PDUTYPE2_SUPPRESS_OUTPUT = 35
    const val PDUTYPE2_SHUTDOWN_REQUEST = 36
    const val PDUTYPE2_SAVE_SESSION_INFO = 38
    const val PDUTYPE2_FONT_LIST = 39
    const val PDUTYPE2_FONT_MAP = 40
    const val PDUTYPE2_SET_ERROR_INFO = 47
    const val PDUTYPE2_MONITOR_LAYOUT = 55
    const val PDUTYPE2_FRAME_ACKNOWLEDGE = 56

    /** STREAM_LOW: the priority every client sends its own PDUs at. */
    private const val STREAM_LOW = 1

    /** Bytes a Share Control header occupies. */
    const val CONTROL_HEADER_SIZE = 6

    /** Bytes a Share Data header occupies (Share Control header included). */
    const val DATA_HEADER_SIZE = CONTROL_HEADER_SIZE + 12

    /** A Share Control header for a PDU of [totalLength] bytes sent by [userId]. */
    fun controlHeader(writer: RdpWriter, totalLength: Int, pduType: Int, userId: Int) {
        writer.u16le(totalLength)
        writer.u16le(pduType or PROTOCOL_VERSION)
        writer.u16le(userId)
    }

    /**
     * Wrap [body] in a Share Data header. [shareId] comes from the server's Demand Active PDU — a
     * PDU carrying the wrong one is discarded by the server without a word.
     */
    fun dataPdu(shareId: Int, userId: Int, pduType2: Int, body: ByteArray): ByteArray {
        val total = DATA_HEADER_SIZE + body.size
        val writer = RdpWriter(total)
        controlHeader(writer, total, PDUTYPE_DATA, userId)
        writer.u32le(shareId)
        writer.u8(0) // pad1
        writer.u8(STREAM_LOW)
        // The whole packet, Share Control Header included (MS-RDPBCGR 2.2.8.1.1.1.2). Most Data PDUs
        // are accepted whatever this says, which is how six bytes short went unnoticed; the Refresh
        // Rect PDU is checked against it, and a length too short to hold the rectangles it declares
        // comes back as ERRINFO_INVALIDREFRESHRECTPDU (0x10D1) with the session ended.
        writer.u16le(total) // uncompressedLength
        writer.u8(pduType2)
        writer.u8(0) // compressedType: never compressed by this client
        writer.u16le(0) // compressedLength
        writer.bytes(body)
        return writer.toByteArray()
    }

    /** Header of a received Share Control PDU. */
    data class ControlHeader(val totalLength: Int, val pduType: Int, val pduSource: Int)

    fun readControlHeader(reader: RdpReader): ControlHeader {
        val totalLength = reader.u16le()
        val pduType = reader.u16le()
        val pduSource = reader.u16le()
        return ControlHeader(totalLength, pduType and 0x0F, pduSource)
    }

    /** Header of a received Share Data PDU, already past its Share Control header. */
    data class DataHeader(val shareId: Int, val pduType2: Int, val compressedType: Int)

    fun readDataHeader(reader: RdpReader): DataHeader {
        val shareId = reader.u32le()
        reader.skip(1) // pad1
        reader.skip(1) // streamId
        reader.skip(2) // uncompressedLength
        val pduType2 = reader.u8()
        val compressedType = reader.u8()
        reader.skip(2) // compressedLength
        return DataHeader(shareId, pduType2, compressedType)
    }
}

/**
 * The basic security header (MS-RDPBCGR 2.2.8.1.1.2.1). Under TLS the transport already encrypts,
 * so this carries no MAC — but the flags still classify the PDU, and the server requires them on
 * the two PDUs that are defined to have one.
 */
object RdpSecurityHeader {
    const val SEC_EXCHANGE_PKT = 0x0001
    const val SEC_ENCRYPT = 0x0008
    const val SEC_INFO_PKT = 0x0040
    const val SEC_LICENSE_PKT = 0x0080

    fun write(writer: RdpWriter, flags: Int) {
        writer.u16le(flags)
        writer.u16le(0) // flagsHi
    }

    /** Read the flags of a security header; the high half is reserved and ignored. */
    fun readFlags(reader: RdpReader): Int {
        val flags = reader.u16le()
        reader.skip(2)
        return flags
    }
}
