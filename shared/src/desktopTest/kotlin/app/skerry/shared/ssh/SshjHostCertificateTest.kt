package app.skerry.shared.ssh

import kotlinx.coroutines.test.runTest
import org.apache.sshd.certificate.OpenSshCertificateBuilder
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.OpenSshCertificate
import org.apache.sshd.common.keyprovider.HostKeyCertificateProvider
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val CERT_USER = "skerry"
private const val CERT_PASSWORD = "correct horse battery staple"

/**
 * Host certificate verification against an embedded MINA SSHD that presents a CA-signed host
 * certificate instead of a bare key — the `@cert-authority` path end to end: what the server sends,
 * what sshj hands us, and what [HostCertificateVerifier] decides.
 */
class SshjHostCertificateTest {

    private val caKeyPair: KeyPair = generateEcKeyPair()
    private val hostKeyPair: KeyPair = generateEcKeyPair()
    private val caFingerprint: String = opensshFingerprint(caKeyPair.public)
    private val hostKeyFingerprint: String = opensshFingerprint(hostKeyPair.public)

    private var server: SshServer? = null

    /** What the running server presents as its host certificate; swapped in place to model re-issue. */
    @Volatile
    private var presented: OpenSshCertificate? = null

    @BeforeTest
    fun resetServer() {
        server = null
        presented = null
    }

    @AfterTest
    fun stopServer() {
        server?.stop(true)
    }

    private fun startServer(certificate: OpenSshCertificate) {
        presented = certificate
        if (server != null) return
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKeyPair)
            hostKeyCertificateProvider = HostKeyCertificateProvider { listOfNotNull(presented) }
            setPasswordAuthenticator { user, password, _ -> user == CERT_USER && password == CERT_PASSWORD }
            commandFactory = ProcessShellCommandFactory.INSTANCE
            start()
        }
    }

    private fun hostCertificate(
        principals: List<String> = listOf("127.0.0.1"),
        validAfter: Instant = Instant.now().minusSeconds(3600),
        validBefore: Instant = Instant.now().plusSeconds(3600),
        serial: Long = 1,
    ): OpenSshCertificate =
        OpenSshCertificateBuilder.hostCertificate()
            .publicKey(hostKeyPair.public)
            .id("skerry-test")
            .serial(serial)
            .principals(principals)
            .validAfter(validAfter)
            .validBefore(validBefore)
            .sign(caKeyPair, KeyUtils.getKeyType(caKeyPair))

    private fun port(): Int = requireNonNullServer().port

    private fun requireNonNullServer(): SshServer = server ?: error("server not started")

    private fun target() = SshTarget(host = "127.0.0.1", port = port(), username = CERT_USER)

    private fun trustedCa(pattern: String = "127.0.0.1") = TrustedCa(
        id = "ca-1",
        hostPattern = pattern,
        keyType = KeyUtils.getKeyType(caKeyPair),
        publicKey = "",
        fingerprint = caFingerprint,
    )

    private fun verifier(cas: List<TrustedCa>, known: KnownHostsStore) =
        HostCertificateVerifier(FixedCaStore(cas), TofuHostKeyVerifier(known)) { Instant.now().epochSecond }

    @Test
    fun `connects to a host whose certificate comes from a trusted CA`() = runTest {
        startServer(hostCertificate())
        val known = RecordingKnownHosts()

        val connection = SshjTransport(verifier(listOf(trustedCa()), known))
            .connect(target(), SshAuth.Password(CERT_PASSWORD))

        assertTrue(connection.isConnected)
        // Nothing is remembered: the CA is the trust anchor, and the certificate is re-issued.
        assertEquals(emptyList(), known.all())
        connection.disconnect()
    }

    @Test
    fun `an untrusted CA leaves the host key itself to TOFU`() = runTest {
        startServer(hostCertificate())
        val known = RecordingKnownHosts()

        val connection = SshjTransport(verifier(emptyList(), known))
            .connect(target(), SshAuth.Password(CERT_PASSWORD))
        connection.disconnect()

        // The remembered key is the host's own, not the certificate blob wrapped around it.
        val remembered = known.all().single()
        assertEquals(hostKeyFingerprint, remembered.fingerprint)
        assertEquals(KeyUtils.getKeyType(hostKeyPair), remembered.keyType)
    }

    @Test
    fun `a re-issued certificate over the same key is not a key change`() = runTest {
        val known = RecordingKnownHosts()
        startServer(hostCertificate(serial = 1))
        SshjTransport(verifier(emptyList(), known)).connect(target(), SshAuth.Password(CERT_PASSWORD)).disconnect()

        // Same host key, freshly issued certificate — what an hourly issuer produces. Re-issued on
        // the running server: a restart would land on another port, which is a different identity.
        startServer(hostCertificate(serial = 2, validBefore = Instant.now().plusSeconds(7200)))
        val connection = SshjTransport(verifier(emptyList(), known)).connect(target(), SshAuth.Password(CERT_PASSWORD))

        assertTrue(connection.isConnected)
        assertEquals(1, known.all().size)
        connection.disconnect()
    }

    @Test
    fun `rejects a certificate issued for a different host`() = runTest {
        startServer(hostCertificate(principals = listOf("other.example.com")))

        assertFailsWith<SshHostKeyRejectedException> {
            SshjTransport(verifier(listOf(trustedCa()), RecordingKnownHosts()))
                .connect(target(), SshAuth.Password(CERT_PASSWORD))
        }
    }

    @Test
    fun `rejects an expired certificate`() = runTest {
        startServer(
            hostCertificate(
                validAfter = Instant.now().minusSeconds(7200),
                validBefore = Instant.now().minusSeconds(60),
            ),
        )

        assertFailsWith<SshHostKeyRejectedException> {
            SshjTransport(verifier(listOf(trustedCa()), RecordingKnownHosts()))
                .connect(target(), SshAuth.Password(CERT_PASSWORD))
        }
    }

    @Test
    fun `a CA trusted for other hosts does not cover this one`() = runTest {
        startServer(hostCertificate())
        val known = RecordingKnownHosts()

        SshjTransport(verifier(listOf(trustedCa(pattern = "*.example.com")), known))
            .connect(target(), SshAuth.Password(CERT_PASSWORD))
            .disconnect()

        // Falls through to TOFU rather than failing: the CA simply says nothing about this host.
        assertEquals(hostKeyFingerprint, known.all().single().fingerprint)
    }
}

/** P-256 pair; the JDK provider is enough and needs no EdDSA extras on the MINA side. */
private fun generateEcKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

private class FixedCaStore(private val entries: List<TrustedCa>) : TrustedCaStore {
    override fun all(): List<TrustedCa> = entries
    override fun put(ca: TrustedCa) = error("read-only")
    override fun remove(id: String) = error("read-only")
}

private class RecordingKnownHosts : KnownHostsStore {
    private val entries = mutableListOf<KnownHost>()

    @Synchronized
    override fun all(): List<KnownHost> = entries.toList()

    @Synchronized
    override fun add(host: KnownHost) {
        entries += host
    }

    @Synchronized
    override fun replace(host: KnownHost) {
        entries.removeAll { it.host == host.host && it.port == host.port && it.keyType == host.keyType }
        entries += host
    }

    @Synchronized
    override fun remove(host: String, port: Int, keyType: String) {
        entries.removeAll { it.host == host && it.port == port && it.keyType == keyType }
    }
}
