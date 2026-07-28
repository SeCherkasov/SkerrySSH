package app.skerry.shared.rdp.nla

import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpReader
import app.skerry.shared.rdp.RdpWriter

/** NTLM negotiate flags (MS-NLMP 2.2.2.5) this client sets or inspects. */
object NtlmFlags {
    const val NEGOTIATE_UNICODE = 0x00000001
    const val REQUEST_TARGET = 0x00000004
    const val NEGOTIATE_SIGN = 0x00000010
    const val NEGOTIATE_SEAL = 0x00000020
    const val NEGOTIATE_NTLM = 0x00000200
    const val NEGOTIATE_ALWAYS_SIGN = 0x00008000
    const val NEGOTIATE_EXTENDED_SESSIONSECURITY = 0x00080000
    const val NEGOTIATE_TARGET_INFO = 0x00800000
    const val NEGOTIATE_VERSION = 0x02000000
    const val NEGOTIATE_128 = 0x20000000
    const val NEGOTIATE_KEY_EXCH = 0x40000000
    const val NEGOTIATE_56 = -0x80000000 // 0x80000000
}

/** AV_PAIR identifiers of the target info (MS-NLMP 2.2.2.1). */
object NtlmAvId {
    const val EOL = 0x0000
    const val NB_COMPUTER_NAME = 0x0001
    const val NB_DOMAIN_NAME = 0x0002
    const val TIMESTAMP = 0x0007
    const val FLAGS = 0x0006
    const val TARGET_NAME = 0x0009
    const val CHANNEL_BINDINGS = 0x000A

    /** MsvAvFlags bit that tells the server this message carries a MIC. */
    const val FLAG_MIC_PRESENT = 0x00000002
}

/**
 * The credentials NTLM authenticates with. [password] is held as a string because that is what the
 * vault hands over and what the NT hash consumes; it never reaches the wire — only HMACs of its
 * MD4 digest do.
 */
data class NtlmCredentials(
    val domain: String,
    val user: String,
    val password: String,
    val workstation: String,
) {
    override fun toString(): String = "NtlmCredentials($domain\\$user, password=redacted)"
}

/**
 * The NTLMv2 computation of MS-NLMP 3.3.2, as pure functions over injected primitives so the spec's
 * published vectors can pin every intermediate.
 */
object NtlmV2 {

    /** NTOWFv2: HMAC_MD5(MD4(UTF16(password)), UTF16(uppercase(user) + domain)). */
    fun responseKeyNt(crypto: NtlmCrypto, user: String, domain: String, password: String): ByteArray {
        val ntHash = crypto.md4(utf16le(password))
        return crypto.hmacMd5(ntHash, utf16le(user.uppercase() + domain))
    }

    /**
     * The `temp` buffer the NTLMv2 response is computed over: version bytes, the server's timestamp,
     * the client challenge and the target info the server sent back verbatim.
     */
    fun temp(timestamp: Long, clientChallenge: ByteArray, targetInfo: ByteArray): ByteArray {
        require(clientChallenge.size == CHALLENGE_SIZE) { "client challenge must be 8 bytes" }
        val writer = RdpWriter(targetInfo.size + 32)
        writer.u8(1) // Responserversion
        writer.u8(1) // HiResponserversion
        writer.zeros(6)
        writer.u32le(timestamp.toInt())
        writer.u32le((timestamp ushr 32).toInt())
        writer.bytes(clientChallenge)
        writer.zeros(4)
        writer.bytes(targetInfo)
        writer.zeros(4)
        return writer.toByteArray()
    }

    /** NTProofStr: HMAC_MD5(ResponseKeyNT, ServerChallenge || temp). */
    fun ntProofString(
        crypto: NtlmCrypto,
        responseKeyNt: ByteArray,
        serverChallenge: ByteArray,
        temp: ByteArray,
    ): ByteArray = crypto.hmacMd5(responseKeyNt, serverChallenge + temp)

