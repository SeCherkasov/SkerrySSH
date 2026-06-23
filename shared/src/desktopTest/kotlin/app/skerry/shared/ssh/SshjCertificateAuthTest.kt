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
 * Интеграционные тесты аутентификации по SSH-сертификату ([SshAuth.Certificate]) против встроенного
 * Apache MINA SSHD. Сервер доверяет тестовому CA из [CertificateFixtures]: при входе по сертификату
 * MINA сам проверяет подпись CA, срок действия и principal, а наш authenticator решает лишь вопрос
 * доверия к самому удостоверяющему центру (сравнением его публичного ключа).
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
                // Принимаем только сертификат, выписанный нашим доверенным CA. Подпись/срок/principal
                // MINA уже проверил до этого вызова — невалидный сертификат сюда не дойдёт.
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
        // Тот же приватный ключ, но без сертификата: authenticator принимает лишь OpenSshCertificate,
        // поэтому голый публичный ключ должен быть отклонён — подтверждает, что прошлый тест прошёл
        // именно по сертификату, а не по ключу.
        assertFailsWith<SshAuthenticationException> {
            SshjTransport(acceptAllHostKeys).connect(
                target(CertificateFixtures.ED25519_PRINCIPAL),
                SshAuth.PublicKey(CertificateFixtures.ED25519_PRIVATE_KEY),
            )
        }
    }
}
