package app.skerry.shared.rdp

/**
 * The server's stream did not parse: a malformed structure, a length that does not fit its container,
 * an unexpected PDU. The whole RDP stack treats the peer as untrusted, so every decoder raises this
 * instead of indexing past a buffer (same rule as `VncProtocolException`).
 */
class RdpProtocolException(message: String) : Exception(message)

/**
 * Credentials were rejected — by the server's own logon check, or by the CredSSP/NTLM exchange that
 * runs before the RDP connection sequence. Distinct from [RdpProtocolException]: this one is the
 * user's to fix (wrong password, wrong domain, locked account).
 */
class RdpAuthException(message: String) : Exception(message)

/**
 * The server handed the connection to another machine (MS-RDPBCGR 2.2.13). Not a failure: it is how
 * a Remote Desktop farm's broker routes a logon, and the transport answers it by dialling
 * [redirection]'s target instead of surfacing anything to the user.
 */
class RdpRedirectException(val redirection: RdpRedirection) :
    Exception("the server redirected the session to ${redirection.targetHost ?: "another host"}")

/**
 * The server refused the security protocols we asked for (RDP_NEG_FAILURE, MS-RDPBCGR 2.2.1.2.2).
 * [reason] is the parsed failure code, or `null` when the server sent a code we don't know — the
 * connection still fails, but the UI can only show the raw number.
 */
class RdpNegotiationException(
    val reason: RdpNegotiationFailure?,
    message: String,
) : Exception(message)

/**
 * Failure codes of RDP_NEG_FAILURE (MS-RDPBCGR 2.2.1.2.2). Kept as an enum rather than raw ints
 * because these are the strings the user sees: "the server requires NLA" is actionable,
 * "negotiation failed (5)" is not.
 */
enum class RdpNegotiationFailure(val code: Int) {
    SSL_REQUIRED_BY_SERVER(1),
    SSL_NOT_ALLOWED_BY_SERVER(2),
    SSL_CERT_NOT_ON_SERVER(3),
    INCONSISTENT_FLAGS(4),
    HYBRID_REQUIRED_BY_SERVER(5),
    SSL_WITH_USER_AUTH_REQUIRED_BY_SERVER(6),
    ;

    companion object {
        fun of(code: Int): RdpNegotiationFailure? = entries.firstOrNull { it.code == code }
    }
}
