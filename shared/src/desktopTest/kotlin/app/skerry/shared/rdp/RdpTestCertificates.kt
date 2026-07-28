package app.skerry.shared.rdp

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Self-signed server certificate + TLS context for the transport tests — the shape every real RDP
 * host presents, since Windows generates its own certificate for Remote Desktop unless an
 * enterprise CA issued one.
 */
object RdpTestCertificates {

    /** A server-side [SSLContext] holding a fresh self-signed certificate for [commonName]. */
    fun serverContext(commonName: String = "rdp-test"): SSLContext {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=$commonName")
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 60_000),
            Date(now + 3_600_000),
            name,
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        val certificate: X509Certificate = JcaX509CertificateConverter().getCertificate(holder)

        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", keyPair.private, PASSWORD, arrayOf(certificate))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, PASSWORD) }
        return SSLContext.getInstance("TLS").apply {
            init(managers.keyManagers, null, SecureRandom())
        }
    }

    private val PASSWORD = charArrayOf('x')
}
