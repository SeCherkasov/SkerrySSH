package app.skerry.ui.tunnel

import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ptail_err_host_not_trusted
import org.jetbrains.compose.resources.getString
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PortForwardException
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshHostKeyRejectedException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.shared.tunnel.TunnelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelManagerTest {

    private val target = SshTarget(host = "h", port = 22, username = "u")
    private val auth = SshAuth.Password("pw")

    private fun managerWith(
        transport: SshTransport,
        store: TunnelStore = FakeTunnelStore(),
        resolve: (Tunnel) -> TunnelResolution = { TunnelResolution.Ready(target, auth) },
        ids: List<String> = List(20) { "id-$it" },
    ): TunnelManager {
        val scope = TestScope(UnconfinedTestDispatcher())
        val it = ids.iterator()
        return TunnelManager(store, transport, resolve, scope) { it.next() }
    }

    private fun localDraft(label: String = "web", hostId: String = "h1") = TunnelDraft(
        label = label, hostId = hostId, direction = TunnelDirection.Local,
        bindHost = "127.0.0.1", bindPort = 8080, destHost = "10.0.0.5", destPort = 80,
    )

    @Test
    fun `save persists a new tunnel and lists it inactive`() = runTest {
        val store = FakeTunnelStore()
        val manager = managerWith(FakeTunnelTransport(), store)

        val id = manager.save(localDraft())

        assertEquals("id-0", id)
        val entry = manager.tunnels.single()
        assertEquals("web", entry.tunnel.label)
        assertEquals(TunnelStatus.Inactive, entry.status)
        assertEquals(listOf(id), store.all().map { it.id }) // reached the store
    }

    @Test
    fun `save with existing id updates config in place`() = runTest {
        val manager = managerWith(FakeTunnelTransport())
        val id = manager.save(localDraft(label = "old"))

        manager.save(localDraft(label = "renamed").copy(id = id))

        val entry = manager.tunnels.single()
        assertEquals("renamed", entry.tunnel.label)
    }

    @Test
    fun `delete removes the tunnel from store and list`() = runTest {
        val store = FakeTunnelStore()
        val manager = managerWith(FakeTunnelTransport(), store)
        val id = manager.save(localDraft())

        manager.delete(id)

        assertTrue(manager.tunnels.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `activate opens a connection and raises a local forward`() = runTest {
        val transport = FakeTunnelTransport(FakeTunnelConnection(localPort = 50001))
        val manager = managerWith(transport)
        val id = manager.save(localDraft())

        manager.activate(id)

        val entry = manager.tunnels.single()
        assertEquals(TunnelStatus.Active(50001), entry.status)
        assertEquals(target, transport.lastTarget)
        assertEquals(auth, transport.lastAuth)
        assertEquals(
            LocalForwardSpec(bindHost = "127.0.0.1", bindPort = 8080, destHost = "10.0.0.5", destPort = 80),
            transport.connection.lastLocalSpec,
        )
    }

    @Test
    fun `activate dynamic raises a SOCKS forward`() = runTest {
        val transport = FakeTunnelTransport(FakeTunnelConnection(dynamicPort = 1080))
        val manager = managerWith(transport)
        val id = manager.save(
            TunnelDraft(label = "socks", hostId = "h1", direction = TunnelDirection.Dynamic, bindPort = 1080),
        )

        manager.activate(id)

        val entry = manager.tunnels.single()
        assertEquals(TunnelStatus.Active(1080), entry.status)
        assertEquals(DynamicForwardSpec(bindHost = "127.0.0.1", bindPort = 1080), transport.connection.lastDynamicSpec)
    }

    @Test
    fun `activate fails fast when resolution is unavailable and never connects`() = runTest {
        val transport = FakeTunnelTransport()
        val manager = managerWith(transport, resolve = { TunnelResolution.Unavailable("No saved credential") })
        val id = manager.save(localDraft())

        manager.activate(id)

        val status = assertIs<TunnelStatus.Failed>(manager.tunnels.single().status)
        assertEquals("No saved credential", status.message)
        assertNull(transport.lastTarget) // transport untouched
    }

    @Test
    fun `activate maps a rejected host key to a clear message and closes the connection`() = runTest {
        val conn = FakeTunnelConnection(localError = SshHostKeyRejectedException("bad"))
        val transport = FakeTunnelTransport(conn)
        val manager = managerWith(transport)
        val id = manager.save(localDraft())

        manager.activate(id)

        val status = assertIs<TunnelStatus.Failed>(manager.tunnels.single().status)
        // Message is localized (strings_ptail); compare against the resource itself so the test
        // doesn't depend on the machine locale.
        assertEquals(getString(Res.string.ptail_err_host_not_trusted), status.message)
        assertTrue(conn.disconnected) // connection not leaked
    }

    @Test
    fun `deactivate closes the forward and the connection`() = runTest {
        val conn = FakeTunnelConnection(localPort = 50002)
        val transport = FakeTunnelTransport(conn)
        val manager = managerWith(transport)
        val id = manager.save(localDraft())
        manager.activate(id)

        manager.deactivate(id)

        assertEquals(TunnelStatus.Inactive, manager.tunnels.single().status)
        assertEquals(1, conn.lastForward!!.closeCount)
        assertTrue(conn.disconnected)
    }

    @Test
    fun `telemetry poll snapshots bytes and computes per-second rate`() = runTest {
        val conn = FakeTunnelConnection(localPort = 50003)
        val manager = managerWith(FakeTunnelTransport(conn))
        val id = manager.save(localDraft())
        manager.activate(id)
        val entry = manager.tunnels.single()
        val handle = conn.lastForward!!

        handle.bytesUp = 5000
        handle.bytesDown = 200
        manager.pollTelemetry()

        assertEquals(5000, entry.bytesUp)
        assertEquals(200, entry.bytesDown)
        assertEquals(5000, entry.upRate)
        assertEquals(200, entry.downRate)
    }

    @Test
    fun `deactivate while connecting cancels the raise and never leaks the connection`() = runTest {
        // Gate in forwardLocal: connect already returned a live connection, the forward is still
        // being raised — this is the leak window (connection open, not yet recorded in entry.connection).
        val gate = CompletableDeferred<Unit>()
        val conn = FakeTunnelConnection(localPort = 50010, raiseGate = gate)
        val transport = FakeTunnelTransport(conn)
        val manager = managerWith(transport)
        val id = manager.save(localDraft())

        manager.activate(id)
        assertEquals(TunnelStatus.Connecting, manager.tunnels.single().status)

        // Deactivate while the forward is stuck on the gate, then release the gate.
        manager.deactivate(id)
        gate.complete(Unit)

        assertEquals(TunnelStatus.Inactive, manager.tunnels.single().status)
        assertTrue(conn.disconnected) // the opened connection was closed, not left as an orphan
    }

    @Test
    fun `closeAll deactivates every active tunnel`() = runTest {
        val transport = FakeTunnelTransport(FakeTunnelConnection(localPort = 50020))
        val manager = managerWith(transport)
        val a = manager.save(localDraft(label = "a"))
        val b = manager.save(localDraft(label = "b"))
        manager.activate(a)
        manager.activate(b)

        manager.closeAll()

        assertTrue(manager.tunnels.all { it.status == TunnelStatus.Inactive })
    }

    @Test
    fun `activate after a failure resets the status to connecting then active`() = runTest {
        // First resolution is unavailable, second is ready — status must be able to leave Failed.
        var available = false
        val transport = FakeTunnelTransport(FakeTunnelConnection(localPort = 50030))
        val manager = managerWith(transport, resolve = {
            if (available) TunnelResolution.Ready(target, auth) else TunnelResolution.Unavailable("No saved credential")
        })
        val id = manager.save(localDraft())

        manager.activate(id)
        assertIs<TunnelStatus.Failed>(manager.tunnels.single().status)

        available = true
        manager.activate(id)
        assertEquals(TunnelStatus.Active(50030), manager.tunnels.single().status)
    }

    @Test
    fun `loads previously saved tunnels on construction`() = runTest {
        val store = FakeTunnelStore()
        store.put(Tunnel("x", "saved", "h1", TunnelDirection.Local, "127.0.0.1", 22, "a", 1))
        val manager = managerWith(FakeTunnelTransport(), store)

        assertEquals(listOf("saved"), manager.tunnels.map { it.tunnel.label })
        assertEquals(TunnelStatus.Inactive, manager.tunnels.single().status)
    }
}

private class FakeTunnelStore : TunnelStore {
    private val entries = mutableListOf<Tunnel>()
    override fun all(): List<Tunnel> = entries.toList()
    override fun put(tunnel: Tunnel) {
        val i = entries.indexOfFirst { it.id == tunnel.id }
        if (i >= 0) entries[i] = tunnel else entries += tunnel
    }
    override fun remove(id: String) {
        entries.removeAll { it.id == id }
    }
}

private class FakeTunnelTransport(
    val connection: FakeTunnelConnection = FakeTunnelConnection(),
    private val connectError: Throwable? = null,
) : SshTransport {
    var lastTarget: SshTarget? = null
        private set
    var lastAuth: SshAuth? = null
        private set

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        lastTarget = target
        lastAuth = auth
        connectError?.let { throw it }
        return connection
    }
}

private class FakeTunnelConnection(
    private val localPort: Int = 0,
    private val remotePort: Int = 0,
    private val dynamicPort: Int = 0,
    private val localError: Throwable? = null,
    private val raiseGate: CompletableDeferred<Unit>? = null,
) : SshConnection {
    var lastLocalSpec: LocalForwardSpec? = null
        private set
    var lastRemoteSpec: RemoteForwardSpec? = null
        private set
    var lastDynamicSpec: DynamicForwardSpec? = null
        private set
    var lastForward: FakeTunnelForward? = null
        private set
    var disconnected = false
        private set

    override val isConnected: Boolean get() = !disconnected
    override suspend fun exec(command: String): ExecResult = throw UnsupportedOperationException()
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = throw UnsupportedOperationException()
    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()

    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward {
        lastLocalSpec = spec
        raiseGate?.await()
        localError?.let { throw it }
        return FakeTunnelForward(localPort).also { lastForward = it }
    }

    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward {
        lastRemoteSpec = spec
        return FakeTunnelForward(remotePort).also { lastForward = it }
    }

    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward {
        lastDynamicSpec = spec
        return FakeTunnelForward(dynamicPort).also { lastForward = it }
    }

    override suspend fun disconnect() {
        disconnected = true
    }
}

private class FakeTunnelForward(
    override val boundPort: Int,
    override var bytesUp: Long = 0,
    override var bytesDown: Long = 0,
) : PortForward {
    var closeCount = 0
        private set
    override var isPaused: Boolean = false
        private set
    override val isActive: Boolean get() = closeCount == 0
    override suspend fun pause() { isPaused = true }
    override suspend fun resume() { isPaused = false }
    override suspend fun close() { closeCount++ }
}
