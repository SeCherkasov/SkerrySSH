package app.skerry.shared.rdp

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Puts the server's chain in front of [RdpCertificateVerifier] while the handshake is still in
 * flight, and fails it when the answer is no. The platform's own verdict is recorded rather than
 * enforced — Windows signs its Remote Desktop certificate itself unless an enterprise CA issued
 * one, so "the platform does not trust this" is the normal case, and "my enterprise CA issued
 * it" and "the machine signed it itself" are answers the verifier gets to weigh.
 */
internal class RdpVerifyingTrustManager(
    private val verifier: RdpCertificateVerifier,
    private val platform: X509TrustManager?,
    private val host: String,
    private val port: Int,
) : X509TrustManager {
    /** The certificate the verifier accepted; its public key is what CredSSP binds to. */
    @Volatile
    var accepted: RdpCertificateOffer? = null
        private set

    /** The certificate the verifier turned down, so the failure can name it. */
    @Volatile
    var rejected: RdpCertificateOffer? = null
        private set

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull()
            ?: throw CertificateException("server sent an empty certificate chain")
        val settled = accepted
        if (settled != null) {
            // Renegotiation. The session may not change the certificate under us: CredSSP is
            // bound to the key captured on the first one, and asking the verifier again would
            // run it from the read thread, long after the connection was reported as up.
            if (settled.fingerprintSha256 != fingerprintOf(leaf)) {
                throw CertificateException("server changed its certificate mid-session")
            }
            return
        }
        val offer = RdpCertificateOffer(
            host = host,
            port = port,
            fingerprintSha256 = fingerprintOf(leaf),
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notBeforeMillis = leaf.notBefore.time,
            notAfterMillis = leaf.notAfter.time,
            trustedByPlatform = platformTrusts(chain, authType),
            hostnameMatches = certificateMatchesHost(host, leaf),
            publicKey = leaf.publicKey.encoded,
            derChain = chain.map { it.encoded },
        )
        if (!verifier.verify(offer)) {
            rejected = offer
            throw CertificateException("server certificate rejected (${offer.fingerprintSha256})")
        }
        accepted = offer
    }

    /**
     * What the platform's own trust store makes of [chain] — recorded for the verifier, never
     * enforced here, so its refusal is an answer rather than a failure.
     */
    private fun platformTrusts(chain: Array<X509Certificate>, authType: String): Boolean {
        val store = platform ?: return false
        return runCatching { store.checkServerTrusted(chain, authType) }.isSuccess
    }

    /** Client-side only: nothing here ever authenticates a peer that dialled us. */
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String): Unit =
        throw CertificateException("RDP client trust manager cannot vouch for a client certificate")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun fingerprintOf(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString(":") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
