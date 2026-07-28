package app.skerry.shared.rdp

/**
 * T.124 Generic Conference Control: the envelope MCS connect PDUs carry the RDP-specific settings
 * blocks in (MS-RDPBCGR 2.2.1.3/2.2.1.4). Everything outside the user data is fixed framing — the
 * conference is always named "1", there is always exactly one user-data set, and the H.221 key is
 * always "Duca" one way and "McDn" the other.
 */
object Gcc {
    private val T124_OID = intArrayOf(0, 0, 20, 124, 0, 1)
    private val CLIENT_KEY = "Duca".encodeToByteArray()
    private val SERVER_KEY = "McDn".encodeToByteArray()

    /** Bytes of ConferenceCreateRequest framing that sit between the length and the user data. */
    private const val REQUEST_FRAMING = 12

    // TS_UD_HEADER::type (MS-RDPBCGR 2.2.1.3.1). Client blocks are 0xC0xx, server blocks 0x0Cxx.
    private const val CS_CORE = 0xC001
    private const val CS_SECURITY = 0xC002
    private const val CS_NET = 0xC003
    private const val CS_CLUSTER = 0xC004
    private const val SC_CORE = 0x0C01
    private const val SC_SECURITY = 0x0C02
    private const val SC_NET = 0x0C03

    private const val RDP_VERSION_5_PLUS = 0x00080004
    private const val COLOR_8BPP = 0xCA01
    private const val SAS_SEQUENCE = 0xAA03
    private const val HIGH_COLOR_24BPP = 24

    // TS_UD_CS_CORE::supportedColorDepths / earlyCapabilityFlags (2.2.1.3.2).
    private const val SUPPORT_24BPP = 0x01
    private const val SUPPORT_16BPP = 0x02
    private const val SUPPORT_15BPP = 0x04
    private const val SUPPORT_32BPP = 0x08
    private const val EARLY_SUPPORT_ERRINFO_PDU = 0x0001
    private const val EARLY_WANT_32BPP_SESSION = 0x0002
    private const val EARLY_SUPPORT_DYNVC_GFX = 0x0100

    // TS_UD_CS_CLUSTER::Flags (2.2.1.3.5).
    private const val REDIRECTION_SUPPORTED = 0x01
    private const val REDIRECTION_VERSION4 = 0x03

    /** REDIRECTED_SESSIONID_FIELD_VALID (MS-RDPBCGR 2.2.1.3.5). */
    private const val REDIRECTED_SESSION_FIELD_VALID = 0x02

    // CHANNEL_DEF::options (2.2.1.3.4.1).
    private const val CHANNEL_OPTION_INITIALIZED = 0x80000000.toInt()
    private const val CHANNEL_OPTION_COMPRESS_RDP = 0x00800000
    private const val CHANNEL_OPTION_SHOW_PROTOCOL = 0x00200000

    private const val CLIENT_NAME_BYTES = 32
    private const val IME_FILE_NAME_BYTES = 64
    private const val PRODUCT_ID_BYTES = 64

    /** Wrap [userData] in ConnectData + ConferenceCreateRequest, as the client half of the exchange. */
    fun conferenceCreateRequest(userData: ByteArray): ByteArray {
        val writer = RdpWriter(userData.size + 32)
        Per.choice(writer, 0) // From Key select object (0)
        Per.objectIdentifier(writer, T124_OID)
        // connectPDU length: the framing below plus the user data and its own length determinant.
        val userDataLengthSize = if (userData.size < 0x80) 1 else 2
        Per.length(writer, REQUEST_FRAMING + userDataLengthSize + userData.size)
        Per.choice(writer, 0) // ConnectGCCPDU: conferenceCreateRequest
        Per.selection(writer, 0x08) // only userData is present
        Per.numericString(writer, "1", minLength = 1) // ConferenceName::numeric
        Per.padding(writer, 1)
        Per.numberOfSets(writer, 1)
        Per.choice(writer, 0xC0) // UserData::value present, key is h221NonStandard
        Per.octetString(writer, CLIENT_KEY, minLength = 4)
        Per.octetString(writer, userData, minLength = 0)
        return writer.toByteArray()
    }

