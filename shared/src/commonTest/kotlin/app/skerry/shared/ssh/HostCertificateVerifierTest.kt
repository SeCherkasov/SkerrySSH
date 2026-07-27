package app.skerry.shared.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CA_FP = "SHA256:CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"
private const val OTHER_CA_FP = "SHA256:DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD"
private const val HOST_KEY_FP = "SHA256:HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH"
private const val CERT_FP = "SHA256:EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE"

private const val NOW = 1_800_000_000L

class HostCertificateVerifierTest {

    private fun certificate(
        principals: List<String> = listOf("web-01.prod.example.com"),
        validAfter: Long = NOW - 3600,
        validBefore: Long = NOW + 3600,
        hostCertificate: Boolean = true,
        criticalOptions: List<String> = emptyList(),
        caFingerprint: String = CA_FP,
        caSignatureVerified: Boolean = true,
    ) = OfferedHostCertificate(
        keyType = "ssh-ed25519",
        fingerprint = HOST_KEY_FP,
        caKeyType = "ssh-ed25519",
        caFingerprint = caFingerprint,
        principals = principals,
        validAfterEpochSeconds = validAfter,
        validBeforeEpochSeconds = validBefore,
        hostCertificate = hostCertificate,
        criticalOptions = criticalOptions,
        caSignatureVerified = caSignatureVerified,
    )

    private fun offer(cert: OfferedHostCertificate? = certificate(), host: String = "web-01.prod.example.com") =
        HostKeyOffer(
            host = host,
            port = 22,
            keyType = if (cert == null) "ssh-ed25519" else "ssh-ed25519-cert-v01@openssh.com",
            fingerprint = if (cert == null) HOST_KEY_FP else CERT_FP,
            certificate = cert,
        )

    private fun verifier(
        cas: List<TrustedCa> = listOf(trustedCa()),
        fallback: RecordingVerifier = RecordingVerifier(),
        store: InMemoryTrustedCaStore = InMemoryTrustedCaStore(cas),
    ) = HostCertificateVerifier(store, fallback) { NOW }

    private fun trustedCa(pattern: String = "*.prod.example.com", fingerprint: String = CA_FP) =
        TrustedCa(id = "ca-1", hostPattern = pattern, keyType = "ssh-ed25519", publicKey = "AAAA", fingerprint = fingerprint)

    @Test
    fun `a bare host key is left to the fallback verifier`() {
        val fallback = RecordingVerifier(answer = true)
        val verifier = HostCertificateVerifier(InMemoryTrustedCaStore(listOf(trustedCa())), fallback) { NOW }

        assertTrue(verifier.verify(offer(cert = null)))
        assertEquals(offer(cert = null), fallback.seen.single())
    }

    @Test
    fun `the fallback decides for a bare key`() {
        val fallback = RecordingVerifier(answer = false)
        val verifier = HostCertificateVerifier(InMemoryTrustedCaStore(listOf(trustedCa())), fallback) { NOW }

        assertFalse(verifier.verify(offer(cert = null)))
    }

    @Test
    fun `accepts a certificate signed by a trusted CA without recording anything`() {
        val fallback = RecordingVerifier()
        val verifier = HostCertificateVerifier(InMemoryTrustedCaStore(listOf(trustedCa())), fallback) { NOW }

        assertTrue(verifier.verify(offer()))
        // Nothing goes through TOFU: a certificate is re-issued on a schedule, and remembering
        // either the certificate or the key would turn every rotation into "host key changed".
        assertTrue(fallback.seen.isEmpty())
    }

    @Test
    fun `falls back to the key inside the certificate when the CA is unknown`() {
        val fallback = RecordingVerifier(answer = true)
        val verifier = HostCertificateVerifier(InMemoryTrustedCaStore(emptyList()), fallback) { NOW }

        assertTrue(verifier.verify(offer()))

        // TOFU must see the host's own key, not the certificate: the certificate's fingerprint
        // changes on every re-issue, the key inside it does not.
        val seen = fallback.seen.single()
        assertEquals("ssh-ed25519", seen.keyType)
        assertEquals(HOST_KEY_FP, seen.fingerprint)
        assertNull(seen.certificate)
    }

