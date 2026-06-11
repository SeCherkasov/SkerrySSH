package app.skerry.shared.ssh

import kotlinx.coroutines.test.runTest
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val USER = "skerry"
private const val PASSWORD = "correct horse battery staple"

private val acceptAllKeys = HostKeyVerifier { _, _, _, _ -> true }

/** Интеграционные тесты SshjTransport против встроенного Apache MINA SSHD. */
class SshjTransportTest {

    private lateinit var server: SshServer

    // Ключ, который сервер считает авторизованным; приватная часть подаётся клиенту как PEM.
    private val authorizedKey: KeyPair = generateRsaKeyPair()

    @BeforeTest
    fun startServer() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0 // свободный порт выбирает ОС
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
            publickeyAuthenticator = PublickeyAuthenticator { user, key, _ ->
                user == USER && key.encoded.contentEquals(authorizedKey.public.encoded)
            }
            commandFactory = ProcessShellCommandFactory.INSTANCE
            start()
        }
    }

    @AfterTest
    fun stopServer() {
        server.stop(true)
    }

    private fun target() = SshTarget(host = "127.0.0.1", port = server.port, username = USER)

    private suspend fun connect(): SshConnection =
        SshjTransport(acceptAllKeys).connect(target(), SshAuth.Password(PASSWORD))

    @Test
    fun `connects with valid password and disconnects`() = runTest {
        val connection = connect()
        assertTrue(connection.isConnected)
        connection.disconnect()
        assertFalse(connection.isConnected)
    }

    @Test
    fun `rejects invalid password`() = runTest {
        assertFailsWith<SshAuthenticationException> {
            SshjTransport(acceptAllKeys).connect(target(), SshAuth.Password("wrong"))
        }
    }

    @Test
    fun `connects with an authorized private key`() = runTest {
        val connection = SshjTransport(acceptAllKeys)
            .connect(target(), SshAuth.PublicKey(pkcs8Pem(authorizedKey)))
        assertTrue(connection.isConnected)
        connection.disconnect()
    }

    @Test
    fun `rejects an unauthorized private key`() = runTest {
        assertFailsWith<SshAuthenticationException> {
            SshjTransport(acceptAllKeys)
                .connect(target(), SshAuth.PublicKey(pkcs8Pem(generateRsaKeyPair())))
        }
    }

    @Test
    fun `fails to connect when nobody listens`() = runTest {
        val unusedPort = server.port + 1
        assertFailsWith<SshConnectionException> {
            SshjTransport(acceptAllKeys)
                .connect(target().copy(port = unusedPort), SshAuth.Password(PASSWORD))
        }
    }

    @Test
    fun `executes command and captures stdout with exit code`() = runTest {
        val connection = connect()
        try {
            val result = connection.exec("echo hello")
            assertEquals(0, result.exitCode)
            assertEquals("hello\n", result.stdout)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `reports non-zero exit code`() = runTest {
        val connection = connect()
        try {
            assertEquals(1, connection.exec("false").exitCode)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `host key rejection aborts connect before auth`() = runTest {
        var seenKeyType: String? = null
        var seenFingerprint: String? = null
        val rejecting = HostKeyVerifier { _, _, keyType, fingerprint ->
            seenKeyType = keyType
            seenFingerprint = fingerprint
            false
        }

        assertFailsWith<SshHostKeyRejectedException> {
            SshjTransport(rejecting).connect(target(), SshAuth.Password(PASSWORD))
        }
        assertTrue(!seenKeyType.isNullOrBlank(), "verifier должен получить тип ключа")
        assertTrue(
            seenFingerprint.orEmpty().startsWith("SHA256:"),
            "fingerprint в формате OpenSSH, получено: $seenFingerprint",
        )
    }
}

private fun generateRsaKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

/** Приватный ключ в неэшифрованном PKCS#8 PEM — формат, который sshj распознаёт из содержимого. */
private fun pkcs8Pem(keyPair: KeyPair): String {
    val body = Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(keyPair.private.encoded)
    return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
}
