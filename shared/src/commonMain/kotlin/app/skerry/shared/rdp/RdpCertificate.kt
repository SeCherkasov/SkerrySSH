package app.skerry.shared.rdp

/**
 * The TLS certificate an RDP server presented, in the terms a trust decision needs. Windows
 * generates its own self-signed certificate for Remote Desktop unless an enterprise CA issued one,
 * so "not trusted by the platform" is the normal case here rather than a red flag — which is exactly
 * why the decision is delegated to a verifier (TOFU over a remembered fingerprint) instead of being
 * left to the default TLS trust store.
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
fun interface RdpCertificateVerifier {
    fun verify(offer: RdpCertificateOffer): Boolean
}

/** The verifier refused the server's certificate; the socket is closed before any data is sent. */
class RdpCertificateRejectedException(val offer: RdpCertificateOffer) :
    Exception("server certificate rejected (${offer.fingerprintSha256})")
