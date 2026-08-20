package app.skerry.shared.rdp

/**
 * Records what the connector asked of it, so the two questions — may I talk to this, and is this
 * what the host is now known by — can be told apart. [RdpCertificateVerifier] is deliberately not a
 * SAM type: a verifier that answered only the first question would accept every certificate for
 * ever without recording one.
 */
internal class RecordingVerifier(
    private val answer: Boolean = true,
    private val committed: Boolean = true,
    private val onVerify: (RdpCertificateOffer) -> Unit = {},
) : RdpCertificateVerifier {
    val verified = mutableListOf<RdpCertificateOffer>()
    val remembered = mutableListOf<RdpCertificateOffer>()

    override fun verify(offer: RdpCertificateOffer): Boolean {
        verified.add(offer)
        onVerify(offer)
        return answer
    }

    override fun remember(offer: RdpCertificateOffer): Boolean {
        remembered.add(offer)
        return committed
    }
}
