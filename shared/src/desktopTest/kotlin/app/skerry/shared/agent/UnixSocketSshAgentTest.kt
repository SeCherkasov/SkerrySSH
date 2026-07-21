package app.skerry.shared.agent

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The `SSH_AUTH_SOCK` socket, driven by a client that speaks the real agent protocol — the role
 * `ssh` plays when it asks the agent for keys.
 */
class UnixSocketSshAgentTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dir: Path
    private var agent: UnixSocketSshAgent? = null

    @AfterTest
    fun tearDown() {
        agent?.stop()
        scope.cancel()
        if (::dir.isInitialized) Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { it.toFile().delete() }
    }

    private fun start(identities: List<SshAgentIdentity>): Path {
        dir = Files.createTempDirectory("skerry-agent-test")
        val keys = object : SshAgentKeys {
            override suspend fun identities(scope: SshAgentScope) = identities
            override suspend fun sign(keyBlob: ByteArray, data: ByteArray, flags: Int, scope: SshAgentScope): SshAgentSignature? = null
        }
        // Own dispatcher: the shared agent one is capped and other suites in this JVM park readers
        // on it, which would leave this listener's accept() waiting for a free thread.
        return UnixSocketSshAgent(dir.resolve("run"), SshAgentService(keys), scope, Dispatchers.IO)
            .also { agent = it }
            .start()
    }

    /** One agent round trip over the socket, framing included. */
    private fun request(path: Path, message: ByteArray): ByteArray =
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(path))
            val output = Channels.newOutputStream(channel)
            output.write(SshAgentCodec.frame(message))
            output.flush()
            val input = Channels.newInputStream(channel)
            val header = ByteArray(4).also { input.readNBytes(it, 0, 4) }
            val length = (header[0].toInt() and 0xFF shl 24) or (header[1].toInt() and 0xFF shl 16) or
                (header[2].toInt() and 0xFF shl 8) or (header[3].toInt() and 0xFF)
            input.readNBytes(length)
        }

    /**
     * Run blocking socket work with a wall-clock deadline, so a regression fails the test instead
     * of hanging the whole suite. Not `runTest`: its virtual clock does not advance while a thread
     * sits in a blocking read, which makes `withTimeout` around one both useless and flaky.
     */
    private fun <T> withDeadline(block: () -> T): T {
        val worker = Executors.newSingleThreadExecutor()
        return try {
            worker.submit(block).get(SOCKET_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            fail("the agent did not answer within \${SOCKET_TIMEOUT_MILLIS}ms")
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `serves the identity list over the socket`() {
        val identities = listOf(SshAgentIdentity(byteArrayOf(1, 2, 3), "work key"))
        val path = start(identities)
        assertContentEquals(SshAgentCodec.identitiesAnswer(identities), withDeadline { request(path, byteArrayOf(11)) })
    }

    @Test
    fun `serves several clients in a row`() {
        // `ssh` opens a new connection per invocation; one client hanging up must not end the agent.
        val path = start(emptyList())
        repeat(3) {
            assertContentEquals(SshAgentCodec.identitiesAnswer(emptyList()), withDeadline { request(path, byteArrayOf(11)) })
        }
    }

    @Test
    fun `the socket is reachable only by its owner`() {
        // The socket has no other access control: anything that can open it can make the agent sign.
        val path = start(emptyList())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(path),
        )
        assertEquals(
            PosixFilePermission.entries.filter { it.name.startsWith("OWNER") }.toSet(),
            Files.getPosixFilePermissions(path.parent),
        )
    }

    @Test
    fun `stop ends connections that are still open`() {
        // A long-lived client (an `ssh` session on the far end of a shell) keeps its connection
        // open with no requests; switching the socket off must not leave it holding a thread.
        val path = start(emptyList())
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(path))
            val input = Channels.newInputStream(channel)
            agent?.stop()
            val eof = withDeadline { runCatching { input.read() }.getOrDefault(-1) }
            assertEquals(-1, eof, "connection outlived the agent")
        }
    }

    @Test
    fun `a client from the previous round is not served after a restart`() {
        // Switching the socket off and straight back on is one click in Settings; a connection that
        // belonged to the old listener must end with it, not be picked up by the new one.
        val path = start(emptyList())
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(path))
            val input = Channels.newInputStream(channel)
            agent?.stop()
            agent?.start()

            val eof = withDeadline { runCatching { input.read() }.getOrDefault(-1) }

            assertEquals(-1, eof, "a connection from before the restart is still being served")
        }
    }

    @Test
    fun `stop removes the socket`() {
        val path = start(emptyList())
        assertTrue(Files.exists(path))
        agent?.stop()
        assertFalse(Files.exists(path), "socket file outlived the agent")
    }

    @Test
    fun `start replaces a socket left behind by a crash`() {
        val path = start(emptyList())
        agent?.stop()
        Files.createFile(path)
        assertEquals(path, agent?.start())
    }

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS = 10_000L
    }
}
