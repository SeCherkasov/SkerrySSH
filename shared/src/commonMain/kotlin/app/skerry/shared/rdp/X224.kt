package app.skerry.shared.rdp

/**
 * Security protocols a client may request and a server may select in the X.224 negotiation
 * (MS-RDPBCGR 2.2.1.1.1). Bit flags: the request carries every protocol we accept, the response
 * names the single one the server chose.
 *
 * [RDP] is "Standard RDP Security" — the legacy RC4 scheme with no transport encryption. It is
 * offered by servers that predate TLS support and is not requested by this client; a server that
 * selects it (by answering without a negotiation block) is refused rather than silently talked to
 * over a channel whose key exchange has been broken for a decade.
 */
object RdpSecurityProtocol {
    const val RDP = 0x00000000
    const val SSL = 0x00000001
    const val HYBRID = 0x00000002
    const val RDSTLS = 0x00000004
    const val HYBRID_EX = 0x00000008
}

/**
 * What the server answered in the X.224 Connection Confirm (MS-RDPBCGR 2.2.1.2).
 * [selectedProtocol] decides what happens next: [RdpSecurityProtocol.SSL] means "wrap the socket in
 * TLS and continue", [RdpSecurityProtocol.HYBRID] means "TLS, then CredSSP before continuing".
 *
 * [supportsGraphicsPipeline] (DYNVC_GFX_PROTOCOL_SUPPORTED) is the server saying it can serve the
 * MS-RDPEGFX pipeline over a dynamic virtual channel; [supportsExtendedClientData] gates the
 * extended client data blocks in the GCC conference request (monitor layout, multi-transport).
 */
data class X224NegotiationResponse(
    val selectedProtocol: Int,
    val supportsExtendedClientData: Boolean,
    val supportsGraphicsPipeline: Boolean,
    val supportsRestrictedAdmin: Boolean,
)

/**
 * The X.224 (ISO 8073 / COTP class 0) layer that carries the RDP connection sequence, framed by TPKT
 * (RFC 1006). Every slow-path PDU of a session travels as an X.224 Data TPDU inside a TPKT packet;
 * only fast-path updates bypass this header (they are recognised by their first byte — see
 * [Tpkt.isFastPath]).
 *
 * Pure byte building/parsing: the socket, TLS upgrade and CredSSP live in the transport.
 */
object X224 {
    private const val LI_CONNECTION_REQUEST = 0xE0
    private const val LI_CONNECTION_CONFIRM = 0xD0
    private const val LI_DATA = 0xF0
    private const val EOT = 0x80

    private const val TYPE_RDP_NEG_REQ = 0x01
    private const val TYPE_RDP_NEG_RSP = 0x02
    private const val TYPE_RDP_NEG_FAILURE = 0x03

    private const val NEG_BLOCK_SIZE = 8
    private const val X224_CR_FIXED = 7
    private const val X224_DATA_HEADER = 3

    private const val RSP_EXTENDED_CLIENT_DATA_SUPPORTED = 0x01
    private const val RSP_DYNVC_GFX_PROTOCOL_SUPPORTED = 0x02
    private const val RSP_RESTRICTED_ADMIN_MODE_SUPPORTED = 0x08

    /** Longest user name we are willing to disclose in the routing cookie (see [connectionRequest]). */
    private const val MAX_COOKIE_LENGTH = 64

    /**
     * Longest broker token we will send. Farm tokens are longer than a user name (`tsv://MS Terminal
     * Services Plugin.1.<collection>`), and the ceiling is the X.224 length indicator: one byte for
     * the fixed header, the token and the 8-byte negotiation block together.
     */
    private const val MAX_LOAD_BALANCE_LENGTH = 192

    /**
     * Client X.224 Connection Request (MS-RDPBCGR 2.2.1.1) asking for [requestedProtocols].
     *
     * [cookie] is the `mstshash` routing token a load balancer uses to pin the session to the host
     * that already has this user's session. It is optional, unencrypted and attacker-visible, so it
     * is dropped rather than sanitized when it isn't plain printable ASCII: the token is CRLF-framed,
     * and a name carrying a newline would append a header line of the profile's choosing.
     *
     * [loadBalanceInfo] is a Remote Desktop farm's own routing token (`loadbalanceinfo` in an `.rdp`
     * file). It takes the cookie's place — the request carries one routing token, and the broker's
     * is the one that decides which host of the collection answers. Same sanitizing rule: a token
     * that isn't printable ASCII is dropped, which falls back to the user cookie.
     */
    fun connectionRequest(
        requestedProtocols: Int,
        cookie: String?,
        loadBalanceInfo: String? = null,
    ): ByteArray {
        val token = loadBalancerToken(loadBalanceInfo) ?: routingToken(cookie)
        val x224Length = X224_CR_FIXED + token.size + NEG_BLOCK_SIZE
        val writer = RdpWriter(Tpkt.HEADER_SIZE + x224Length)
        writer.bytes(Tpkt.header(Tpkt.HEADER_SIZE + x224Length))
        // Length indicator counts everything after itself.
        writer.u8(x224Length - 1)
        writer.u8(LI_CONNECTION_REQUEST)
        writer.u16be(0) // DST-REF
        writer.u16be(0) // SRC-REF
        writer.u8(0) // class 0, no options
        writer.bytes(token)
        writer.u8(TYPE_RDP_NEG_REQ)
        writer.u8(0) // flags: no restricted admin, no correlation info
        writer.u16le(NEG_BLOCK_SIZE)
        writer.u32le(requestedProtocols)
        return writer.toByteArray()
    }

