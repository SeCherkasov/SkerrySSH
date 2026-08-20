package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The certificate/hostname check the connector runs from inside the TLS handshake, where no finished
 * `SSLSession` exists yet to hand to the platform's verifier.
 */
class RdpHostnameMatchTest {

    @Test
    fun `a dNSName entry matches the host it names, whatever the case`() {
        val certificate = RdpTestCertificates.certificate(
            commonName = "irrelevant",
            dnsNames = listOf("Rdp.Example.Com"),
        )

        assertTrue(certificateMatchesHost("rdp.example.com", certificate))
        assertTrue(certificateMatchesHost("RDP.EXAMPLE.COM", certificate))
        // A trailing root dot is the same name.
        assertTrue(certificateMatchesHost("rdp.example.com.", certificate))
        assertFalse(certificateMatchesHost("other.example.com", certificate))
    }

    @Test
    fun `a wildcard covers one label and never the registry`() {
        val wildcard = RdpTestCertificates.certificate(dnsNames = listOf("*.example.com"))

        assertTrue(certificateMatchesHost("rdp.example.com", wildcard))
        assertFalse(certificateMatchesHost("a.b.example.com", wildcard))
        assertFalse(certificateMatchesHost("example.com", wildcard))

        // "*.com" would otherwise match every host in the TLD.
        val tooBroad = RdpTestCertificates.certificate(dnsNames = listOf("*.com"))
        assertFalse(certificateMatchesHost("example.com", tooBroad))
        assertFalse(certificateMatchesHost("rdp.com", tooBroad))
    }

    @Test
    fun `the common name answers only when there is no dNSName entry`() {
        val bare = RdpTestCertificates.certificate(commonName = "win-host")
        assertTrue(certificateMatchesHost("win-host", bare))
        assertFalse(certificateMatchesHost("other-host", bare))

        // RFC 6125: once the certificate carries dNSName entries, they describe it completely.
        val withSan = RdpTestCertificates.certificate(
            commonName = "win-host",
            dnsNames = listOf("rdp.example.com"),
        )
        assertFalse(certificateMatchesHost("win-host", withSan))
    }

    @Test
    fun `an address matches an iPAddress entry and nothing else`() {
        val certificate = RdpTestCertificates.certificate(
            commonName = "10.0.0.5",
            dnsNames = listOf("10.0.0.5"),
            ipAddresses = listOf("10.0.0.5"),
        )

        assertTrue(certificateMatchesHost("10.0.0.5", certificate))
        assertFalse(certificateMatchesHost("10.0.0.6", certificate))

        // The same text as a name entry is not an address entry, and the common name never
        // stands in for one.
        val nameOnly = RdpTestCertificates.certificate(
            commonName = "10.0.0.5",
            dnsNames = listOf("10.0.0.5"),
        )
        assertFalse(certificateMatchesHost("10.0.0.5", nameOnly))
    }

    @Test
    fun `an IPv6 address is compared as an address, not as text`() {
        val certificate = RdpTestCertificates.certificate(ipAddresses = listOf("0:0:0:0:0:0:0:1"))

        assertTrue(certificateMatchesHost("::1", certificate))
        assertTrue(certificateMatchesHost("[::1]", certificate))
        assertFalse(certificateMatchesHost("::2", certificate))
    }

    @Test
    fun `a subjectAltName extension retires the common name, whatever it holds`() {
        // RFC 6125: the extension describes the certificate completely once it is there. An
        // iPAddress-only certificate names no host, even if its common name looks like one.
        val addressOnly = RdpTestCertificates.certificate(
            commonName = "win-host",
            ipAddresses = listOf("10.0.0.5"),
        )

        assertFalse(certificateMatchesHost("win-host", addressOnly))
        assertTrue(certificateMatchesHost("10.0.0.5", addressOnly))
    }

    @Test
    fun `an unparseable subjectAltName names nothing and does not fall back to the common name`() {
        val malformed = RdpTestCertificates.certificate(
            commonName = "win-host",
            malformedAlternativeNames = true,
        )

        assertFalse(certificateMatchesHost("win-host", malformed))
    }

    @Test
    fun `an escaped comma inside another attribute is not an attribute separator`() {
        // Renders as `O=\,CN=evil.example.com` — a subject with no common name at all.
        val certificate = RdpTestCertificates.certificate(
            distinguishedName = """O=\,CN=evil.example.com""",
        )

        assertFalse(certificateMatchesHost("evil.example.com", certificate))
    }

    @Test
    fun `a multi-valued RDN still yields its common name`() {
        val certificate = RdpTestCertificates.certificate(distinguishedName = "CN=win-host+OU=lab")

        assertTrue(certificateMatchesHost("win-host", certificate))
    }

    @Test
    fun `only a real IPv6 literal is handed to the resolver`() {
        // Anything this lets through and InetAddress cannot parse becomes a DNS lookup on the
        // handshake thread — and the host is attacker-influenced through Server Redirection.
        assertTrue(isIpv6Literal("::1"))
        assertTrue(isIpv6Literal("0:0:0:0:0:0:0:1"))
        assertTrue(isIpv6Literal("fe80::1%eth0"))
        assertTrue(isIpv6Literal("::ffff:10.0.0.5"))

        assertFalse(isIpv6Literal("dead:beef"), "a two-group address is not a literal")
        assertFalse(isIpv6Literal("rdp.example.com:3389"))
        assertFalse(isIpv6Literal("1::2::3"))
        assertFalse(isIpv6Literal("0:0:0:0:0:0:0:1:2"))
    }

    @Test
    fun `a plus sign in the common name survives unescaping`() {
        // RFC 2253 renders it `\+`, and `"+1".toIntOrNull(16)` is 1 — decoding it as a hex pair
        // would turn the name into a control character.
        val certificate = RdpTestCertificates.certificate(distinguishedName = """CN=win\+host""")

        assertTrue(certificateMatchesHost("win+host", certificate))
    }

    @Test
    fun `a certificate without a usable name matches nothing`() {
        val certificate = RdpTestCertificates.certificate(dnsNames = listOf("rdp.example.com"))

        assertFalse(certificateMatchesHost("", certificate))
        assertFalse(certificateMatchesHost("   ", certificate))
    }
}
