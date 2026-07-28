package app.skerry.shared.rdp.nla

import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpReader
import app.skerry.shared.rdp.RdpWriter

/** What the server said in its CHALLENGE_MESSAGE (MS-NLMP 2.2.1.2). */
data class NtlmChallengeMessage(
    val serverChallenge: ByteArray,
    val flags: Int,
    val targetInfo: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is NtlmChallengeMessage && serverChallenge.contentEquals(other.serverChallenge) &&
            flags == other.flags && targetInfo.contentEquals(other.targetInfo)

    override fun hashCode(): Int =
        (serverChallenge.contentHashCode() * 31 + flags) * 31 + targetInfo.contentHashCode()
}

/** Parsing of the server's CHALLENGE_MESSAGE. */
object NtlmChallenge {
    private val SIGNATURE = byteArrayOf(0x4E, 0x54, 0x4C, 0x4D, 0x53, 0x53, 0x50, 0x00) // "NTLMSSP\0"
    private const val MESSAGE_TYPE_CHALLENGE = 2

    fun parse(message: ByteArray): NtlmChallengeMessage {
        val reader = RdpReader(message)
        val signature = reader.bytes(8)
        if (!signature.contentEquals(SIGNATURE)) throw RdpProtocolException("not an NTLM message")
        val type = reader.u32le()
        if (type != MESSAGE_TYPE_CHALLENGE) throw RdpProtocolException("expected an NTLM challenge, got type $type")
        reader.skip(4) // TargetNameLen + MaxLen
        reader.skip(4) // TargetNameBufferOffset
        val flags = reader.u32le()
        val serverChallenge = reader.bytes(NtlmV2.CHALLENGE_SIZE)
        reader.skip(8) // Reserved
        val targetInfoLength = reader.u16le()
        reader.skip(2) // TargetInfoMaxLen
        val targetInfoOffset = reader.u32le()
        // Offsets and lengths are the server's claim; the message we actually hold is the fact.
        if (targetInfoOffset < 0 || targetInfoLength < 0 ||
            targetInfoOffset.toLong() + targetInfoLength > message.size
        ) {
            throw RdpProtocolException("NTLM challenge target info lies outside the message")
        }
        val targetInfo = message.copyOfRange(targetInfoOffset, targetInfoOffset + targetInfoLength)
        return NtlmChallengeMessage(serverChallenge, flags, targetInfo)
    }
}

/** The AUTHENTICATE_MESSAGE and the session keys derived alongside it. */
class NtlmAuthenticateResult(val message: ByteArray, val session: NtlmSession)

/**
 * Client half of an NTLMv2 exchange (MS-NLMP 3.1.5.1): [negotiate], then [authenticate] with the
 * server's challenge. The instance keeps the negotiate message because the MIC is computed over all
 * three messages in order.
 *
 * [spn] is the service principal name of the target ("TERMSRV/host"), carried as MsvAvTargetName so
 * a server that enforces target binding accepts the ticket; [fixedClientChallenge] and
 * [fixedTimestamp] exist for the spec's test vectors and are never set in production.
 */
