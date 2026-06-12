package app.skerry.ui.connection

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshAuthenticationException
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionControllerTest {

    private val target = SshTarget(host = "h", port = 22, username = "u")

    private fun TestScope.controllerWith(transport: SshTransport): Pair<ConnectionController, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = ConnectionController(
            transport = transport,
            scope = scope,
            newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
        )
        return controller to scope
    }

    @Test
    fun `starts in Form state`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)
        scope.cancel()
    }

    @Test
    fun `connect transitions to Connected and streams shell output`() = runTest {
        val channel = FakeShellChannel()
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(channel)))

        controller.connect(target, SshAuth.Password("pw"))

        val state = controller.uiState
        assertIs<ConnectionUiState.Connected>(state)
        channel.emit("hi".encodeToByteArray())
        assertEquals("hi", state.terminal.output)
        scope.cancel()
    }

    @Test
    fun `connect failure transitions to Error with message`() = runTest {
        val transport = FakeSshTransport(error = SshAuthenticationException("нет доступа"))
        val (controller, scope) = controllerWith(transport)

        controller.connect(target, SshAuth.Password("pw"))

        val state = controller.uiState
        assertIs<ConnectionUiState.Error>(state)
        assertEquals("нет доступа", state.message)
        scope.cancel()
    }

    @Test
    fun `connect shows Connecting while in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel()), gate = gate))

        controller.connect(target, SshAuth.Password("pw"))
        assertEquals(ConnectionUiState.Connecting, controller.uiState)

        gate.complete(Unit)
        assertIs<ConnectionUiState.Connected>(controller.uiState)
        scope.cancel()
    }

    @Test
    fun `disconnect returns to Form and disconnects connection`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        controller.disconnect()

        assertEquals(ConnectionUiState.Form, controller.uiState)
        assertTrue(conn.disconnected)
        scope.cancel()
    }

    @Test
    fun `connect is a no-op while already connected`() = runTest {
        val firstChannel = FakeShellChannel()
        val conn = CountingSshConnection(firstChannel)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))

        controller.connect(target, SshAuth.Password("pw"))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        // Повторный connect из Connected не должен открывать второй shell/сессию.
        controller.connect(target, SshAuth.Password("pw"))

        assertEquals(connected, controller.uiState)
        assertEquals(1, conn.openShellCalls)
        scope.cancel()
    }

    @Test
    fun `dismissError returns to Form`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(error = SshAuthenticationException("x")))
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Error>(controller.uiState)

        controller.dismissError()

        assertEquals(ConnectionUiState.Form, controller.uiState)
        scope.cancel()
    }
}

/** Фейк-транспорт: опциональная задержка через gate, либо успех/ошибка. */
private class FakeSshTransport(
    private val connection: SshConnection? = null,
    private val error: Throwable? = null,
    private val gate: CompletableDeferred<Unit>? = null,
) : SshTransport {
    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        gate?.await()
        error?.let { throw it }
        return connection!!
    }
}

private class FakeSshConnection(private val channel: ShellChannel) : SshConnection {
    var disconnected = false
        private set

    override val isConnected: Boolean get() = !disconnected
    override suspend fun exec(command: String): ExecResult = throw UnsupportedOperationException()
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = channel
    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()
    override suspend fun disconnect() {
        disconnected = true
    }
}

/** Считает вызовы openShell — для проверки, что повторный connect не открывает второй shell. */
private class CountingSshConnection(private val channel: ShellChannel) : SshConnection {
    var openShellCalls = 0
        private set

    override val isConnected: Boolean = true
    override suspend fun exec(command: String): ExecResult = throw UnsupportedOperationException()
    override suspend fun openShell(size: PtySize, term: String): ShellChannel {
        openShellCalls++
        return channel
    }

    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()

    override suspend fun disconnect() {}
}

private class FakeShellChannel : ShellChannel {
    private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
    override val isOpen: Boolean = true
    override val output: Flow<ByteArray> = flow { for (chunk in emissions) emit(chunk) }

    suspend fun emit(chunk: ByteArray) {
        emissions.send(chunk)
    }

    override suspend fun write(data: ByteArray) {}
    override suspend fun resize(size: PtySize) {}
    override suspend fun close() {
        emissions.close()
    }
}
