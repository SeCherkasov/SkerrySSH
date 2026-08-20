package app.skerry.shared.rdp

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The trust manager the RDP connector installs. Driven directly rather than through a socket: a
 * real TLS renegotiation is a thread race, and the branch it exercises is one comparison.
 */
class RdpVerifyingTrustManagerTest {

    private class Answers(private val answer: Boolean) : RdpCertificateVerifier {
        var asked = 0
            private set

        override fun verify(offer: RdpCertificateOffer): Boolean {
            asked++
            return answer
        }

        override fun remember(offer: RdpCertificateOffer) = true
    }

    private fun manager(verifier: RdpCertificateVerifier) =
        RdpVerifyingTrustManager(verifier, platform = null, host = "10.0.0.5", port = 3389)

    private fun chainOf(commonName: String): Array<X509Certificate> =
        arrayOf(RdpTestCertificates.certificate(commonName = commonName))

    @Test
    fun `an accepted certificate is offered to the verifier once, whatever the handshake does after`() {
        val verifier = Answers(answer = true)
        val trust = manager(verifier)
        val chain = chainOf("win-host")

        trust.checkServerTrusted(chain, "RSA")
        // Renegotiation on the same certificate: asking again would run the verifier from the read
        // thread, long after the connection was reported as up.
        trust.checkServerTrusted(chain, "RSA")

        assertEquals(1, verifier.asked)
        assertEquals(fingerprintOf(chain.first()), trust.accepted?.fingerprintSha256)
    }

    @Test
    fun `a certificate swapped mid-session is refused`() {
        val trust = manager(Answers(answer = true))
        trust.checkServerTrusted(chainOf("win-host"), "RSA")

        assertFailsWith<CertificateException> { trust.checkServerTrusted(chainOf("other-host"), "RSA") }
    }

    @Test
    fun `a refused certificate is recorded so the failure can name it`() {
        val trust = manager(Answers(answer = false))
        val chain = chainOf("win-host")

        assertFailsWith<CertificateException> { trust.checkServerTrusted(chain, "RSA") }

        assertNull(trust.accepted)
        assertEquals(fingerprintOf(chain.first()), trust.rejected?.fingerprintSha256)
    }

    @Test
    fun `an empty chain is refused without asking the verifier`() {
        val verifier = Answers(answer = true)
        val trust = manager(verifier)

        assertFailsWith<CertificateException> { trust.checkServerTrusted(emptyArray(), "RSA") }

        assertEquals(0, verifier.asked)
    }

    @Test
    fun `it will not vouch for a client certificate`() {
        val trust = manager(Answers(answer = true))

        assertFailsWith<CertificateException> { trust.checkClientTrusted(chainOf("someone"), "RSA") }
        assertTrue(trust.acceptedIssuers.isEmpty())
    }

    @Test
    fun `the offer carries what the verifier needs to judge`() {
        val chain = chainOf("win-host")
        var seen: RdpCertificateOffer? = null
        val trust = manager(object : RdpCertificateVerifier {
            override fun verify(offer: RdpCertificateOffer): Boolean {
                seen = offer
                return true
            }

            override fun remember(offer: RdpCertificateOffer) = true
        })

        trust.checkServerTrusted(chain, "RSA")

        val offer = requireNotNull(seen)
        assertSame(offer, trust.accepted)
        assertEquals("10.0.0.5", offer.host)
        assertEquals(3389, offer.port)
        assertTrue(offer.subject.contains("win-host"))
        // No platform trust manager was given, and the certificate names no address.
        assertTrue(!offer.trustedByPlatform)
        assertTrue(!offer.hostnameMatches)
    }
}