    @Test
    fun `a rotated certificate over the same key stays trusted under TOFU`() {
        val store = InMemoryKnownHosts()
        val verifier = HostCertificateVerifier(InMemoryTrustedCaStore(emptyList()), TofuHostKeyVerifier(store)) { NOW }
        assertTrue(verifier.verify(offer()))

        val reissued = offer(cert = certificate(validAfter = NOW, validBefore = NOW + 7200))
        assertTrue(verifier.verify(reissued.copy(fingerprint = "SHA256:FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")))
        assertEquals(1, store.all().size)
        assertEquals("ssh-ed25519", store.all().single().keyType)
    }

    @Test
    fun `a CA trusted for other hosts does not cover this one`() {
        val fallback = RecordingVerifier(answer = true)
        val cas = InMemoryTrustedCaStore(listOf(trustedCa(pattern = "*.staging.example.com")))
        val verifier = HostCertificateVerifier(cas, fallback) { NOW }

        assertTrue(verifier.verify(offer()))
        // Not an outright rejection: the CA simply doesn't apply here, so this is an ordinary host.
        assertEquals(1, fallback.seen.size)
    }

    @Test
    fun `a certificate from a different CA does not match a trusted one`() {
        val fallback = RecordingVerifier(answer = true)
        val verifier = HostCertificateVerifier(InMemoryTrustedCaStore(listOf(trustedCa())), fallback) { NOW }

        assertTrue(verifier.verify(offer(cert = certificate(caFingerprint = OTHER_CA_FP))))
        assertEquals(1, fallback.seen.size)
    }

    @Test
    fun `rejects a user certificate presented as a host key`() {
        // sshj verifies the CA signature, the principals and the validity window during KEX, but
        // not the certificate's type — a user certificate from the same CA would otherwise pass.
        val fallback = RecordingVerifier(answer = true)
        val verifier = HostCertificateVerifier(InMemoryTrustedCaStore(listOf(trustedCa())), fallback) { NOW }

        assertFalse(verifier.verify(offer(cert = certificate(hostCertificate = false))))
        assertTrue(fallback.seen.isEmpty())
    }

    @Test
    fun `rejects a certificate carrying critical options`() {
        val verifier = verifier()
        assertFalse(verifier.verify(offer(cert = certificate(criticalOptions = listOf("force-command")))))
    }

    @Test
    fun `rejects an expired certificate`() {
        val verifier = verifier()
        assertFalse(verifier.verify(offer(cert = certificate(validBefore = NOW - 1))))
    }

    @Test
    fun `rejects a certificate that is not valid yet`() {
        val verifier = verifier()
        assertFalse(verifier.verify(offer(cert = certificate(validAfter = NOW + 1))))
    }

    @Test
    fun `accepts a certificate with no expiry`() {
        val verifier = verifier()
        assertTrue(verifier.verify(offer(cert = certificate(validBefore = Long.MAX_VALUE))))
    }

    @Test
    fun `rejects a certificate issued for another host`() {
        val verifier = verifier()
        assertFalse(verifier.verify(offer(cert = certificate(principals = listOf("db-01.prod.example.com")))))
    }

    @Test
    fun `matches a principal written as a pattern`() {
        val verifier = verifier()
        assertTrue(verifier.verify(offer(cert = certificate(principals = listOf("*.prod.example.com")))))
    }

    @Test
    fun `an empty principal list means any host`() {
        val verifier = verifier()
        assertTrue(verifier.verify(offer(cert = certificate(principals = emptyList()))))
    }

