package app.skerry.shared.ssh

import kotlinx.coroutines.test.runTest
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val USER = "skerry"
private const val PASSWORD = "correct horse battery staple"
private const val JUMP_USER = "gate"
private const val JUMP_PASSWORD = "open sesame"

/**
 * ProxyJump integration tests against embedded Apache MINA SSHD servers: [jumpServer] allows
 * direct-tcpip forwarding (the hop), [targetServer] is the destination reached only through it.
 */
class SshjJumpTest {

    private lateinit var jumpServer: SshServer
    private lateinit var targetServer: SshServer

    @BeforeTest
    fun startServers() {
        jumpServer = newServer(JUMP_USER, JUMP_PASSWORD, forwarding = true)
        targetServer = newServer(USER, PASSWORD, forwarding = false)
    }

    @AfterTest
    fun stopServers() {
        jumpServer.stop(true)
        targetServer.stop(true)
    }

    private fun newServer(user: String, password: String, forwarding: Boolean): SshServer =
        SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { u, p, _ -> u == user && p == password }
            commandFactory = ProcessShellCommandFactory.INSTANCE
            if (forwarding) forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            start()
        }

    private fun jumpHop(auth: SshAuth = SshAuth.Password(JUMP_PASSWORD), next: SshJump? = null) =
        SshJump(host = "127.0.0.1", port = jumpServer.port, username = JUMP_USER, auth = auth, jump = next)

    private fun target(jump: SshJump?) =
        SshTarget(host = "127.0.0.1", port = targetServer.port, username = USER, jump = jump)

    private val acceptAll = HostKeyVerifier { _, _, _, _ -> true }

    @Test
    fun `connects through the jump host and executes a command`() = runTest {
        val connection = SshjTransport(acceptAll).connect(target(jumpHop()), SshAuth.Password(PASSWORD))
        try {
            assertTrue(connection.isConnected)
            assertEquals("hello", connection.exec("echo hello").stdout.trim())
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `connects through a two-hop chain`() = runTest {
        // The same forwarding-enabled server serves as both hops: entry hop -> itself -> target.
        val chain = jumpHop(next = jumpHop())
        val connection = SshjTransport(acceptAll).connect(target(chain), SshAuth.Password(PASSWORD))
        try {
            assertEquals("hi", connection.exec("echo hi").stdout.trim())
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `host key verifier sees the jump and the target hops`() = runTest {
        val seen = mutableListOf<Pair<String, Int>>()
        val recording = HostKeyVerifier { host, port, _, _ ->
            synchronized(seen) { seen += host to port }
            true
        }
        val connection = SshjTransport(recording).connect(target(jumpHop()), SshAuth.Password(PASSWORD))
        connection.disconnect()
        assertTrue("127.0.0.1" to jumpServer.port in seen, "jump hop not verified: $seen")
        assertTrue("127.0.0.1" to targetServer.port in seen, "target not verified: $seen")
    }

    @Test
    fun `rejected jump host key fails the connect and names the hop`() = runTest {
        val rejectJump = HostKeyVerifier { _, port, _, _ -> port != jumpServer.port }
        val e = assertFailsWith<SshHostKeyRejectedException> {
            SshjTransport(rejectJump).connect(target(jumpHop()), SshAuth.Password(PASSWORD))
        }
        // The user must be able to tell WHOSE key was rejected — a changed bastion key would
        // otherwise send them investigating the target's fingerprint (and vice versa).
        assertEquals("Jump host key rejected by verifier", e.message)
        awaitNoSessions(jumpServer)
    }

    @Test
    fun `rejected target host key names the target, not the hop`() = runTest {
        val rejectTarget = HostKeyVerifier { _, port, _, _ -> port != targetServer.port }
        val e = assertFailsWith<SshHostKeyRejectedException> {
            SshjTransport(rejectTarget).connect(target(jumpHop()), SshAuth.Password(PASSWORD))
        }
        assertEquals("Host key rejected by verifier", e.message)
        awaitNoSessions(jumpServer)
    }

    @Test
    fun `wrong jump credentials fail with an authentication error`() = runTest {
        assertFailsWith<SshAuthenticationException> {
            SshjTransport(acceptAll)
                .connect(target(jumpHop(auth = SshAuth.Password("wrong"))), SshAuth.Password(PASSWORD))
        }
        awaitNoSessions(jumpServer)
    }

    @Test
    fun `wrong target credentials close the jump connection too`() = runTest {
        assertFailsWith<SshAuthenticationException> {
            SshjTransport(acceptAll).connect(target(jumpHop()), SshAuth.Password("wrong"))
        }
        awaitNoSessions(jumpServer)
    }

    @Test
    fun `disconnect tears down the jump session as well`() = runTest {
        val connection = SshjTransport(acceptAll).connect(target(jumpHop()), SshAuth.Password(PASSWORD))
        connection.disconnect()
        awaitNoSessions(targetServer)
        awaitNoSessions(jumpServer)
    }

    /** Server-side session teardown is asynchronous; poll briefly instead of asserting instantly. */
    private fun awaitNoSessions(server: SshServer) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (server.activeSessions.isEmpty()) return
            Thread.sleep(50)
        }
        assertTrue(server.activeSessions.isEmpty(), "sessions still open on :${server.port}")
    }
}
