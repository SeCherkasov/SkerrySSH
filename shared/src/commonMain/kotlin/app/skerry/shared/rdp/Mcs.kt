package app.skerry.shared.rdp

/**
 * A decoded MCS domain PDU. Only two matter to a client: session data on some channel, and the
 * server tearing the domain down. Anything else is surfaced as [Other] so the read loop can say what
 * it saw rather than mis-reading it as data.
 */
sealed interface McsDomainPdu {
    /** Session data on [channelId]; [payload] is bounded to the declared length. */
    data class Data(val channelId: Int, val initiator: Int, val payload: RdpReader) : McsDomainPdu

    /** The server ended the domain (MCS Disconnect Provider Ultimatum, MS-RDPBCGR 2.2.2.3). */
    data class Disconnect(val reason: Int) : McsDomainPdu

    data class Other(val type: Int) : McsDomainPdu
}

/**
 * MCS domain PDUs of the connection sequence and of the live session (MS-RDPBCGR 2.2.1.5–2.2.1.9,
 * 2.2.2.3). Each is a handful of PER-packed bytes, built and parsed by hand: the choice byte carries
 * the alternative in its top six bits and structural flags in the rest, which no generic PER encoder
 * would express more readably.
 */
object Mcs {
    // DomainMCSPDU alternatives (T.125), as they appear in the top six bits of the choice byte.
    private const val PDU_ERECT_DOMAIN_REQUEST = 1
    private const val PDU_DISCONNECT_PROVIDER_ULTIMATUM = 8
    private const val PDU_ATTACH_USER_REQUEST = 10
    private const val PDU_ATTACH_USER_CONFIRM = 11
    private const val PDU_CHANNEL_JOIN_REQUEST = 14
    private const val PDU_CHANNEL_JOIN_CONFIRM = 15
    private const val PDU_SEND_DATA_REQUEST = 25
    private const val PDU_SEND_DATA_INDICATION = 26

    /**
     * dataPriority = high, segmentation begin+end: one PDU carries one whole payload. RDP never
     * splits an MCS payload, so every deployed client sends exactly this byte.
     */
    private const val DATA_FLAGS = 0x70

    private const val RESULT_SUCCESSFUL = 0

    /** Fixed part of a Send Data PDU: choice, initiator, channel, flags. */
    private const val SEND_DATA_HEADER = 6

    fun erectDomainRequest(): ByteArray {
        val body = RdpWriter(8)
        Per.choice(body, PDU_ERECT_DOMAIN_REQUEST shl 2)
        Per.integer(body, 0) // subHeight
        Per.integer(body, 0) // subInterval
        return packet(body.toByteArray())
    }

    fun attachUserRequest(): ByteArray = packet(byteArrayOf((PDU_ATTACH_USER_REQUEST shl 2).toByte()))

    /**
     * Read the user channel id the server assigned.
     *
     * @throws RdpProtocolException the server refused, or answered without an initiator
     */
    fun parseAttachUserConfirm(reader: RdpReader): Int {
        val choice = reader.u8()
        expectPdu(choice, PDU_ATTACH_USER_CONFIRM)
        val initiatorPresent = (choice shr 1) and 0x01 == 1
        val result = reader.u8()
        if (result != RESULT_SUCCESSFUL) throw RdpProtocolException("MCS attach user refused with result $result")
        if (!initiatorPresent) throw RdpProtocolException("MCS attach user confirm carried no user id")
        return Per.readUserId(reader)
    }

    fun channelJoinRequest(userId: Int, channelId: Int): ByteArray {
        val body = RdpWriter(8)
        Per.choice(body, PDU_CHANNEL_JOIN_REQUEST shl 2)
        Per.userId(body, userId)
        body.u16be(channelId)
        return packet(body.toByteArray())
    }

    /** Read the channel the server confirmed; the id is echoed so a client can match it to its request. */
    fun parseChannelJoinConfirm(reader: RdpReader): Int {
        val choice = reader.u8()
        expectPdu(choice, PDU_CHANNEL_JOIN_CONFIRM)
        val channelIdPresent = (choice shr 1) and 0x01 == 1
        val result = reader.u8()
        if (result != RESULT_SUCCESSFUL) throw RdpProtocolException("MCS channel join refused with result $result")
        Per.readUserId(reader) // initiator
        val requested = reader.u16be()
        return if (channelIdPresent) reader.u16be() else requested
    }

    /** Wrap [payload] as session data from [userId] to [channelId]. */
    fun sendDataRequest(userId: Int, channelId: Int, payload: ByteArray): ByteArray {
        val body = RdpWriter(SEND_DATA_HEADER + 2 + payload.size)
        Per.choice(body, PDU_SEND_DATA_REQUEST shl 2)
        Per.userId(body, userId)
        body.u16be(channelId)
        body.u8(DATA_FLAGS)
        Per.length(body, payload.size)
        body.bytes(payload)
        return packet(body.toByteArray())
    }

    /** Decode a domain PDU that arrived inside an X.224 data TPDU. */
    fun parseDomainPdu(reader: RdpReader): McsDomainPdu {
        val choice = reader.u8()
        return when (choice shr 2) {
            PDU_SEND_DATA_INDICATION, PDU_SEND_DATA_REQUEST -> {
                val initiator = Per.readUserId(reader)
                val channelId = reader.u16be()
                reader.u8() // dataPriority + segmentation
                val length = Per.readLength(reader)
                McsDomainPdu.Data(channelId, initiator, reader.slice(length))
            }

            PDU_DISCONNECT_PROVIDER_ULTIMATUM -> {
                // The three-bit reason straddles the choice byte and the next one.
                val reason = ((choice and 0x03) shl 1) or (reader.u8() shr 7)
                McsDomainPdu.Disconnect(reason)
            }

            else -> McsDomainPdu.Other(choice shr 2)
        }
    }

    private fun expectPdu(choice: Int, expected: Int) {
        val actual = choice shr 2
        if (actual != expected) throw RdpProtocolException("expected MCS PDU $expected, got $actual")
    }

    /** TPKT + X.224 data header around an MCS body. */
    private fun packet(body: ByteArray): ByteArray =
        RdpWriter(body.size + 8).bytes(X224.dataHeader(body.size)).bytes(body).toByteArray()
}