    @Test
    fun `rejects a certificate whose CA signature was not verified by the transport`() {
        val fallback = RecordingVerifier(answer = true)
        val verifier = HostCertificateVerifier(InMemoryTrustedCaStore(listOf(trustedCa())), fallback) { NOW }

        assertFalse(verifier.verify(offer(cert = certificate(caSignatureVerified = false))))
        assertTrue(fallback.seen.isEmpty())
    }

    @Test
    fun `rejects while the CA store is unreadable instead of falling back to TOFU`() {
        // Locked vault mid-handshake: without the trusted set we can't tell "no CA configured"
        // from "the CA that covers this host is in there". Fail closed, like the TOFU verifier.
        val fallback = RecordingVerifier(answer = true)
        val cas = InMemoryTrustedCaStore(listOf(trustedCa())).apply { readable = false }
        val verifier = HostCertificateVerifier(cas, fallback) { NOW }

        assertFalse(verifier.verify(offer()))
        assertTrue(fallback.seen.isEmpty())
    }

    @Test
    fun `an unreadable CA store still lets a bare key through to the fallback`() {
        // A bare key never consults the CA list, so its own fail-closed rule is the one that counts.
        val fallback = RecordingVerifier(answer = true)
        val cas = InMemoryTrustedCaStore(listOf(trustedCa())).apply { readable = false }
        val verifier = HostCertificateVerifier(cas, fallback) { NOW }

        assertTrue(verifier.verify(offer(cert = null)))
    }

    @Test
    fun `a certificate whose CA key did not parse never matches a stored authority`() {
        // Both fingerprints blank must not read as "the same CA": a corrupt synced record with an
        // empty fingerprint would otherwise vouch for every unparsable certificate.
        val fallback = RecordingVerifier(answer = true)
        val cas = InMemoryTrustedCaStore(listOf(trustedCa(fingerprint = "")))
        val verifier = HostCertificateVerifier(cas, fallback) { NOW }

        assertTrue(verifier.verify(offer(cert = certificate(caFingerprint = ""))))
        assertEquals(1, fallback.seen.size, "must fall through to TOFU, not be trusted")
    }

    @Test
    fun `picks the CA that covers the host among several`() {
        val fallback = RecordingVerifier(answer = true)
        val cas = InMemoryTrustedCaStore(
            listOf(
                trustedCa(pattern = "*.staging.example.com", fingerprint = OTHER_CA_FP).copy(id = "ca-0"),
                trustedCa(pattern = "*.prod.example.com"),
            ),
        )
        val verifier = HostCertificateVerifier(cas, fallback) { NOW }

        assertTrue(verifier.verify(offer()))
        assertTrue(fallback.seen.isEmpty())
    }
}

/** Records what it was asked about; answers [answer]. */
private class RecordingVerifier(private val answer: Boolean = true) : HostKeyVerifier {
    val seen = mutableListOf<HostKeyOffer>()
    override fun verify(offer: HostKeyOffer): Boolean {
        seen += offer
        return answer
    }
}

private class InMemoryTrustedCaStore(initial: List<TrustedCa> = emptyList()) : TrustedCaStore {
    private val entries = initial.toMutableList()
    var readable = true
    override fun all(): List<TrustedCa> = entries.toList()
    override fun allOrNull(): List<TrustedCa>? = if (readable) all() else null
    override fun put(ca: TrustedCa) {
        entries.removeAll { it.id == ca.id }
        entries += ca
    }

    override fun remove(id: String) {
        entries.removeAll { it.id == id }
    }
}

private class InMemoryKnownHosts : KnownHostsStore {
    private val entries = mutableListOf<KnownHost>()
    override fun all(): List<KnownHost> = entries.toList()
    override fun add(host: KnownHost) {
        entries += host
    }

    override fun replace(host: KnownHost) {
        entries.removeAll { it.host == host.host && it.port == host.port && it.keyType == host.keyType }
        entries += host
    }

    override fun remove(host: String, port: Int, keyType: String) {
        entries.removeAll { it.host == host && it.port == port && it.keyType == keyType }
    }
}
