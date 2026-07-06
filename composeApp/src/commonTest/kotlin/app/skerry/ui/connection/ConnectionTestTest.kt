package app.skerry.ui.connection

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
import app.skerry.shared.sftp.SftpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionTestTest {

    private val target = SshTarget(host = "h", port = 22, username = "u")
    private val auth = SshAuth.Password("pw")

    @Test
    fun `success reports round trip and always disconnects`() = runTest {
        val conn = ProbeConnection(roundTrip = 12L)
        val status = runConnectionTest(ProbeTransport(conn), target, auth)
        assertEquals(ConnectionTestStatus.Success(12L), status)
        assertTrue(conn.disconnected) // the temporary connection is closed
    }

    @Test
    fun `success with null round trip still succeeds`() = runTest {
        val conn = ProbeConnection(roundTrip = null)
        assertEquals(ConnectionTestStatus.Success(null), runConnectionTest(ProbeTransport(conn), target, auth))
        assertTrue(conn.disconnected)
    }

    @Test
    fun `disconnects even when round trip probe throws`() = runTest {
        val conn = ProbeConnection(roundTripError = IllegalStateException("boom"))
        val status = runConnectionTest(ProbeTransport(conn), target, auth)
        assertEquals(ConnectionTestStatus.Success(null), status) // a ping failure isn't a test failure
        assertTrue(conn.disconnected)
    }

    @Test
    fun `auth failure maps to friendly message`() = runTest {
        val status = runConnectionTest(ProbeTransport(error = SshAuthenticationException("denied")), target, auth)
        assertEquals(ConnectionTestStatus.Failure("Authentication failed"), status)
    }

    @Test
    fun `host key rejection maps to friendly message`() = runTest {
        val status = runConnectionTest(ProbeTransport(error = SshHostKeyRejectedException("bad key")), target, auth)
        assertEquals(ConnectionTestStatus.Failure("Host key rejected"), status)
    }

    @Test
    fun `connection error maps to generic message without leaking transport detail`() = runTest {
        // Raw exception text (address/library internals) is never surfaced in the UI — generic only.
        val status = runConnectionTest(ProbeTransport(error = SshConnectionException("no route to 10.0.0.1:22")), target, auth)
        assertEquals(ConnectionTestStatus.Failure("Connection failed"), status)
    }

    @Test
    fun `unexpected error maps to generic message`() = runTest {
        val status = runConnectionTest(ProbeTransport(error = IllegalStateException("library internals")), target, auth)
        assertEquals(ConnectionTestStatus.Failure("Connection failed"), status)
    }

    // Controller: status transitions

    @Test
    fun `controller goes Checking then Success`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val controller = ConnectionTestController(ProbeTransport(ProbeConnection(roundTrip = 5L), gate = gate), scope)
        assertEquals(ConnectionTestStatus.Idle, controller.status)

        controller.test(target, auth)
        assertEquals(ConnectionTestStatus.Checking, controller.status)

        gate.complete(Unit)
        assertEquals(ConnectionTestStatus.Success(5L), controller.status)
        scope.cancel()
    }

    @Test
    fun `controller reset returns to Idle`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = ConnectionTestController(ProbeTransport(ProbeConnection()), scope)
        controller.test(target, auth)
        assertIs<ConnectionTestStatus.Success>(controller.status)

        controller.reset()
        assertEquals(ConnectionTestStatus.Idle, controller.status)
        scope.cancel()
    }
}

private class ProbeTransport(
    private val connection: ProbeConnection? = null,
    private val error: Throwable? = null,
    private val gate: CompletableDeferred<Unit>? = null,
) : SshTransport {
    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        gate?.await()
        error?.let { throw it }
        return connection!!
    }
}

private class ProbeConnection(
    private val roundTrip: Long? = null,
    private val roundTripError: Throwable? = null,
) : SshConnection {
    var disconnected = false
        private set

    override val isConnected: Boolean get() = !disconnected
    override suspend fun measureRoundTrip(): Long? {
        roundTripError?.let { throw it }
        return roundTrip
    }
    override suspend fun exec(command: String): ExecResult = throw UnsupportedOperationException()
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = throw UnsupportedOperationException()
    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun disconnect() {
        disconnected = true
    }
}
