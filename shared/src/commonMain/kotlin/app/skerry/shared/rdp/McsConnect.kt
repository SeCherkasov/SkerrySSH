package app.skerry.shared.rdp

/**
 * The MCS (T.125) connect exchange that opens an RDP session: Connect-Initial carrying the GCC
 * conference create request, and the Connect-Response carrying the server's answer
 * (MS-RDPBCGR 2.2.1.3/2.2.1.4).
 *
 * The domain parameters are the values a real client sends, verbatim. They are not tuning knobs:
 * servers compare them against their own limits, and the triple (target/minimum/maximum) is what
 * every deployed client has been sending for two decades.
 */
object McsConnect {
    private const val TAG_CONNECT_INITIAL = 101
    private const val TAG_CONNECT_RESPONSE = 102

    private const val RESULT_SUCCESSFUL = 0

    /** Connect-Initial with [gccUserData] (the output of [Gcc.conferenceCreateRequest]) inside. */
    fun connectInitial(gccUserData: ByteArray): ByteArray {
        val body = RdpWriter(gccUserData.size + 128)
        body.bytes(Ber.octetString(byteArrayOf(0x01))) // callingDomainSelector
        body.bytes(Ber.octetString(byteArrayOf(0x01))) // calledDomainSelector
        body.bytes(Ber.boolean(true)) // upwardFlag
        body.bytes(domainParameters(34, 2, 0, 1, 0, 1, 0xFFFF, 2)) // target
        body.bytes(domainParameters(1, 1, 1, 1, 0, 1, 1056, 2)) // minimum
        body.bytes(domainParameters(0xFFFF, 64535, 0xFFFF, 1, 0, 1, 0xFFFF, 2)) // maximum
        body.bytes(Ber.octetString(gccUserData))

        val content = body.toByteArray()
        val writer = RdpWriter(content.size + 8)
        Ber.applicationTag(writer, TAG_CONNECT_INITIAL, content.size)
        writer.bytes(content)
        return writer.toByteArray()
    }

    /**
     * Parse Connect-Response and return what the server said about the session.
     *
     * @throws RdpProtocolException the server refused the connection or the structure is malformed
     */
    fun parseConnectResponse(reader: RdpReader): ServerUserData {
        val length = Ber.readApplicationTag(reader, TAG_CONNECT_RESPONSE)
        val body = reader.slice(minOf(length, reader.remaining))
        val result = Ber.readEnumerated(body)
        if (result != RESULT_SUCCESSFUL) {
            throw RdpProtocolException("MCS connect refused with result $result")
        }
        Ber.readInteger(body) // calledConnectId
        Ber.readSequence(body) // domainParameters, negotiated down by the server and not acted on
        val userData = Ber.readOctetString(body)
        return Gcc.parseServerUserData(Gcc.parseConferenceCreateResponse(RdpReader(userData)))
    }

    private fun domainParameters(
        maxChannelIds: Int,
        maxUserIds: Int,
        maxTokenIds: Int,
        numPriorities: Int,
        minThroughput: Int,
        maxHeight: Int,
        maxMcsPduSize: Int,
        protocolVersion: Int,
    ): ByteArray {
        val content = RdpWriter(32)
        content.bytes(Ber.integer(maxChannelIds))
        content.bytes(Ber.integer(maxUserIds))
        content.bytes(Ber.integer(maxTokenIds))
        content.bytes(Ber.integer(numPriorities))
        content.bytes(Ber.integer(minThroughput))
        content.bytes(Ber.integer(maxHeight))
        content.bytes(Ber.integer(maxMcsPduSize))
        content.bytes(Ber.integer(protocolVersion))
        return Ber.sequence(content.toByteArray())
    }
}