class NtlmClient(
    private val credentials: NtlmCredentials,
    private val crypto: NtlmCrypto,
    private val spn: String? = null,
    private val fixedClientChallenge: ByteArray? = null,
    private val fixedTimestamp: Long? = null,
    private val nowFileTime: () -> Long = { 0L },
) {
    private var negotiateMessage: ByteArray? = null

    /** NEGOTIATE_MESSAGE (MS-NLMP 2.2.1.1). */
    fun negotiate(): ByteArray {
        val writer = RdpWriter(40)
        writer.bytes(SIGNATURE)
        writer.u32le(MESSAGE_TYPE_NEGOTIATE)
        writer.u32le(NEGOTIATE_FLAGS)
        // DomainName and Workstation are left empty: they are unauthenticated hints, they leak the
        // local machine name to anything listening before the channel is bound, and the domain that
        // decides the logon travels in the authenticate message.
        writer.u16le(0).u16le(0).u32le(0) // DomainNameFields
        writer.u16le(0).u16le(0).u32le(0) // WorkstationFields
        writer.bytes(VERSION)
        return writer.toByteArray().also { negotiateMessage = it }
    }

    /**
     * Build the AUTHENTICATE_MESSAGE for [challengeMessage] and derive the session keys.
     *
     * @throws RdpProtocolException the challenge is malformed
     * @throws IllegalStateException [negotiate] has not been called
     */
    fun authenticate(challengeMessage: ByteArray): NtlmAuthenticateResult {
        val negotiate = checkNotNull(negotiateMessage) { "negotiate() must run before authenticate()" }
        val challenge = NtlmChallenge.parse(challengeMessage)

        val clientChallenge = fixedClientChallenge ?: crypto.randomBytes(NtlmV2.CHALLENGE_SIZE)
        // The server's own timestamp is used when it published one: a server that enforces
        // MsvAvTimestamp compares it, and a clock skew on this machine would fail every logon.
        val timestamp = fixedTimestamp ?: NtlmV2.timestampOf(challenge.targetInfo) ?: nowFileTime()
        val targetInfo = NtlmV2.withMicFlag(challenge.targetInfo, spn)

        val responseKey = NtlmV2.responseKeyNt(crypto, credentials.user, credentials.domain, credentials.password)
        val temp = NtlmV2.temp(timestamp, clientChallenge, targetInfo)
        val proof = NtlmV2.ntProofString(crypto, responseKey, challenge.serverChallenge, temp)
        val ntResponse = proof + temp
        val lmResponse = NtlmV2.lmResponse(crypto, responseKey, challenge.serverChallenge, clientChallenge)
        val sessionBaseKey = NtlmV2.sessionBaseKey(crypto, responseKey, proof)

        // With key exchange negotiated the session key is ours to pick, wrapped under the base key;
        // it is what everything after this point is encrypted with.
        val exportedSessionKey = crypto.randomBytes(SESSION_KEY_SIZE)
        val encryptedSessionKey = Rc4(sessionBaseKey).process(exportedSessionKey)

        val message = buildAuthenticate(ntResponse, lmResponse, encryptedSessionKey)
        val mic = crypto.hmacMd5(exportedSessionKey, negotiate + challengeMessage + message)
        mic.copyInto(message, MIC_OFFSET)

        return NtlmAuthenticateResult(message, NtlmSession(crypto, exportedSessionKey, NtlmRole.Client))
    }

    private fun buildAuthenticate(
        ntResponse: ByteArray,
        lmResponse: ByteArray,
        encryptedSessionKey: ByteArray,
    ): ByteArray {
        val domain = NtlmV2.utf16le(credentials.domain)
        val user = NtlmV2.utf16le(credentials.user)
        val workstation = NtlmV2.utf16le(credentials.workstation)

        var offset = PAYLOAD_OFFSET
        val writer = RdpWriter(PAYLOAD_OFFSET + lmResponse.size + ntResponse.size + domain.size + user.size + 64)
        writer.bytes(SIGNATURE)
        writer.u32le(MESSAGE_TYPE_AUTHENTICATE)
        offset = writeField(writer, lmResponse.size, offset)
        offset = writeField(writer, ntResponse.size, offset)
        offset = writeField(writer, domain.size, offset)
        offset = writeField(writer, user.size, offset)
        offset = writeField(writer, workstation.size, offset)
        writeField(writer, encryptedSessionKey.size, offset)
        writer.u32le(NEGOTIATE_FLAGS)
        writer.bytes(VERSION)
        writer.zeros(MIC_SIZE) // MIC, filled in once the whole message exists
        writer.bytes(lmResponse)
        writer.bytes(ntResponse)
        writer.bytes(domain)
        writer.bytes(user)
        writer.bytes(workstation)
        writer.bytes(encryptedSessionKey)
        return writer.toByteArray()
    }

    /** Write a Len/MaxLen/BufferOffset triple and return the offset the next payload starts at. */
    private fun writeField(writer: RdpWriter, length: Int, offset: Int): Int {
        writer.u16le(length).u16le(length).u32le(offset)
        return offset + length
    }

    companion object {
        private val SIGNATURE = byteArrayOf(0x4E, 0x54, 0x4C, 0x4D, 0x53, 0x53, 0x50, 0x00)
        private const val MESSAGE_TYPE_NEGOTIATE = 1
        private const val MESSAGE_TYPE_AUTHENTICATE = 3
        private const val SESSION_KEY_SIZE = 16
        private const val MIC_SIZE = 16

        /** Offset of the MIC inside AUTHENTICATE_MESSAGE: after the fields, flags and version. */
        const val MIC_OFFSET = 72

        /** Payload starts after the MIC. */
        private const val PAYLOAD_OFFSET = MIC_OFFSET + MIC_SIZE

        /**
         * Windows 7 / build 7601 in the OS version field. The value is not a capability claim — it
         * is a struct servers parse — and reporting a plausible one avoids the "unknown client"
         * paths some servers take.
         */
        private val VERSION = byteArrayOf(0x06, 0x01, 0xB1.toByte(), 0x1D, 0x00, 0x00, 0x00, 0x0F)

        /**
         * What this client negotiates: Unicode, NTLM with extended session security, sealing with a
         * 128-bit key, and key exchange. No LM_KEY, no datagram mode, no NTLMv1 fallback — every one
         * of those weakens the session key CredSSP then binds the TLS channel with.
         */
        private const val NEGOTIATE_FLAGS =
            NtlmFlags.NEGOTIATE_UNICODE or
                NtlmFlags.REQUEST_TARGET or
                NtlmFlags.NEGOTIATE_SIGN or
                NtlmFlags.NEGOTIATE_SEAL or
                NtlmFlags.NEGOTIATE_NTLM or
                NtlmFlags.NEGOTIATE_ALWAYS_SIGN or
                NtlmFlags.NEGOTIATE_EXTENDED_SESSIONSECURITY or
                NtlmFlags.NEGOTIATE_TARGET_INFO or
                NtlmFlags.NEGOTIATE_VERSION or
                NtlmFlags.NEGOTIATE_128 or
                NtlmFlags.NEGOTIATE_KEY_EXCH or
                NtlmFlags.NEGOTIATE_56
    }
}