    /**
     * Unwrap the server's ConferenceCreateResponse and return the settings blocks it carries.
     *
     * The framing is walked rather than skipped by a fixed count: the tag is variable-length, and a
     * server that pads differently would otherwise shift every block that follows.
     */
    fun parseConferenceCreateResponse(reader: RdpReader): ByteArray {
        Per.readChoice(reader) // From Key select object
        Per.readObjectIdentifier(reader, T124_OID)
        Per.readLength(reader) // connectPDU length, ignored: the BER container already bounds us
        // The choice byte packs the ConnectGCCPDU extension bit (7), the three-bit alternative
        // (6..4) and the ConferenceCreateResponse flags; `0x14` in the dump is alternative 1 with
        // userData present.
        val choice = Per.readChoice(reader)
        if (choice and 0x80 != 0 || (choice shr 4) and 0x07 != 1) {
            throw RdpProtocolException("expected a GCC conferenceCreateResponse, got 0x${choice.toString(16)}")
        }
        Per.readUserId(reader) // nodeID
        reader.skip(reader.u8()) // tag, length-prefixed
        val result = (reader.u8() shr 4) and 0x07
        if (result != 0) throw RdpProtocolException("GCC conference create failed with result $result")
        val sets = reader.u8()
        if (sets != 1) throw RdpProtocolException("expected one GCC user data set, got $sets")
        Per.readChoice(reader) // UserData::value present, h221NonStandard
        val key = reader.bytes(1 + SERVER_KEY.size).copyOfRange(1, 1 + SERVER_KEY.size)
        if (!key.contentEquals(SERVER_KEY)) {
            throw RdpProtocolException("unexpected H.221 key ${key.decodeToString()}")
        }
        return reader.bytes(Per.readLength(reader))
    }

    /** The client settings blocks (TS_UD_CS_*) that travel inside the conference create request. */
    fun clientUserData(settings: RdpClientSettings): ByteArray {
        val writer = RdpWriter(512)
        writeCoreData(writer, settings)
        writeClusterData(writer, settings.redirectedSessionId)
        writeSecurityData(writer)
        if (settings.channels.isNotEmpty()) writeNetworkData(writer, settings.channels)
        return writer.toByteArray()
    }

    /**
     * Parse the server settings blocks (TS_UD_SC_*). Unknown block types are skipped by their own
     * length: the set grows with every RDP version, and a client that choked on an unfamiliar block
     * would stop working the day the server learns a new one.
     */
    fun parseServerUserData(userData: ByteArray): ServerUserData {
        val reader = RdpReader(userData)
        var version = 0
        var requestedProtocols = 0
        var ioChannelId = 0
        var channelIds = IntArray(0)
        while (reader.remaining >= 4) {
            val type = reader.u16le()
            val length = reader.u16le()
            if (length < 4) throw RdpProtocolException("server data block of length $length")
            val block = reader.slice(length - 4)
            when (type) {
                SC_CORE -> {
                    version = block.u32le()
                    if (block.remaining >= 4) requestedProtocols = block.u32le()
                }

                SC_NET -> {
                    ioChannelId = block.u16le()
                    val count = block.u16le()
                    // The count is the server's claim; the block's own length is the fact.
                    if (count * 2 > block.remaining) {
                        throw RdpProtocolException("server announced $count channels but the block holds fewer")
                    }
                    channelIds = IntArray(count) { block.u16le() }
                }

                SC_SECURITY -> Unit // encryption is the TLS layer's job; the legacy fields are ignored
                else -> Unit
            }
        }
        if (ioChannelId == 0) throw RdpProtocolException("server did not name an I/O channel")
        return ServerUserData(version, requestedProtocols, ioChannelId, channelIds)
    }