    /**
     * Parse the server's Connection Confirm (MS-RDPBCGR 2.2.1.2).
     *
     * @throws RdpNegotiationException the server answered RDP_NEG_FAILURE
     * @throws RdpProtocolException the packet is not a well-formed Connection Confirm
     */
    fun parseConnectionConfirm(packet: ByteArray): X224NegotiationResponse {
        val reader = Tpkt.reader(packet)
        val lengthIndicator = reader.u8()
        val code = reader.u8()
        if (code != LI_CONNECTION_CONFIRM) {
            throw RdpProtocolException("expected X.224 Connection Confirm, got code 0x${code.toString(16)}")
        }
        reader.skip(5) // DST-REF, SRC-REF, class
        // The length indicator counts itself out but covers the negotiation block; anything the
        // server appended beyond it is not ours to read.
        val negotiationLength = lengthIndicator - (X224_CR_FIXED - 1)
        if (negotiationLength <= 0) {
            // No negotiation block: the server is answering a request it did not understand, which
            // means Standard RDP Security. We never asked for it (see [RdpSecurityProtocol.RDP]).
            return X224NegotiationResponse(
                selectedProtocol = RdpSecurityProtocol.RDP,
                supportsExtendedClientData = false,
                supportsGraphicsPipeline = false,
                supportsRestrictedAdmin = false,
            )
        }
        val negotiation = reader.slice(minOf(negotiationLength, reader.remaining))
        return when (val type = negotiation.u8()) {
            TYPE_RDP_NEG_RSP -> {
                val flags = negotiation.u8()
                negotiation.u16le() // declared block length, fixed at 8 — the reader bounds us already
                val selected = negotiation.u32le()
                X224NegotiationResponse(
                    selectedProtocol = selected,
                    supportsExtendedClientData = flags and RSP_EXTENDED_CLIENT_DATA_SUPPORTED != 0,
                    supportsGraphicsPipeline = flags and RSP_DYNVC_GFX_PROTOCOL_SUPPORTED != 0,
                    supportsRestrictedAdmin = flags and RSP_RESTRICTED_ADMIN_MODE_SUPPORTED != 0,
                )
            }

            TYPE_RDP_NEG_FAILURE -> {
                negotiation.u8() // flags
                negotiation.u16le() // length
                val code = negotiation.u32le()
                throw RdpNegotiationException(
                    reason = RdpNegotiationFailure.of(code),
                    message = "server refused the requested security protocols (failure code $code)",
                )
            }

            else -> throw RdpProtocolException("unknown negotiation response type $type")
        }
    }

    /**
     * TPKT + X.224 Data TPDU header for a slow-path PDU of [payloadLength] bytes. The payload is
     * written straight after it, so the caller can build large PDUs without a second copy.
     */
    fun dataHeader(payloadLength: Int): ByteArray {
        val total = Tpkt.HEADER_SIZE + X224_DATA_HEADER + payloadLength
        return RdpWriter(Tpkt.HEADER_SIZE + X224_DATA_HEADER)
            .bytes(Tpkt.header(total))
            .u8(2) // length indicator of the data TPDU
            .u8(LI_DATA)
            .u8(EOT)
            .toByteArray()
    }

    /**
     * Strip the TPKT + X.224 Data header off a received slow-path packet, leaving the MCS payload.
     */
    fun dataPayload(packet: ByteArray): RdpReader {
        val reader = Tpkt.reader(packet)
        val lengthIndicator = reader.u8()
        val code = reader.u8()
        if (code != LI_DATA) {
            throw RdpProtocolException("expected X.224 Data TPDU, got code 0x${code.toString(16)}")
        }
        // LI counts the bytes after itself; class 0 data TPDUs carry exactly the EOT byte, but a
        // conformant peer may pad the header, so skip whatever it declared instead of assuming one.
        reader.skip(lengthIndicator - 1)
        return reader
    }

