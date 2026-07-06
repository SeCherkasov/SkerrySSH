package app.skerry.shared.ssh

import app.skerry.shared.vault.CertificateFixtures
import kotlinx.coroutines.test.runTest
import org.apache.sshd.common.config.keys.OpenSshCertificate
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import java.security.PublicKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val acceptAllHostKeys = HostKeyVerifier { _, _, _, _ -> true }

/**
 * Integration tests for SSH certificate auth ([SshAuth.Certificate]) against an embedded
 * Apache MINA SSHD. The server trusts the test CA from [CertificateFixtures]: MINA itself
 * verifies the CA signature, validity, and principal on certificate login; our authenticator
 * only decides whether to trust the CA itself (by comparing its public key).
 */
class SshjCertificateAuthTest {

    private lateinit var server: SshServer
    private val trustedCa: PublicKey =
        PublicKeyEntry.parsePublicKeyEntry(CertificateFixtures.CA_PUBLIC_KEY).resolvePublicKey(null, null, null)

    @BeforeTest
    fun startServer() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            publickeyAuthenticator = PublickeyAuthenticator { _, key, _ ->
                // Accept only a certificate issued by our trusted CA. MINA already validated
                // signature/validity/principal before this call — an invalid cert never reaches here.
                key is OpenSshCertificate && key.caPubKey.encoded.contentEquals(trustedCa.encoded)
            }
            commandFactory = ProcessShellCommandFactory.INSTANCE
            start()
        }
    }

    @AfterTest
    fun stopServer() {
        server.stop(true)
    }

    private fun target(username: String) = SshTarget(host = "127.0.0.1", port = server.port, username = username)

    @Test
    fun `connects with a certificate from the trusted CA`() = runTest {
        val connection = SshjTransport(acceptAllHostKeys).connect(
            target(CertificateFixtures.ED25519_PRINCIPAL),
            SshAuth.Certificate(CertificateFixtures.ED25519_PRIVATE_KEY, CertificateFixtures.ED25519_CERT),
        )
        assertTrue(connection.isConnected)
        connection.disconnect()
    }

    @Test
    fun `rejects a bare key when the server trusts only certificates`() = runTest {
        // Same private key but no certificate: the authenticator only accepts OpenSshCertificate,
        // so a bare public key must be rejected — confirms the previous test passed via the
        // certificate, not the key.
        assertFailsWith<SshAuthenticationException> {
            SshjTransport(acceptAllHostKeys).connect(
                target(CertificateFixtures.ED25519_PRINCIPAL),
                SshAuth.PublicKey(CertificateFixtures.ED25519_PRIVATE_KEY),
            )
        }
    }
}
