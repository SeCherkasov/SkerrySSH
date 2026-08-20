package app.skerry.shared.rdp

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
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
    fun serverContext(
        commonName: String = "rdp-test",
        dnsNames: List<String> = emptyList(),
        ipAddresses: List<String> = emptyList(),
    ): SSLContext {
        val issued = selfSigned(commonName, dnsNames, ipAddresses)
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", issued.privateKey, PASSWORD, arrayOf(issued.certificate))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, PASSWORD) }
        return SSLContext.getInstance("TLS").apply {
            init(managers.keyManagers, null, SecureRandom())
        }
    }

    /**
     * The certificate alone, for the checks that never open a socket. [distinguishedName] replaces
     * the whole subject when a DN more awkward than `CN=x` is the point, and
     * [malformedAlternativeNames] writes a `subjectAltName` extension that cannot be parsed.
     */
    fun certificate(
        commonName: String = "rdp-test",
        dnsNames: List<String> = emptyList(),
        ipAddresses: List<String> = emptyList(),
        distinguishedName: String? = null,
        malformedAlternativeNames: Boolean = false,
    ): X509Certificate = selfSigned(
        commonName,
        dnsNames,
        ipAddresses,
        distinguishedName,
        malformedAlternativeNames,
    ).certificate

    private class Issued(val certificate: X509Certificate, val privateKey: PrivateKey)

    private fun selfSigned(
        commonName: String,
        dnsNames: List<String>,
        ipAddresses: List<String>,
        distinguishedName: String? = null,
        malformedAlternativeNames: Boolean = false,
    ): Issued {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name(distinguishedName ?: "CN=$commonName")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 60_000),
            Date(now + 3_600_000),
            name,
            keyPair.public,
        )
        val alternatives = dnsNames.map { GeneralName(GeneralName.dNSName, it) } +
            ipAddresses.map { GeneralName(GeneralName.iPAddress, it) }
        if (malformedAlternativeNames) {
            // A SEQUENCE whose dNSName entry claims three bytes and carries one.
            builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                byteArrayOf(0x30, 0x05, 0x82.toByte(), 0x03, 0x61),
            )
        } else if (alternatives.isNotEmpty()) {
            builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(alternatives.toTypedArray()),
            )
        }
        val holder = builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        return Issued(JcaX509CertificateConverter().getCertificate(holder), keyPair.private)
    }

    private val PASSWORD = charArrayOf('x')
}
