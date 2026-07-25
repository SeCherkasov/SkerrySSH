package app.skerry.shared.container

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [ContainerTransport] rides the regular SSH transport: it dials the host exactly as an SSH profile
 * would (same auth/jump/keep-alive) but runs the container's `exec` command instead of the login
 * shell. Covered here: what the inner transport is handed, and which SSH capabilities a container
 * session must not expose.
 */
class ContainerTransportTest {

    private val target = SshTarget(
        host = "docker-host",
        port = 2222,
        username = "ops",
        connectionType = ConnectionType.CONTAINER,
        keepAliveSeconds = 30,
        container = ContainerSpec(runtime = ContainerRuntime.DOCKER, target = "web"),
    )

    @Test
    fun `dials the host over ssh with the exec command`() = runTest {
        val inner = RecordingTransport()
        ContainerTransport(inner).connect(target, SshAuth.Password("s"))
        val dialed = inner.lastTarget!!
        assertEquals(ConnectionType.SSH, dialed.connectionType)
        assertEquals("docker-host", dialed.host)
        assertEquals(2222, dialed.port)
        assertEquals("ops", dialed.username)
        assertEquals(30, dialed.keepAliveSeconds)
        assertEquals(listOf("docker", "exec", "-i", "-t", "web", "sh"), dialed.shellCommand)
        // The spec has been turned into a command — the SSH leg has no business re-reading it.
        assertNull(dialed.container)
    }

    @Test
    fun `keeps the auth as given`() = runTest {
        val inner = RecordingTransport()
        val auth = SshAuth.Password("s3cret")
        ContainerTransport(inner).connect(target, auth)
        assertSame(auth, inner.lastAuth)
    }

    @Test
    fun `fails before dialing when no container is selected`() = runTest {
        val inner = RecordingTransport()
        assertFailsWith<SshConnectionException> {
            ContainerTransport(inner).connect(target.copy(container = null), SshAuth.Password("s"))
        }
        assertNull(inner.lastTarget)
    }

    @Test
    fun `fails before dialing when the container name is blank`() = runTest {
        val inner = RecordingTransport()
        assertFailsWith<SshConnectionException> {
            ContainerTransport(inner).connect(
                target.copy(container = ContainerSpec(target = "   ")),
                SshAuth.Password("s"),
            )
        }
        assertNull(inner.lastTarget)
    }

    @Test
    fun `session exposes the terminal but not host-level channels`() = runTest {
        val connection = ContainerTransport(RecordingTransport()).connect(target, SshAuth.Password("s"))
        assertTrue(connection.isConnected)
        assertEquals("aes256-gcm@openssh.com", connection.cipher)
        assertEquals("SSH-2.0-OpenSSH_9.6", connection.serverVersion)
        assertEquals(7L, connection.measureRoundTrip())
        assertFailsWith<UnsupportedOperationException> { connection.exec("ls") }
        assertFailsWith<UnsupportedOperationException> { connection.openSftp() }
        assertFailsWith<UnsupportedOperationException> {
            connection.forwardLocal(LocalForwardSpec(bindPort = 0, destHost = "h", destPort = 1))
        }
    }

    @Test
    fun `disconnect closes the underlying ssh connection`() = runTest {
        val inner = RecordingTransport()
        val connection = ContainerTransport(inner).connect(target, SshAuth.Password("s"))
        connection.disconnect()
        assertTrue(inner.lastConnection!!.disconnected)
    }
}

private class RecordingTransport : SshTransport {
    var lastTarget: SshTarget? = null
    var lastAuth: SshAuth? = null
    var lastConnection: FakeConnection? = null

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        lastTarget = target
        lastAuth = auth
        return FakeConnection().also { lastConnection = it }
    }
}

private class FakeConnection : SshConnection {
    var disconnected = false

    override val isConnected: Boolean get() = !disconnected
    override val cipher: String get() = "aes256-gcm@openssh.com"
    override val serverVersion: String get() = "SSH-2.0-OpenSSH_9.6"

    override suspend fun measureRoundTrip(): Long = 7L
    override suspend fun exec(command: String): ExecResult = ExecResult(0, "", "")
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = FakeChannel()
    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun disconnect() { disconnected = true }
}

private class FakeChannel : ShellChannel {
    override val isOpen: Boolean = true
    override val output: Flow<ByteArray> = emptyFlow()
    override suspend fun write(data: ByteArray) = Unit
    override suspend fun resize(size: PtySize) = Unit
    override suspend fun close() = Unit
}