    private fun writeCoreData(writer: RdpWriter, settings: RdpClientSettings) {
        val start = writer.size
        writer.u16le(CS_CORE).u16le(0) // length patched once the body is written
        writer.u32le(RDP_VERSION_5_PLUS)
        writer.u16le(settings.desktopWidth)
        writer.u16le(settings.desktopHeight)
        // The legacy depth fields are frozen at 8bpp in every modern client; the real depth is
        // negotiated through highColorDepth/supportedColorDepths below.
        writer.u16le(COLOR_8BPP)
        writer.u16le(SAS_SEQUENCE)
        writer.u32le(settings.keyboardLayout)
        writer.u32le(settings.clientBuild)
        writeFixedUtf16(writer, settings.clientName, CLIENT_NAME_BYTES)
        writer.u32le(settings.keyboardType)
        writer.u32le(0) // keyboardSubType
        writer.u32le(settings.keyboardFunctionKeys)
        writer.zeros(IME_FILE_NAME_BYTES)
        writer.u16le(COLOR_8BPP) // postBeta2ColorDepth
        writer.u16le(1) // clientProductId
        writer.u32le(0) // serialNumber
        writer.u16le(HIGH_COLOR_24BPP)
        writer.u16le(SUPPORT_24BPP or SUPPORT_16BPP or SUPPORT_15BPP or SUPPORT_32BPP)
        var earlyCapabilityFlags = EARLY_SUPPORT_ERRINFO_PDU or EARLY_WANT_32BPP_SESSION
        if (settings.wantsGraphicsPipeline) earlyCapabilityFlags = earlyCapabilityFlags or EARLY_SUPPORT_DYNVC_GFX
        writer.u16le(earlyCapabilityFlags)
        writer.zeros(PRODUCT_ID_BYTES) // clientDigProductId
        writer.u8(0) // connectionType, ignored without RNS_UD_CS_VALID_CONNECTION_TYPE
        writer.u8(0) // pad1octet
        writer.u32le(settings.selectedProtocol)
        writer.patchU16le(start + 2, writer.size - start)
    }

    private fun writeSecurityData(writer: RdpWriter) {
        writer.u16le(CS_SECURITY).u16le(12)
        // Zero on both: Standard RDP Security is never negotiated by this client, and advertising
        // its methods would let a server pick one.
        writer.u32le(0) // encryptionMethods
        writer.u32le(0) // extEncryptionMethods
    }

    /**
     * [redirectedSessionId] is what a Server Redirection Packet named (0 on a first connection). The
     * SESSION_ID_FIELD_VALID bit is what makes the target treat this as the redirected half of that
     * session instead of a new logon, so it is set only when there is a session to rejoin.
     */
    private fun writeClusterData(writer: RdpWriter, redirectedSessionId: Int) {
        writer.u16le(CS_CLUSTER).u16le(12)
        val sessionIdValid = if (redirectedSessionId != 0) REDIRECTED_SESSION_FIELD_VALID else 0
        writer.u32le((REDIRECTION_VERSION4 shl 2) or REDIRECTION_SUPPORTED or sessionIdValid)
        writer.u32le(redirectedSessionId)
    }

    private fun writeNetworkData(writer: RdpWriter, channels: List<String>) {
        val start = writer.size
        writer.u16le(CS_NET).u16le(0)
        writer.u32le(channels.size)
        for (name in channels) {
            val bytes = name.encodeToByteArray()
            writer.bytes(bytes).zeros(RdpClientSettings.CHANNEL_NAME_SIZE - bytes.size)
            writer.u32le(CHANNEL_OPTION_INITIALIZED or CHANNEL_OPTION_COMPRESS_RDP or CHANNEL_OPTION_SHOW_PROTOCOL)
        }
        writer.patchU16le(start + 2, writer.size - start)
    }

    /** UTF-16LE into a fixed-size, null-padded field; over-long text is cut to fit with its terminator. */
    private fun writeFixedUtf16(writer: RdpWriter, text: String, byteCount: Int) {
        val maxChars = byteCount / 2 - 1
        val trimmed = if (text.length > maxChars) text.take(maxChars) else text
        writer.utf16le(trimmed)
        writer.zeros(byteCount - trimmed.length * 2)
    }
}

/**
 * What the server said about itself in the GCC conference create response: [ioChannelId] is the MCS
 * channel the session's own PDUs travel on, [channelIds] are the ids of the virtual channels we
 * asked for, in the order we asked. Both have to be joined before the connection sequence continues.
 */
data class ServerUserData(
    val serverVersion: Int,
    val clientRequestedProtocols: Int,
    val ioChannelId: Int,
    val channelIds: IntArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ServerUserData && serverVersion == other.serverVersion &&
            clientRequestedProtocols == other.clientRequestedProtocols &&
            ioChannelId == other.ioChannelId && channelIds.contentEquals(other.channelIds)

    override fun hashCode(): Int =
        ((serverVersion * 31 + clientRequestedProtocols) * 31 + ioChannelId) * 31 + channelIds.contentHashCode()
}
