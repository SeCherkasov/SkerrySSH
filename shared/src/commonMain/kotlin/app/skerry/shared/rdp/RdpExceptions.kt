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
class RdpAuthException(
    val reason: RdpAuthFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Why the logon was refused, as a contract the UI translates. The message keeps the protocol's own
 * wording for diagnostics; this is what decides which sentence the user reads.
 */
enum class RdpAuthFailure {
    /** Wrong user name or password (STATUS_LOGON_FAILURE). */
    Credentials,

    /** The account exists but is disabled, locked out or expired. */
    AccountRestricted,

    /** The password has expired or must be changed before the next logon. */
    PasswordExpired,

    /** The account may not log on through Remote Desktop. */
    LogonNotAllowed,

    /** The server could not license the session. */
    License,

    /** The server ended the logon for a reason of its own (Set Error Info, an unmapped NTSTATUS). */
    ServerRefused,

    /**
     * CredSSP could not tie the logon to the TLS channel. The password itself is never sent, but the
     * NTLM response may already have been — an interception is not ruled out.
     */
    SecurityCheck,
}

/**
 * The TLS handshake that every accepted protocol starts with did not complete: no shared version
 * or cipher suite, or the server aborted the handshake. Kept apart from a refused certificate
 * ([RdpCertificateRejectedException]), which is the user's own decision, and from a timeout or a
 * reset, which stay plain I/O failures.
 */
class RdpTlsException(message: String, cause: Throwable) : Exception(message, cause)

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