    /** SessionBaseKey: HMAC_MD5(ResponseKeyNT, NTProofStr). */
    fun sessionBaseKey(crypto: NtlmCrypto, responseKeyNt: ByteArray, ntProofString: ByteArray): ByteArray =
        crypto.hmacMd5(responseKeyNt, ntProofString)

    /**
     * LMv2 response. It authenticates nothing NTLMv2 does not already cover, but a server that asked
     * for LM refuses a zero-length field, so the value is computed rather than omitted.
     */
    fun lmResponse(
        crypto: NtlmCrypto,
        responseKeyNt: ByteArray,
        serverChallenge: ByteArray,
        clientChallenge: ByteArray,
    ): ByteArray = crypto.hmacMd5(responseKeyNt, serverChallenge + clientChallenge) + clientChallenge

    /**
     * Return [targetInfo] with MsvAvFlags carrying [NtlmAvId.FLAG_MIC_PRESENT], plus [targetName]
     * as MsvAvTargetName when the caller has an SPN.
     *
     * The pairs are rebuilt rather than appended to: the list is EOL-terminated, and a pair written
     * after that terminator is invisible to the server — which then computes a different MIC.
     */
    fun withMicFlag(targetInfo: ByteArray, targetName: String?): ByteArray {
        val pairs = parseAvPairs(targetInfo).toMutableList()
        val existingFlags = pairs.firstOrNull { it.id == NtlmAvId.FLAGS }
        val flags = existingFlags?.let { readU32le(it.value) } ?: 0
        pairs.removeAll { it.id == NtlmAvId.FLAGS || it.id == NtlmAvId.TARGET_NAME }
        pairs.add(NtlmAvPair(NtlmAvId.FLAGS, u32le(flags or NtlmAvId.FLAG_MIC_PRESENT)))
        if (targetName != null) pairs.add(NtlmAvPair(NtlmAvId.TARGET_NAME, utf16le(targetName)))
        val writer = RdpWriter(targetInfo.size + 64)
        for (pair in pairs) {
            writer.u16le(pair.id)
            writer.u16le(pair.value.size)
            writer.bytes(pair.value)
        }
        writer.u16le(NtlmAvId.EOL).u16le(0)
        return writer.toByteArray()
    }

    /** The timestamp the server published in its target info, or null when it sent none. */
    fun timestampOf(targetInfo: ByteArray): Long? =
        parseAvPairs(targetInfo).firstOrNull { it.id == NtlmAvId.TIMESTAMP && it.value.size == 8 }
            ?.let { pair ->
                var value = 0L
                for (i in 7 downTo 0) value = (value shl 8) or (pair.value[i].toLong() and 0xFF)
                value
            }

    /** Parse an AV_PAIR list, stopping at the EOL pair. */
    fun parseAvPairs(targetInfo: ByteArray): List<NtlmAvPair> {
        val reader = RdpReader(targetInfo)
        val pairs = mutableListOf<NtlmAvPair>()
        while (reader.remaining >= 4) {
            val id = reader.u16le()
            val length = reader.u16le()
            if (id == NtlmAvId.EOL) break
            if (length > reader.remaining) throw RdpProtocolException("AV pair $id runs past the target info")
            pairs.add(NtlmAvPair(id, reader.bytes(length)))
        }
        return pairs
    }

    internal const val CHALLENGE_SIZE = 8

    internal fun utf16le(text: String): ByteArray {
        val out = ByteArray(text.length * 2)
        for (i in text.indices) {
            out[i * 2] = text[i].code.toByte()
            out[i * 2 + 1] = (text[i].code ushr 8).toByte()
        }
        return out
    }

    private fun u32le(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    private fun readU32le(data: ByteArray): Int {
        if (data.size < 4) return 0
        return (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) or
            ((data[2].toInt() and 0xFF) shl 16) or ((data[3].toInt() and 0xFF) shl 24)
    }
}

/** One AV_PAIR of the NTLM target info. */
data class NtlmAvPair(val id: Int, val value: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is NtlmAvPair && id == other.id && value.contentEquals(other.value)

    override fun hashCode(): Int = id * 31 + value.contentHashCode()
}
