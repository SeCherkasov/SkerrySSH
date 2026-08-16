package app.skerry.shared.ssh

import kotlinx.coroutines.launch
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

/** How long the test server takes to answer authentication, so a cancel can land inside it. */
private const val AUTH_DELAY_MILLIS = 400L

private val acceptAllKeys = HostKeyVerifier { null }

/** Integration tests for SshjTransport against an embedded Apache MINA SSHD. */
class SshjTransportTest {

    private lateinit var server: SshServer

    // Key the server treats as authorized; the private part is fed to the client as PEM.
    private val authorizedKey: KeyPair = generateRsaKeyPair()

    @BeforeTest
    fun startServer() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0 // OS picks a free port
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

    /**
     * The dial is blocking from the first byte to the last, with no cancellation point in it: a
     * cancel arriving mid-handshake is noticed only once TCP, key exchange and userauth have all
     * finished, and `withContext` then throws away the connection they produced. Unless that
     * connection is closed on the way out, the user who closed the pane leaves an authenticated
     * session open on the server — one per ProxyJump hop, for the life of the process.
     */
    @Test
    fun `a connect cancelled mid-handshake leaves no session behind`() = kotlinx.coroutines.runBlocking {
        val live = java.util.concurrent.atomic.AtomicInteger()
        server.addSessionListener(object : org.apache.sshd.common.session.SessionListener {
            override fun sessionCreated(session: org.apache.sshd.common.session.Session) { live.incrementAndGet() }
            override fun sessionClosed(session: org.apache.sshd.common.session.Session) { live.decrementAndGet() }
        })
        val reachedAuth = java.util.concurrent.CountDownLatch(1)
        server.setPasswordAuthenticator { user, password, _ ->
            reachedAuth.countDown()
            Thread.sleep(AUTH_DELAY_MILLIS) // long enough for the cancel to land while it runs
            user == USER && password == PASSWORD
        }

        val job = launch(kotlinx.coroutines.Dispatchers.IO) { connect() }
        assertTrue(reachedAuth.await(5, java.util.concurrent.TimeUnit.SECONDS), "authentication never started")
        job.cancel() // the user closes the pane while it is connecting
        job.join()

        val deadline = System.currentTimeMillis() + 5_000
        while (live.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(0, live.get(), "an authenticated session was left open on the server")
    }

    @Test
    fun `transport socket has nagle disabled`() = runTest {
        val connection = connect()
        try {
            val socket = (connection as SshjConnection).transportSocket
            assertTrue(socket != null && socket.tcpNoDelay)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `exposes negotiated cipher after connect`() = runTest {
        val connection = connect()
        try {
            val cipher = connection.cipher
            assertTrue(
                !cipher.isNullOrBlank(),
                "connection should report the negotiated cipher, got: $cipher",
            )
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `exposes server version after connect`() = runTest {
        val connection = connect()
        try {
            val version = connection.serverVersion
            assertTrue(
                version != null && version.startsWith("SSH-2.0-"),
                "connection should report the server ident with the SSH-2.0- prefix, got: $version",
            )
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `measures round-trip after connect`() = runTest {
        val connection = connect()
        try {
            // MINA SSHD doesn't know keepalive@openssh.com and replies REQUEST_FAILURE — that's still
            // a completed round-trip, so the measurement should return a non-negative time (< timeout).
            val rtt = connection.measureRoundTrip()
            assertTrue(
                rtt != null && rtt >= 0 && rtt < 5_000,
                "round-trip should give a non-negative time under the timeout, got: $rtt",
            )
        } finally {
            connection.disconnect()
        }
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
        val rejecting = HostKeyVerifier { offer ->
            seenKeyType = offer.keyType
            seenFingerprint = offer.fingerprint
            HostKeyRefusal.KeyChanged
        }

        val rejection = assertFailsWith<SshHostKeyRejectedException> {
            SshjTransport(rejecting).connect(target(), SshAuth.Password(PASSWORD))
        }
        // The verifier runs on sshj's IO thread and the reason is read back here: without this the
        // transport could drop it and every other assertion would still hold.
        assertEquals(HostKeyRefusal.KeyChanged, rejection.refusal)
        assertTrue(!seenKeyType.isNullOrBlank(), "verifier should receive the key type")
        assertTrue(
            seenFingerprint.orEmpty().startsWith("SHA256:"),
            "fingerprint in OpenSSH format, got: $seenFingerprint",
        )
    }
}

private fun generateRsaKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

/** Private key as unencrypted PKCS#8 PEM — the format sshj recognizes from content. */
private fun pkcs8Pem(keyPair: KeyPair): String {
    val body = Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(keyPair.private.encoded)
    return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
}