    /** The broker's token as its own header line, or `null` when there is none worth sending. */
    private fun loadBalancerToken(info: String?): ByteArray? {
        val value = info?.takeIf {
            it.isNotEmpty() && it.length <= MAX_LOAD_BALANCE_LENGTH && it.all(::isSafeCookieChar)
        }
            ?: return null
        return "$value\r\n".encodeToByteArray()
    }

    private fun routingToken(cookie: String?): ByteArray {
        val name = cookie?.takeIf { it.isNotEmpty() && it.length <= MAX_COOKIE_LENGTH && it.all(::isSafeCookieChar) }
            ?: return ByteArray(0)
        return "Cookie: mstshash=$name\r\n".encodeToByteArray()
    }

    private fun isSafeCookieChar(ch: Char): Boolean = ch.code in 0x20..0x7E
}

/** TPKT framing (RFC 1006): a 4-byte header in front of every slow-path RDP packet. */
object Tpkt {
    const val VERSION = 3
    const val HEADER_SIZE = 4

    /** Largest packet we will read off the wire; a length field is a remote peer's claim, not a fact. */
    const val MAX_PACKET_SIZE = 64 * 1024

    /** TPKT header declaring a packet of [totalLength] bytes (header included). */
    fun header(totalLength: Int): ByteArray =
        RdpWriter(HEADER_SIZE).u8(VERSION).u8(0).u16be(totalLength).toByteArray()

    /**
     * Whether the first byte of a server packet starts a fast-path update rather than a TPKT packet.
     * Fast-path PDUs (MS-RDPBCGR 2.2.9.1.2) put an action code of 0 in the low two bits, which can
     * never collide with TPKT's version 3 — that is exactly why the encoding was chosen.
     */
    fun isFastPath(firstByte: Int): Boolean = firstByte and 0x03 == 0

    /** A reader positioned just after a validated TPKT header of [packet]. */
    fun reader(packet: ByteArray): RdpReader {
        val reader = RdpReader(packet)
        val version = reader.u8()
        if (version != VERSION) throw RdpProtocolException("bad TPKT version $version")
        reader.u8() // reserved
        val declared = reader.u16be()
        if (declared != packet.size) {
            throw RdpProtocolException("TPKT length $declared does not match packet of ${packet.size}")
        }
        return reader
    }

    /**
     * Read one whole packet — TPKT or fast-path — from [source], header included. Both framings are
     * read here because the server interleaves them on the same socket and only the first byte says
     * which one arrived.
     */
    suspend fun readPacket(source: RdpSource): ByteArray {
        val head = ByteArray(4)
        source.readFully(head, 0, 1)
        val first = head[0].toInt() and 0xFF
        return if (isFastPath(first)) readFastPath(source, head, first) else readTpkt(source, head)
    }

    private suspend fun readTpkt(source: RdpSource, head: ByteArray): ByteArray {
        if (head[0].toInt() and 0xFF != VERSION) {
            throw RdpProtocolException("bad TPKT version ${head[0].toInt() and 0xFF}")
        }
        source.readFully(head, 1, 3)
        val length = ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
        if (length < HEADER_SIZE || length > MAX_PACKET_SIZE) {
            throw RdpProtocolException("TPKT length $length out of range")
        }
        val packet = ByteArray(length)
        head.copyInto(packet, 0, 0, HEADER_SIZE)
        source.readFully(packet, HEADER_SIZE, length - HEADER_SIZE)
        return packet
    }

    /**
     * Fast-path header: an action/flags byte, then a length that is 1 byte when its high bit is
     * clear and 2 big-endian bytes (with that bit masked off) when it is set.
     */
    private suspend fun readFastPath(source: RdpSource, head: ByteArray, first: Int): ByteArray {
        source.readFully(head, 1, 1)
        val lengthByte = head[1].toInt() and 0xFF
        val headerSize: Int
        val length: Int
        if (lengthByte and 0x80 != 0) {
            source.readFully(head, 2, 1)
            headerSize = 3
            length = ((lengthByte and 0x7F) shl 8) or (head[2].toInt() and 0xFF)
        } else {
            headerSize = 2
            length = lengthByte
        }
        if (length < headerSize || length > MAX_PACKET_SIZE) {
            throw RdpProtocolException("fast-path length $length out of range")
        }
        val packet = ByteArray(length)
        head.copyInto(packet, 0, 0, headerSize)
        packet[0] = first.toByte()
        source.readFully(packet, headerSize, length - headerSize)
        return packet
    }
}
