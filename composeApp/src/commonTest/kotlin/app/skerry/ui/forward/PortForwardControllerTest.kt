package app.skerry.ui.forward

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PortForwardException
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PortForwardControllerTest {

    private fun controllerWith(connection: SshConnection): Pair<PortForwardController, CoroutineScope> {
        val scope = TestScope(UnconfinedTestDispatcher())
        return PortForwardController(connection, scope) to scope
    }

    @Test
    fun `addLocal raises a forward and reports the bound port`() = runTest {
        val conn = FakeForwardConnection(localPort = 50001)
        val (controller, _) = controllerWith(conn)

        controller.addLocal(bindPort = 0, destHost = "10.0.0.5", destPort = 80)

        assertEquals(1, controller.forwards.size)
        val entry = controller.forwards.single()
        assertEquals(ForwardDirection.Local, entry.direction)
        assertEquals(ForwardStatus.Active(50001), entry.status)
        assertEquals(
            LocalForwardSpec(bindHost = "127.0.0.1", bindPort = 0, destHost = "10.0.0.5", destPort = 80),
            conn.lastLocalSpec,
        )
    }

    @Test
    fun `addRemote raises a reverse forward`() = runTest {
        val conn = FakeForwardConnection(remotePort = 8080)
        val (controller, _) = controllerWith(conn)

        controller.addRemote(bindPort = 0, destHost = "127.0.0.1", destPort = 3000)

        val entry = controller.forwards.single()
        assertEquals(ForwardDirection.Remote, entry.direction)
        assertEquals(ForwardStatus.Active(8080), entry.status)
        assertEquals(
            RemoteForwardSpec(bindHost = "127.0.0.1", bindPort = 0, destHost = "127.0.0.1", destPort = 3000),
            conn.lastRemoteSpec,
        )
    }

    @Test
    fun `addDynamic raises a SOCKS forward carrying only listener params`() = runTest {
        val conn = FakeForwardConnection(dynamicPort = 1080)
        val (controller, _) = controllerWith(conn)

        controller.addDynamic(bindPort = 0)

        val entry = controller.forwards.single()
        assertEquals(ForwardDirection.Dynamic, entry.direction)
        assertEquals(ForwardStatus.Active(1080), entry.status)
        assertEquals(DynamicForwardSpec(bindHost = "127.0.0.1", bindPort = 0), conn.lastDynamicSpec)
    }

    @Test
    fun `a failed forward becomes Failed without dropping the row`() = runTest {
        val conn = FakeForwardConnection(localError = PortForwardException("порт занят"))
        val (controller, _) = controllerWith(conn)

        controller.addLocal(bindPort = 22, destHost = "10.0.0.5", destPort = 22)

        val entry = controller.forwards.single()
        val status = assertIs<ForwardStatus.Failed>(entry.status)
        assertEquals("порт занят", status.message)
    }

    @Test
    fun `remove closes the underlying forward and drops the row`() = runTest {
        val conn = FakeForwardConnection(localPort = 50002)
        val (controller, _) = controllerWith(conn)
        controller.addLocal(bindPort = 0, destHost = "10.0.0.5", destPort = 80)
        val entry = controller.forwards.single()

        controller.remove(entry)

        assertTrue(controller.forwards.isEmpty())
        assertEquals(1, conn.lastForward!!.closeCount)
    }

    @Test
    fun `closeAll clears the list and closes every forward`() = runTest {
        val conn = FakeForwardConnection(localPort = 50003)
        val (controller, _) = controllerWith(conn)
        controller.addLocal(bindPort = 0, destHost = "a", destPort = 1)
        val first = conn.lastForward!!
        controller.addLocal(bindPort = 0, destHost = "b", destPort = 2)
        val second = conn.lastForward!!

        controller.closeAll()

        assertTrue(controller.forwards.isEmpty())
        assertEquals(1, first.closeCount)
        assertEquals(1, second.closeCount)
    }
}

/** Фейк-соединение: отдаёт настроенный порт/ошибку и запоминает последние spec и проброс. */
private class FakeForwardConnection(
    private val localPort: Int = 0,
    private val remotePort: Int = 0,
    private val dynamicPort: Int = 0,
    private val localError: PortForwardException? = null,
    private val remoteError: PortForwardException? = null,
    private val dynamicError: PortForwardException? = null,
) : SshConnection {
    var lastLocalSpec: LocalForwardSpec? = null
        private set
    var lastRemoteSpec: RemoteForwardSpec? = null
        private set
    var lastDynamicSpec: DynamicForwardSpec? = null
        private set
    var lastForward: FakePortForward? = null
        private set

    override val isConnected: Boolean = true
    override suspend fun exec(command: String): ExecResult = throw UnsupportedOperationException()
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = throw UnsupportedOperationException()
    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()

    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward {
        lastLocalSpec = spec
        localError?.let { throw it }
        return FakePortForward(localPort).also { lastForward = it }
    }

    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward {
        lastRemoteSpec = spec
        remoteError?.let { throw it }
        return FakePortForward(remotePort).also { lastForward = it }
    }

    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward {
        lastDynamicSpec = spec
        dynamicError?.let { throw it }
        return FakePortForward(dynamicPort).also { lastForward = it }
    }

    override suspend fun disconnect() {}
}

private class FakePortForward(override val boundPort: Int) : PortForward {
    var closeCount = 0
        private set
    override val isActive: Boolean get() = closeCount == 0
    override suspend fun close() {
        closeCount++
    }
}
