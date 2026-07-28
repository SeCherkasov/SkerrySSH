package app.skerry.shared.rdp.nla

import app.skerry.shared.rdp.Ber
import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpReader
import app.skerry.shared.rdp.RdpSource
import app.skerry.shared.rdp.RdpWriter

/**
 * The CredSSP TSRequest (MS-CSSP 2.2.1). Every field but the version is optional, and which ones are
 * present is what distinguishes the phases of the exchange.
 */
data class TsRequest(
    val version: Int,
    val negoToken: ByteArray? = null,
    val authInfo: ByteArray? = null,
    val pubKeyAuth: ByteArray? = null,
    val errorCode: Int? = null,
    val clientNonce: ByteArray? = null,
) {
    fun encode(): ByteArray {
        val body = RdpWriter(128)
        body.bytes(Der.tagged(Der.contextTag(0), Der.integer(version)))
        if (negoToken != null) {
            // NegoData ::= SEQUENCE OF SEQUENCE { negoToken [0] OCTET STRING }
            val token = Der.sequence(Der.tagged(Der.contextTag(0), Der.octetString(negoToken)))
            body.bytes(Der.tagged(Der.contextTag(1), Der.sequence(token)))
        }
        if (authInfo != null) body.bytes(Der.tagged(Der.contextTag(2), Der.octetString(authInfo)))
        if (pubKeyAuth != null) body.bytes(Der.tagged(Der.contextTag(3), Der.octetString(pubKeyAuth)))
        if (errorCode != null) body.bytes(Der.tagged(Der.contextTag(4), Der.integer(errorCode)))
        if (clientNonce != null) body.bytes(Der.tagged(Der.contextTag(5), Der.octetString(clientNonce)))
        return Der.sequence(body.toByteArray())
    }

    override fun equals(other: Any?): Boolean =
        other is TsRequest && version == other.version &&
            negoToken.contentEquals(other.negoToken) && authInfo.contentEquals(other.authInfo) &&
            pubKeyAuth.contentEquals(other.pubKeyAuth) && errorCode == other.errorCode &&
            clientNonce.contentEquals(other.clientNonce)

    override fun hashCode(): Int = version * 31 + (negoToken?.contentHashCode() ?: 0)

    companion object {
        /** Largest TSRequest we will read; the length prefix is the peer's claim, not a fact. */
        const val MAX_SIZE = 64 * 1024

        fun decode(bytes: ByteArray): TsRequest {
            val body = Der.readTagged(RdpReader(bytes), Der.TAG_SEQUENCE)
            var version = 0
            var negoToken: ByteArray? = null
            var authInfo: ByteArray? = null
            var pubKeyAuth: ByteArray? = null
            var errorCode: Int? = null
            var clientNonce: ByteArray? = null
            while (body.remaining > 0) {
                when (val tag = Der.peekTag(body)) {
                    Der.contextTag(0) -> version = Der.readInteger(Der.readTagged(body, tag))
                    Der.contextTag(1) -> {
                        val negoData = Der.readTagged(Der.readTagged(body, tag), Der.TAG_SEQUENCE)
                        val item = Der.readTagged(negoData, Der.TAG_SEQUENCE)
                        negoToken = Der.readOctetString(Der.readTagged(item, Der.contextTag(0)))
                    }

                    Der.contextTag(2) -> authInfo = Der.readOctetString(Der.readTagged(body, tag))
                    Der.contextTag(3) -> pubKeyAuth = Der.readOctetString(Der.readTagged(body, tag))
                    Der.contextTag(4) -> errorCode = Der.readInteger(Der.readTagged(body, tag))
                    Der.contextTag(5) -> clientNonce = Der.readOctetString(Der.readTagged(body, tag))
                    // An unknown field is skipped by its own length: the structure has grown before
                    // (clientNonce arrived in version 5) and will again.
                    else -> Der.readTagged(body, tag)
                }
            }
            return TsRequest(version, negoToken, authInfo, pubKeyAuth, errorCode, clientNonce)
        }

        /**
         * Read exactly one TSRequest from [source]. The outer SEQUENCE's length says how much to
         * read, so the message is framed by its own DER header rather than by the socket.
         */
        suspend fun read(source: RdpSource): TsRequest {
            val header = ByteArray(2)
            source.readFully(header, 0, 2)
            if (header[0].toInt() and 0xFF != Der.TAG_SEQUENCE) {
                throw RdpProtocolException("expected a TSRequest SEQUENCE")
            }
            val first = header[1].toInt() and 0xFF
            val lengthBytes = if (first and 0x80 == 0) ByteArray(0) else ByteArray(first and 0x7F)
            if (lengthBytes.size > 2) throw RdpProtocolException("TSRequest length field too wide")
            source.readFully(lengthBytes, 0, lengthBytes.size)
            val contentLength = if (lengthBytes.isEmpty()) {
                first
            } else {
                lengthBytes.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
            }
            if (contentLength !in 0..MAX_SIZE) throw RdpProtocolException("TSRequest of $contentLength bytes")
            val message = RdpWriter(contentLength + 4)
            message.bytes(header).bytes(lengthBytes)
            val content = ByteArray(contentLength)
            source.readFully(content, 0, contentLength)
            message.bytes(content)
            return decode(message.toByteArray())
        }

        private fun ByteArray?.contentEquals(other: ByteArray?): Boolean = when {
            this == null -> other == null
            other == null -> false
            else -> this.contentEquals(other)
        }
    }
}

/**
 * TSCredentials carrying a password (MS-CSSP 2.2.1.2 / 2.2.1.2.1) — what the server logs the user in
 * with once the channel is bound. Strings are UTF-16LE, as everywhere in this protocol family.
 */
object TsCredentials {
    private const val CRED_TYPE_PASSWORD = 1

    fun password(domain: String, user: String, password: String): ByteArray {
        val creds = RdpWriter(128)
        creds.bytes(Der.tagged(Der.contextTag(0), Der.octetString(NtlmV2.utf16le(domain))))
        creds.bytes(Der.tagged(Der.contextTag(1), Der.octetString(NtlmV2.utf16le(user))))
        creds.bytes(Der.tagged(Der.contextTag(2), Der.octetString(NtlmV2.utf16le(password))))
        val passwordCreds = Der.sequence(creds.toByteArray())

        val body = RdpWriter(passwordCreds.size + 16)
        body.bytes(Der.tagged(Der.contextTag(0), Der.integer(CRED_TYPE_PASSWORD)))
        body.bytes(Der.tagged(Der.contextTag(1), Der.octetString(passwordCreds)))
        return Der.sequence(body.toByteArray())
    }
}
