package app.skerry.ui.connection

import app.skerry.shared.container.ContainerRuntime
import app.skerry.shared.container.ContainerSpec
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
import app.skerry.shared.ssh.SshAuthenticationException
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshHostKeyRejectedException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Browse containers" in the connection form: a one-shot probe that runs the runtime's list command
 * on the host and parses it. Same shape as [runConnectionTest] — the temporary connection is always
 * closed, and transport failures are reported as typed problems rather than raw text.
 */
class ContainerBrowseTest {

    private val target = SshTarget(
        host = "docker-host", port = 22, username = "ops",
        connectionType = ConnectionType.CONTAINER,
        container = ContainerSpec(runtime = ContainerRuntime.DOCKER, target = "old-choice"),
    )
    private val auth = SshAuth.Password("s")

    @Test
    fun lists_docker_containers_over_a_plain_ssh_probe() = runTest {
        val transport = FakeTransport(FakeConnection(stdout = "abc123\tweb\tnginx\tUp 2 hours"))
        val result = listContainers(transport, target, auth, ContainerSpec(runtime = ContainerRuntime.DOCKER))
        assertTrue(result is ContainerBrowseStatus.Loaded)
        assertEquals(listOf("web"), result.entries.map { it.name })
        // The probe needs a host-level exec channel, which a container session refuses — so it must
        // dial as plain SSH with no exec command of its own.
        assertEquals(ConnectionType.SSH, transport.lastTarget?.connectionType)
        assertNull(transport.lastTarget?.container)
        assertNull(transport.lastTarget?.shellCommand)
        assertEquals("docker ps --format '{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.Status}}'", transport.connection.lastCommand)
    }

    @Test
    fun lists_pods_in_the_selected_namespace() = runTest {
        val transport = FakeTransport(FakeConnection(stdout = "api-0   Running   app,sidecar"))
        val result = listContainers(
            transport, target, auth,
            ContainerSpec(runtime = ContainerRuntime.KUBERNETES, namespace = "prod"),
        )
        assertTrue(result is ContainerBrowseStatus.Loaded)
        assertEquals(listOf("api-0"), result.entries.map { it.name })
        assertTrue(transport.connection.lastCommand!!.contains("-n prod"))
    }

    @Test
    fun closes_the_probe_connection() = runTest {
        val transport = FakeTransport(FakeConnection(stdout = ""))
        listContainers(transport, target, auth, ContainerSpec())
        assertTrue(transport.connection.disconnected)
    }

    @Test
    fun a_failing_command_is_reported_as_such() = runTest {
        val transport = FakeTransport(
            FakeConnection(stdout = "", stderr = "docker: command not found", exitCode = 127),
        )
        val result = listContainers(transport, target, auth, ContainerSpec())
        assertEquals(ContainerBrowseStatus.Failure(ContainerBrowseProblem.COMMAND_FAILED), result)
        assertTrue(transport.connection.disconnected)
    }

    @Test
    fun an_empty_listing_is_a_success_with_no_entries() = runTest {
        val transport = FakeTransport(FakeConnection(stdout = "\n", exitCode = 0))
        assertEquals(ContainerBrowseStatus.Loaded(emptyList()), listContainers(transport, target, auth, ContainerSpec()))
    }

    @Test
    fun transport_failures_map_to_typed_problems() = runTest {
        suspend fun problemFor(error: Exception): ContainerBrowseProblem {
            val status = listContainers(FailingTransport(error), target, auth, ContainerSpec())
            return (status as ContainerBrowseStatus.Failure).problem
        }
        assertEquals(ContainerBrowseProblem.AUTHENTICATION_FAILED, problemFor(SshAuthenticationException("no")))
        assertEquals(ContainerBrowseProblem.HOST_KEY_REJECTED, problemFor(SshHostKeyRejectedException("no")))
        assertEquals(ContainerBrowseProblem.CONNECTION_FAILED, problemFor(SshConnectionException("no")))
        assertEquals(ContainerBrowseProblem.CONNECTION_FAILED, problemFor(IllegalStateException("boom")))
    }
}

private class FakeTransport(val connection: FakeConnection) : SshTransport {
    var lastTarget: SshTarget? = null

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        lastTarget = target
        return connection
    }
}

private class FailingTransport(private val error: Exception) : SshTransport {
    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection = throw error
}

private class FakeConnection(
    private val stdout: String = "",
    private val stderr: String = "",
    private val exitCode: Int? = 0,
) : SshConnection {
    var lastCommand: String? = null
    var disconnected = false

    override val isConnected: Boolean get() = !disconnected

    override suspend fun exec(command: String): ExecResult {
        lastCommand = command
        return ExecResult(exitCode, stdout, stderr)
    }

    override suspend fun openShell(size: PtySize, term: String): ShellChannel = throw UnsupportedOperationException()
    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun disconnect() { disconnected = true }
}
