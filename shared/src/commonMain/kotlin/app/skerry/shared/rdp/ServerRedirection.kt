package app.skerry.shared.rdp

/**
 * What a Server Redirection Packet (MS-RDPBCGR 2.2.13.1) told the client: reconnect to the machine
 * the connection broker picked, carrying [sessionId] and the routing token it chose.
 *
 * A Remote Desktop farm answers the first connection with this. Every field is optional and gated by
 * a flag, so anything the broker did not name stays `null` and the connection keeps what it had.
 */
data class RdpRedirection(
    val sessionId: Int,
    val flags: Int,
    val targetNetAddress: String? = null,
    val loadBalanceInfo: String? = null,
    val username: String? = null,
    val domain: String? = null,
    /** Cleartext password for the target, or `null` — including when it is an RDSTLS blob we can't use. */
    val password: String? = null,
    val targetFqdn: String? = null,
    val targetNetBiosName: String? = null,
) {
    /** The packet only updates the client's bookkeeping (LB_NOREDIRECT); the session continues. */
    val informationalOnly: Boolean get() = flags and ServerRedirection.LB_NOREDIRECT != 0

    /** The password is an RDSTLS blob, not text: this client reconnects with the user's own instead. */
    val passwordIsEncrypted: Boolean get() = flags and ServerRedirection.LB_PASSWORD_IS_PK_ENCRYPTED != 0

    /**
     * Where to reconnect. The FQDN comes first: the redirected connection runs its own TLS handshake,
     * and only the name matches the certificate the target presents — dialling the IP would turn
     * every hop through a farm into a certificate prompt. `null` means the broker named no target,
     * which happens when the redirection only hands out a routing token.
     */
    val targetHost: String?
        get() = targetFqdn?.takeIf { it.isNotBlank() }
            ?: targetNetAddress?.takeIf { it.isNotBlank() }
            ?: targetNetBiosName?.takeIf { it.isNotBlank() }

    /** The same connection, pointed at the redirection's target and carrying its routing token. */
    fun applyTo(target: RdpTarget): RdpTarget = target.copy(
        host = targetHost ?: target.host,
        loadBalanceInfo = loadBalanceInfo ?: target.loadBalanceInfo,
        redirectedSessionId = sessionId,
    )

    /** The same logon, with whatever identity the broker named instead. */
    fun applyTo(credentials: RdpCredentials): RdpCredentials = credentials.copy(
        username = username?.takeIf { it.isNotBlank() } ?: credentials.username,
        domain = domain ?: credentials.domain,
        password = password?.takeIf { it.isNotEmpty() } ?: credentials.password,
    )
}

/**
 * Decoder for the Server Redirection Packet (MS-RDPBCGR 2.2.13.1).
 *
 * The packet is a flag-gated sequence: each `LB_*` bit in `RedirFlags` says that a length and a
 * value for that field follow, in the fixed order below. Reading it means walking the flags in that
 * exact order — a field read out of turn consumes the next one's bytes — and the packet comes from
 * the network, so every declared length is bounded before it is used.
 */
object ServerRedirection {

    const val LB_TARGET_NET_ADDRESS = 0x00000001
    const val LB_LOAD_BALANCE_INFO = 0x00000002
    const val LB_USERNAME = 0x00000004
    const val LB_DOMAIN = 0x00000008
    const val LB_PASSWORD = 0x00000010
    const val LB_DONTSTOREUSERNAME = 0x00000020
    const val LB_SMARTCARD_LOGON = 0x00000040
    const val LB_NOREDIRECT = 0x00000080
    const val LB_TARGET_FQDN = 0x00000100
    const val LB_TARGET_NETBIOS_NAME = 0x00000200
    const val LB_TARGET_NET_ADDRESSES = 0x00000800
    const val LB_CLIENT_TSV_URL = 0x00001000
    const val LB_SERVER_TSV_CAPABLE = 0x00002000
    const val LB_PASSWORD_IS_PK_ENCRYPTED = 0x00004000
    const val LB_REDIRECTION_GUID = 0x00008000
    const val LB_TARGET_CERTIFICATE = 0x00010000

