package app.skerry.ui.tunnel

import app.skerry.shared.sftp.SftpClient
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
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ptail_err_auth_failed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceScanControllerTest {

    private val target = SshTarget(host = "h", port = 22, username = "u")
    private val auth = SshAuth.Password("pw")

    private fun controllerWith(
        transport: SshTransport,
        resolve: (String) -> TunnelResolution = { TunnelResolution.Ready(target, auth) },
    ) = ServiceScanController(transport, resolve, TestScope(UnconfinedTestDispatcher()))

    @Test
    fun `scan lists the listening services of the host`() = runTest {
        val conn = FakeScanConnection(stdout = "LISTEN 0 128 0.0.0.0:22 0.0.0.0:*\nLISTEN 0 128 127.0.0.1:5432 0.0.0.0:*")
        val controller = controllerWith(FakeScanTransport(conn))

        controller.scan("h1")

        val ready = assertIs<ServiceScanState.Ready>(controller.state)
        assertEquals(listOf(22, 5432), ready.services.map { it.port })
        assertEquals("h1", controller.scannedHostId)
    }

    @Test
    fun `scan closes its connection after one exec round-trip`() = runTest {
        val conn = FakeScanConnection(stdout = "LISTEN 0 128 0.0.0.0:22 0.0.0.0:*")
        val controller = controllerWith(FakeScanTransport(conn))

        controller.scan("h1")

        assertEquals(1, conn.execCalls) // one command, one round-trip
        assertTrue(conn.disconnected) // the scan owns the connection and gives it back
    }

    @Test
    fun `a host that answers nothing reports an empty result, not a failure`() = runTest {
        val controller = controllerWith(FakeScanTransport(FakeScanConnection(stdout = "")))

        controller.scan("h1")

        assertEquals(emptyList(), assertIs<ServiceScanState.Ready>(controller.state).services)
    }

    @Test
    fun `a host where every branch of the command failed is unsupported, not empty`() = runTest {
        // BSD netstat rejects the Linux flags outright: no output and a non-zero exit. Claiming
        // "nothing is listening" there would be a lie.
        val conn = FakeScanConnection(stdout = "", exitCode = 1)
        val controller = controllerWith(FakeScanTransport(conn))

        controller.scan("h1")

        assertEquals(ServiceScanState.Unsupported, controller.state)
    }

    @Test
    fun `a non-zero exit is ignored when the host still listed services`() = runTest {
        // The chain ends on a branch that failed after an earlier one printed: the output is what
        // matters, not the exit code of the last attempt.
        val conn = FakeScanConnection(stdout = "LISTEN 0 128 0.0.0.0:22 0.0.0.0:*", exitCode = 1)
        val controller = controllerWith(FakeScanTransport(conn))

        controller.scan("h1")

        assertEquals(listOf(22), assertIs<ServiceScanState.Ready>(controller.state).services.map { it.port })
    }

    @Test
    fun `a transport without a command channel is reported as unsupported`() = runTest {
        // telnet/serial: exec can never answer, so this is a verdict, not a retryable failure.
        val conn = FakeScanConnection(execError = UnsupportedOperationException("telnet has no exec"))
        val controller = controllerWith(FakeScanTransport(conn))

        controller.scan("h1")

        assertEquals(ServiceScanState.Unsupported, controller.state)
        assertTrue(conn.disconnected)
    }

    @Test
    fun `an unresolvable host fails with a typed reason and never connects`() = runTest {
        val transport = FakeScanTransport(FakeScanConnection())
        val controller = controllerWith(transport, resolve = { TunnelResolution.Unavailable(TunnelUnavailable.NoCredential) })

        controller.scan("h1")

        val failed = assertIs<ServiceScanState.Failed>(controller.state)
        assertEquals(TunnelUnavailable.NoCredential, failed.reason)
        assertEquals(0, transport.connects)
    }

    @Test
    fun `a rejected login is reported with a friendly message`() = runTest {
        val transport = FakeScanTransport(FakeScanConnection(), connectError = SshAuthenticationException("nope"))
        val controller = controllerWith(transport)

        controller.scan("h1")

        val failed = assertIs<ServiceScanState.Failed>(controller.state)
        // Localized from the resource, so the test doesn't depend on the machine locale.
        assertEquals(getString(Res.string.ptail_err_auth_failed), failed.message)
    }

    @Test
    fun `a second scan supersedes the first and never leaks its connection`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = FakeScanConnection(stdout = "LISTEN 0 128 0.0.0.0:22 0.0.0.0:*", execGate = gate)
        val transport = FakeScanTransport(slow)
        val controller = controllerWith(transport)

        controller.scan("h1")
        assertEquals(ServiceScanState.Scanning, controller.state)

        val fresh = FakeScanConnection(stdout = "LISTEN 0 128 0.0.0.0:80 0.0.0.0:*")
        transport.connection = fresh
        controller.scan("h2")
        gate.complete(Unit) // the superseded scan wakes up after the new one already finished

        assertEquals(listOf(80), assertIs<ServiceScanState.Ready>(controller.state).services.map { it.port })
        assertTrue(slow.disconnected) // cancelled scan closed its own connection
    }

    @Test
    fun `reset cancels an in-flight scan and clears the result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val conn = FakeScanConnection(stdout = "LISTEN 0 128 0.0.0.0:22 0.0.0.0:*", execGate = gate)
        val controller = controllerWith(FakeScanTransport(conn))
        controller.scan("h1")

        controller.reset()
        gate.complete(Unit)

        assertEquals(ServiceScanState.Idle, controller.state)
        assertTrue(conn.disconnected)
    }
}

private class FakeScanTransport(
    var connection: FakeScanConnection,
    private val connectError: Throwable? = null,
) : SshTransport {
    var connects = 0
        private set

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        connects++
        connectError?.let { throw it }
        return connection
    }
}

private class FakeScanConnection(
    private val stdout: String = "",
    private val exitCode: Int = 0,
    private val execError: Throwable? = null,
    private val execGate: CompletableDeferred<Unit>? = null,
) : SshConnection {
    var execCalls = 0
        private set
    var disconnected = false
        private set

    override val isConnected: Boolean get() = !disconnected

    override suspend fun exec(command: String): ExecResult {
        execCalls++
        execGate?.await()
        execError?.let { throw it }
        return ExecResult(exitCode, stdout, "")
    }

    override suspend fun openShell(size: PtySize, term: String): ShellChannel = throw UnsupportedOperationException()
    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = throw UnsupportedOperationException()

    override suspend fun disconnect() {
        disconnected = true
    }
}
