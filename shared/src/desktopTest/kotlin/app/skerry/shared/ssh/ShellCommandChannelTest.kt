package app.skerry.shared.ssh

import app.skerry.shared.container.ContainerRuntime
import app.skerry.shared.container.ContainerSpec
import app.skerry.shared.container.ContainerTransport
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val USER = "skerry"
private const val PASSWORD = "correct horse battery staple"
private const val TIMEOUT_MS = 15_000L

/**
 * The interactive channel with [SshTarget.shellCommand] set: the PTY is allocated as usual, but the
 * server is asked to run that command instead of the login shell (container profiles). Verified
 * against an embedded Apache MINA SSHD server that reports which of the two it was asked for.
 */
class ShellCommandChannelTest {

    private lateinit var server: SshServer
    private val requestedCommands = LinkedBlockingQueue<String>()

    @BeforeTest
    fun startServer() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
            shellFactory = ShellFactory { EchoLineCommand("login-shell") }
            commandFactory = CommandFactory { _, command ->
                requestedCommands.put(command)
                EchoLineCommand("ran: $command")
            }
            start()
        }
    }

    @AfterTest
    fun stopServer() {
        server.stop(true)
    }

    private suspend fun connect(target: SshTarget): SshConnection =
        SshjTransport { _, _, _, _ -> true }.connect(target, SshAuth.Password(PASSWORD))

    private fun target(command: List<String>? = null) = SshTarget(
        host = "127.0.0.1",
        port = server.port,
        username = USER,
        shellCommand = command,
    )

    @Test
    fun `without a command the channel is the login shell`() = runBlocking {
        val connection = connect(target())
        try {
            val shell = connection.openShell(PtySize(cols = 80, rows = 24))
            withTimeout(TIMEOUT_MS) {
                assertEquals("login-shell", shell.output.first().decodeToString().trim())
            }
            assertNull(requestedCommands.poll(1, TimeUnit.SECONDS))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a shell command runs on the pty instead of the login shell`() = runBlocking {
        val connection = connect(target(listOf("docker", "exec", "-i", "-t", "web 1", "sh")))
        try {
            val shell = connection.openShell(PtySize(cols = 80, rows = 24))
            withTimeout(TIMEOUT_MS) {
                assertEquals("ran: docker exec -i -t 'web 1' sh", shell.output.first().decodeToString().trim())
            }
            // Quoting happens on our side: the server sees the name as ONE argument, not as
            // `web` plus a stray `1`.
            assertEquals("docker exec -i -t 'web 1' sh", requestedCommands.poll(1, TimeUnit.SECONDS))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a container profile execs into the container over ssh`() = runBlocking {
        val transport = ContainerTransport(SshjTransport { _, _, _, _ -> true })
        val connection = transport.connect(
            SshTarget(
                host = "127.0.0.1",
                port = server.port,
                username = USER,
                connectionType = ConnectionType.CONTAINER,
                container = ContainerSpec(runtime = ContainerRuntime.DOCKER, target = "web"),
            ),
            SshAuth.Password(PASSWORD),
        )
        try {
            connection.openShell(PtySize(cols = 80, rows = 24))
            assertEquals("docker exec -i -t web sh", requestedCommands.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `resizing an exec channel does not fail the session`() = runBlocking {
        val connection = connect(target(listOf("sh")))
        try {
            val shell = connection.openShell(PtySize(cols = 80, rows = 24))
            shell.resize(PtySize(cols = 120, rows = 40))
        } finally {
            connection.disconnect()
        }
    }
}

/** Test server command/shell: writes one line and keeps the channel open until it's closed. */
private class EchoLineCommand(private val line: String) : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var exit: ExitCallback? = null

    override fun setInputStream(value: InputStream) { input = value }
    override fun setOutputStream(value: OutputStream) { output = value }
    override fun setErrorStream(value: OutputStream) { /* nothing writes to stderr here */ }
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        output.write("$line\n".encodeToByteArray())
        output.flush()
    }

    override fun destroy(channel: ChannelSession) {
        exit?.onExit(0)
    }
}
