package app.skerry.shared.rdp

/**
 * The TLS certificate an RDP server presented, in the terms a trust decision needs. Windows
 * generates its own self-signed certificate for Remote Desktop unless an enterprise CA issued one,
 * so "not trusted by the platform" is the normal case here rather than a red flag — which is exactly
 * why the decision is delegated to a verifier (TOFU over a remembered fingerprint) instead of being
 * left to the default TLS trust store.
 *
 * [subject], [issuer] and [host] are text the server authored: run them through
 * `sanitizeServerText` before they reach a screen.
 *
 * [trustedByPlatform] and [hostnameMatches] describe the certificate; they do not decide anything.
 * The only verifier wired in production is `FileRdpCertificateStore`, which judges by fingerprint
 * alone — refusing on either flag would refuse nearly every Windows host, which names itself after
 * the machine rather than the address dialled. They are here for the certificate dialog to show.
 *
 * [publicKey] is the DER SubjectPublicKeyInfo of the leaf certificate: CredSSP binds the
 * authentication exchange to it, so the value the verifier saw is the value the NLA layer signs.
 * [derChain] is the chain as the server sent it, leaf first.
 */
data class RdpCertificateOffer(
    val host: String,
    val port: Int,
    val fingerprintSha256: String,
    val subject: String,
    val issuer: String,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
    val trustedByPlatform: Boolean,
    val hostnameMatches: Boolean,
    val publicKey: ByteArray,
    val derChain: List<ByteArray>,
) {
    // Hand-written: ByteArray uses identity equality, and this type is compared in tests and caches.
    override fun equals(other: Any?): Boolean =
        other is RdpCertificateOffer && host == other.host && port == other.port &&
            fingerprintSha256 == other.fingerprintSha256 && subject == other.subject &&
            issuer == other.issuer && notBeforeMillis == other.notBeforeMillis &&
            notAfterMillis == other.notAfterMillis && trustedByPlatform == other.trustedByPlatform &&
            hostnameMatches == other.hostnameMatches && publicKey.contentEquals(other.publicKey) &&
            derChain.size == other.derChain.size &&
            derChain.indices.all { derChain[it].contentEquals(other.derChain[it]) }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + fingerprintSha256.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

/**
 * Decides whether to talk to a server presenting [RdpCertificateOffer] — the framebuffer sibling of
 * `HostKeyVerifier`, and synchronous for the same reason: it is called from the connect path, and
 * the store it consults is a local file.
 */
interface RdpCertificateVerifier {
    /**
     * Whether this certificate may be talked to. Asked from inside the TLS handshake, which is
     * before the server has proven it holds the matching private key — so this answers, and
     * records nothing. Anyone able to answer the connection can reach it with a certificate
     * copied from elsewhere.
     */
    fun verify(offer: RdpCertificateOffer): Boolean

    /**
     * The handshake [offer] came from completed, so the server does hold the key. Trust on first
     * use is committed here and nowhere else; called once per connection, and only after [verify]
     * said yes.
     *
     * False means this host is now remembered by a different certificate — a second first-time
     * connection settled between the two calls — and the connection is dropped. Abstract on
     * purpose: an implementation that forwarded only [verify] would accept every certificate for
     * ever without recording one, and nothing would say so.
     */
    fun remember(offer: RdpCertificateOffer): Boolean
}

/** The verifier refused the server's certificate; the socket is closed before any data is sent. */
class RdpCertificateRejectedException(val offer: RdpCertificateOffer, cause: Throwable? = null) :
    Exception("server certificate rejected (${offer.fingerprintSha256})", cause)
