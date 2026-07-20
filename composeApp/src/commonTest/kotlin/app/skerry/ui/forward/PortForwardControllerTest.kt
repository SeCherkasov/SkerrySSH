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
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val conn = FakeForwardConnection(localError = PortForwardException("port busy"))
        val (controller, _) = controllerWith(conn)

        controller.addLocal(bindPort = 22, destHost = "10.0.0.5", destPort = 22)

        val entry = controller.forwards.single()
        val status = assertIs<ForwardStatus.Failed>(entry.status)
        assertEquals("port busy", status.message)
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
    fun `pause pauses the underlying forward and flags the entry`() = runTest {
        val conn = FakeForwardConnection(localPort = 50011)
        val (controller, _) = controllerWith(conn)
        controller.addLocal(bindPort = 0, destHost = "a", destPort = 1)
        val entry = controller.forwards.single()
        val handle = conn.lastForward!!

        controller.pause(entry)

        assertTrue(entry.paused)
        assertEquals(1, handle.pauseCount)
        assertTrue(handle.isPaused)

        controller.resume(entry)

        assertEquals(false, entry.paused)
        assertEquals(1, handle.resumeCount)
        assertEquals(false, handle.isPaused)
    }

    @Test
    fun `pause is a no-op while the forward is still starting`() = runTest {
        // Uses the Failed path (via an error) to verify pause is a no-op on a non-Active row.
        val conn = FakeForwardConnection(localError = PortForwardException("not up yet"))
        val (controller, _) = controllerWith(conn)
        controller.addLocal(bindPort = 0, destHost = "a", destPort = 1)
        val entry = controller.forwards.single()

        controller.pause(entry)

        assertEquals(false, entry.paused)
    }

    @Test
    fun `telemetry poll snapshots bytes and computes per-second rate`() = runTest {
        val conn = FakeForwardConnection(localPort = 50012)
        val (controller, _) = controllerWith(conn)
        controller.addLocal(bindPort = 0, destHost = "a", destPort = 1)
        val entry = controller.forwards.single()
        val handle = conn.lastForward!!

        handle.bytesUp = 5000
        handle.bytesDown = 200
        controller.pollTelemetry()

        assertEquals(5000, entry.bytesUp)
        assertEquals(200, entry.bytesDown)
        assertEquals(5000, entry.upRate) // 5000 bytes over a 1000ms interval = 5000 bytes/sec
        assertEquals(200, entry.downRate)

        // Second poll computes the rate from the delta to the previous snapshot.
        handle.bytesUp = 5500
        controller.pollTelemetry()

        assertEquals(5500, entry.bytesUp)
        assertEquals(500, entry.upRate)
        assertEquals(0, entry.downRate)
    }

    @Test
    fun `stop cancels the telemetry poller`() {
        val job = Job()
        val scope = CoroutineScope(UnconfinedTestDispatcher() + job)
        val controller = PortForwardController(FakeForwardConnection(localPort = 50004), scope)

        controller.stop()

        val leaked = job.children.any { it.isActive }
        job.cancel()
        assertFalse(leaked)
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

/** Fake connection: returns a configured port/error and records the last spec and forward. */
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

private class FakePortForward(
    override val boundPort: Int,
    override var bytesUp: Long = 0,
    override var bytesDown: Long = 0,
) : PortForward {
    var closeCount = 0
        private set
    var pauseCount = 0
        private set
    var resumeCount = 0
        private set
    override var isPaused: Boolean = false
        private set
    override val isActive: Boolean get() = closeCount == 0
    override suspend fun pause() {
        isPaused = true
        pauseCount++
    }
    override suspend fun resume() {
        isPaused = false
        resumeCount++
    }
    override suspend fun close() {
        closeCount++
    }
}