    /** Identifies the packet in both its standard-security and enhanced-security wrappers. */
    const val SEC_REDIRECTION_PKT = 0x0400

    /**
     * Parse a whole packet, starting at its `Flags` field.
     *
     * @throws RdpProtocolException the packet is not a redirection, or a field ran past its end
     */
    fun parse(reader: RdpReader): RdpRedirection {
        val flags = reader.u16le()
        if (flags != SEC_REDIRECTION_PKT) {
            throw RdpProtocolException("expected a server redirection packet, got flags 0x${flags.toString(16)}")
        }
        reader.u16le() // overall length; the reader is already bounded by the PDU
        return parseBody(reader)
    }

    /**
     * Parse the packet from its `SessionID` field — the entry point for the licensing-phase form,
     * where the `Flags`/`Length` pair has already been read as the PDU's security header.
     */
    fun parseBody(reader: RdpReader): RdpRedirection {
        val sessionId = reader.u32le()
        val flags = reader.u32le()

        // Field order is fixed by the spec; the flags say which of them are present.
        val targetNetAddress = if (flags and LB_TARGET_NET_ADDRESS != 0) text(reader) else null
        val loadBalanceInfo = if (flags and LB_LOAD_BALANCE_INFO != 0) opaque(reader) else null
        val username = if (flags and LB_USERNAME != 0) text(reader) else null
        val domain = if (flags and LB_DOMAIN != 0) text(reader) else null
        val password = if (flags and LB_PASSWORD != 0) {
            // An encrypted password is an RDSTLS blob bound to the target's key: it is read to keep
            // the field sequence aligned, then dropped rather than typed at a logon prompt.
            val raw = blob(reader)
            if (flags and LB_PASSWORD_IS_PK_ENCRYPTED != 0) null else decodeUtf16(raw)
        } else {
            null
        }
        val targetFqdn = if (flags and LB_TARGET_FQDN != 0) text(reader) else null
        val targetNetBiosName = if (flags and LB_TARGET_NETBIOS_NAME != 0) text(reader) else null
        // Everything past this point (TsvUrl, redirection GUID, target certificate, the address
        // list, padding) is either a value we echo nowhere or an alternative to fields already read,
        // so the rest of the packet is left unparsed.

        return RdpRedirection(
            sessionId = sessionId,
            flags = flags,
            targetNetAddress = targetNetAddress,
            loadBalanceInfo = loadBalanceInfo,
            username = username,
            domain = domain,
            password = password,
            targetFqdn = targetFqdn,
            targetNetBiosName = targetNetBiosName,
        )
    }

    /** A length-prefixed field, bounded before it is read. */
    private fun blob(reader: RdpReader): ByteArray {
        val length = reader.u32le()
        if (length < 0 || length > MAX_FIELD_SIZE) {
            throw RdpProtocolException("redirection field of $length bytes is out of range")
        }
        return reader.bytes(length)
    }

    /** A UTF-16LE field, without its terminator. */
    private fun text(reader: RdpReader): String = decodeUtf16(blob(reader))

    /**
     * A field the client is meant to pass back verbatim. The routing token is opaque bytes, not
     * text, but the only thing done with it is putting it back on the wire in the next connection
     * request, and that request is ASCII-framed — so it is kept as Latin-1, byte for byte.
     */
    private fun opaque(reader: RdpReader): String =
        blob(reader).map { (it.toInt() and 0xFF).toChar() }
            .joinToString("")
            .trimEnd(Char(0), '\r', '\n')

    private fun decodeUtf16(bytes: ByteArray): String {
        val text = StringBuilder(bytes.size / 2)
        var index = 0
        while (index + 1 < bytes.size) {
            val code = (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)
            if (code == 0) break
            text.append(code.toChar())
            index += 2
        }
        return text.toString()
    }

    /** Generous next to any real field (a certificate is the largest), tight enough to bound a lie. */
    private const val MAX_FIELD_SIZE = 8192
}
