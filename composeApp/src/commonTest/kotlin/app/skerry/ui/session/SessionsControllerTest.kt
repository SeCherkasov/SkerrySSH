package app.skerry.ui.session

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsControllerTest {

    private val target = SshTarget(host = "h", port = 22, username = "u")
    private val auth = SshAuth.Password("pw")

    private fun TestScope.sessionsWith(transport: SshTransport): Pair<SessionsController, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var n = 0
        val controller = SessionsController(
            newId = { "s${n++}" },
            controllerFactory = {
                ConnectionController(
                    transport = transport,
                    scope = scope,
                    newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
                )
            },
        )
        return controller to scope
    }

    private fun SessionsController.open(hostId: String?, title: String = hostId ?: "") =
        open(hostId = hostId, title = title, subtitle = "u@h:22", target = target, auth = auth)

    @Test
    fun `starts empty with no active session`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        assertTrue(sessions.sessions.isEmpty())
        assertNull(sessions.activeId)
        assertNull(sessions.active)
        scope.cancel()
    }

    @Test
    fun `open adds a session, makes it active and connects`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())

        val id = sessions.open(hostId = "host-a")

        assertEquals(1, sessions.sessions.size)
        assertEquals(id, sessions.activeId)
        assertIs<ConnectionUiState.Connected>(sessions.active!!.controller.uiState)
        scope.cancel()
    }

    @Test
    fun `opening a second session keeps order and activates the new one`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())

        val first = sessions.open(hostId = "host-a")
        val second = sessions.open(hostId = "host-b")

        assertEquals(listOf(first, second), sessions.sessions.map { it.id })
        assertEquals(second, sessions.activeId)
        scope.cancel()
    }

    @Test
    fun `activate switches the active session`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val first = sessions.open(hostId = "host-a")
        sessions.open(hostId = "host-b")

        sessions.activate(first)

        assertEquals(first, sessions.activeId)
        scope.cancel()
    }

    @Test
    fun `activate ignores an unknown id`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val first = sessions.open(hostId = "host-a")

        sessions.activate("does-not-exist")

        assertEquals(first, sessions.activeId)
        scope.cancel()
    }

    @Test
    fun `closing the active middle session activates the next sibling and disconnects it`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        val a = sessions.open(hostId = "host-a")
        val b = sessions.open(hostId = "host-b")
        val c = sessions.open(hostId = "host-c")
        sessions.activate(b)
        val bConn = transport.connections[1]

        sessions.close(b)

        assertEquals(listOf(a, c), sessions.sessions.map { it.id })
        assertEquals(c, sessions.activeId) // next sibling
        assertTrue(bConn.disconnected)
        scope.cancel()
    }

    @Test
    fun `closing the active last session falls back to the previous sibling`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val b = sessions.open(hostId = "host-b")
        sessions.activate(b)

        sessions.close(b)

        assertEquals(a, sessions.activeId)
        scope.cancel()
    }

    @Test
    fun `closing the only session clears the active id`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")

        sessions.close(a)

        assertTrue(sessions.sessions.isEmpty())
        assertNull(sessions.activeId)
        scope.cancel()
    }

    @Test
    fun `closing a non-active session leaves the active one untouched`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val b = sessions.open(hostId = "host-b")
        sessions.activate(a)

        sessions.close(b)

        assertEquals(a, sessions.activeId)
        assertEquals(listOf(a), sessions.sessions.map { it.id })
        scope.cancel()
    }

    @Test
    fun `statusFor reports the state of the newest session for a host, else null`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        assertNull(sessions.statusFor("host-a"))

        sessions.open(hostId = "host-a")

        assertIs<ConnectionUiState.Connected>(sessions.statusFor("host-a"))
        assertNull(sessions.statusFor("host-b"))
        scope.cancel()
    }

    // --- Split: независимая вторичная сессия внутри вкладки (модель Termius) ---

    private fun SessionsController.connectSplit(parentId: String, hostId: String?) =
        connectSplit(parentId = parentId, hostId = hostId, title = hostId ?: "", subtitle = "u@h:22", target = target, auth = auth)

    @Test
    fun `a fresh session has no split open and no secondary`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        sessions.open(hostId = "host-a")
        assertFalse(sessions.active!!.splitOpen)
        assertNull(sessions.active!!.splitSession)
        assertFalse(sessions.active!!.focusedSplit)
        scope.cancel()
    }

    @Test
    fun `toggleSplit opens then closes the split area on the active session`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        sessions.open(hostId = "host-a")

        sessions.toggleSplit()
        assertTrue(sessions.active!!.splitOpen)
        assertNull(sessions.active!!.splitSession) // пусто — покажет пикер

        sessions.toggleSplit()
        assertFalse(sessions.active!!.splitOpen)
        scope.cancel()
    }

    @Test
    fun `connectSplit attaches an independent secondary session and focuses it`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        sessions.toggleSplit()

        sessions.connectSplit(parentId = a, hostId = "host-b")

        val parent = sessions.active!!
        assertTrue(parent.splitOpen)
        val secondary = parent.splitSession!!
        assertTrue(parent.focusedSplit)
        assertEquals("host-b", secondary.hostId)
        // независимый контроллер, отдельный от основной сессии
        assertTrue(secondary.controller !== parent.controller)
        assertIs<ConnectionUiState.Connected>(secondary.controller.uiState)
        scope.cancel()
    }

    @Test
    fun `connectSplit does not add the secondary session to the tab list`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        sessions.toggleSplit()

        sessions.connectSplit(parentId = a, hostId = "host-b")

        assertEquals(listOf(a), sessions.sessions.map { it.id }) // вторичная не в баре
        scope.cancel()
    }

    @Test
    fun `closeSplit disconnects the secondary and resets split flags`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        val a = sessions.open(hostId = "host-a")
        sessions.toggleSplit()
        sessions.connectSplit(parentId = a, hostId = "host-b")
        val secondaryConn = transport.connections[1]

        sessions.closeSplit(a)

        val parent = sessions.active!!
        assertFalse(parent.splitOpen)
        assertNull(parent.splitSession)
        assertFalse(parent.focusedSplit)
        assertTrue(secondaryConn.disconnected)
        scope.cancel()
    }

    @Test
    fun `closing a tab disconnects its split session`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        val a = sessions.open(hostId = "host-a")
        sessions.toggleSplit()
        sessions.connectSplit(parentId = a, hostId = "host-b")
        val secondaryConn = transport.connections[1]

        sessions.close(a)

        assertTrue(sessions.sessions.isEmpty())
        assertTrue(secondaryConn.disconnected)
        scope.cancel()
    }

    @Test
    fun `disconnectAll disconnects split sessions too`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        val a = sessions.open(hostId = "host-a")
        sessions.toggleSplit()
        sessions.connectSplit(parentId = a, hostId = "host-b")

        sessions.disconnectAll()

        assertTrue(sessions.sessions.isEmpty())
        assertTrue(transport.connections.all { it.disconnected }) // и основная, и вторичная
        scope.cancel()
    }

    @Test
    fun `focusPane switches the focused pane`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        sessions.toggleSplit()
        sessions.connectSplit(parentId = a, hostId = "host-b")

        sessions.focusPane(a, split = false)
        assertFalse(sessions.active!!.focusedSplit)

        sessions.focusPane(a, split = true)
        assertTrue(sessions.active!!.focusedSplit)
        scope.cancel()
    }

    @Test
    fun `disconnectAll closes every session`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        sessions.open(hostId = "host-a")
        sessions.open(hostId = "host-b")

        sessions.disconnectAll()

        assertTrue(sessions.sessions.isEmpty())
        assertNull(sessions.activeId)
        assertTrue(transport.connections.all { it.disconnected })
        scope.cancel()
    }

    // --- Пустой таб без сессии + per-tab view + connect-reuse ---

    @Test
    fun `openBlank adds an active tab with no connection`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())

        val id = sessions.openBlank()

        assertEquals(1, sessions.sessions.size)
        assertEquals(id, sessions.activeId)
        val tab = sessions.active!!
        assertTrue(tab.isBlank)
        assertNull(tab.hostId)
        assertIs<ConnectionUiState.Form>(tab.controller.uiState) // соединение не стартует
        scope.cancel()
    }

    @Test
    fun `a freshly opened (connected) session is not blank`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        sessions.open(hostId = "host-a")
        assertFalse(sessions.active!!.isBlank)
        scope.cancel()
    }

    @Test
    fun `session view defaults to Terminal`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        sessions.open(hostId = "host-a")
        assertEquals(SessionView.Terminal, sessions.active!!.view)
        scope.cancel()
    }

    @Test
    fun `setActiveView changes only the active session view`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val b = sessions.open(hostId = "host-b") // активна b

        sessions.setActiveView(SessionView.Sftp)

        assertEquals(SessionView.Sftp, sessions.sessions.first { it.id == b }.view)
        assertEquals(SessionView.Terminal, sessions.sessions.first { it.id == a }.view) // соседа не трогает
        scope.cancel()
    }

    @Test
    fun `connect reuses the active blank tab in place`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val blank = sessions.openBlank()

        val id = sessions.connect(hostId = "host-a", title = "host-a", subtitle = "u@h:22", target = target, auth = auth)

        assertEquals(blank, id) // та же вкладка, новой не создалось
        assertEquals(1, sessions.sessions.size)
        val tab = sessions.active!!
        assertEquals("host-a", tab.hostId)
        assertEquals("host-a", tab.title)
        assertFalse(tab.isBlank)
        assertIs<ConnectionUiState.Connected>(tab.controller.uiState)
        scope.cancel()
    }

    @Test
    fun `connect opens a new tab when the active one is not blank`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a") // подключена, не пустая

        val id = sessions.connect(hostId = "host-b", title = "host-b", subtitle = "u@h:22", target = target, auth = auth)

        assertTrue(id != a)
        assertEquals(2, sessions.sessions.size)
        assertEquals(id, sessions.activeId)
        scope.cancel()
    }

    @Test
    fun `connect opens a new tab when there is no active session`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())

        val id = sessions.connect(hostId = "host-a", title = "host-a", subtitle = "u@h:22", target = target, auth = auth)

        assertEquals(1, sessions.sessions.size)
        assertEquals(id, sessions.activeId)
        scope.cancel()
    }

    @Test
    fun `effectiveTabTitle prefers live OSC title over fallback`() {
        assertEquals("vim ~/app", effectiveTabTitle(liveTitle = "vim ~/app", fallback = "web-1"))
    }

    @Test
    fun `effectiveTabTitle falls back when live title is null or blank`() {
        assertEquals("web-1", effectiveTabTitle(liveTitle = null, fallback = "web-1"))
        assertEquals("web-1", effectiveTabTitle(liveTitle = "", fallback = "web-1"))
        assertEquals("web-1", effectiveTabTitle(liveTitle = "   ", fallback = "web-1"))
    }
}

/** Транспорт, отдающий свежее соединение на каждый connect; список — для проверки disconnect. */
private class FakeTransport : SshTransport {
    val connections = mutableListOf<FakeConnection>()
    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        FakeConnection().also { connections += it }
}

private class FakeConnection : SshConnection {
    var disconnected = false
        private set

    override val isConnected: Boolean get() = !disconnected
    override suspend fun exec(command: String): ExecResult = throw UnsupportedOperationException()
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = FakeChannel()
    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun disconnect() {
        disconnected = true
    }
}

private class FakeChannel : ShellChannel {
    private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
    override val isOpen: Boolean = true
    override val output: Flow<ByteArray> = flow { for (chunk in emissions) emit(chunk) }
    override suspend fun write(data: ByteArray) {}
    override suspend fun resize(size: PtySize) {}
    override suspend fun close() {
        emissions.close()
    }
}
