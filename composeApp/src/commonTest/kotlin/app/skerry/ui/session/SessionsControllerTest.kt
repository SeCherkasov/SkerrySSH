package app.skerry.ui.session

import app.skerry.shared.vnc.VncRemoteDesktop
import app.skerry.shared.vnc.VncQuality
import app.skerry.shared.vnc.VncUpdate
import app.skerry.ui.remote.RemoteDesktopController
import app.skerry.shared.graphics.RemoteFramebuffer
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
import app.skerry.shared.terminal.Asciicast
import app.skerry.shared.terminal.CastEvent
import app.skerry.shared.vnc.VncAuth
import app.skerry.shared.vnc.VncPointerEvent
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.shared.vnc.VncSession
import app.skerry.shared.vnc.VncTransport
import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.shared.guard.ProductionGuardPolicy
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.session.SessionStatus
import app.skerry.ui.session.asSessionStatus
import app.skerry.ui.terminal.MirroredInput
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.workAreaSection
import app.skerry.ui.desktop.RAIL
import app.skerry.ui.desktop.RailTarget
import app.skerry.ui.desktop.openRailSection
import app.skerry.ui.desktop.railItemActive
import app.skerry.ui.host.HostSection
import app.skerry.ui.host.prodGuardDialogOpen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
import kotlin.test.assertNotNull
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
        assertTrue(sessions.tabs.isEmpty())
        assertNull(sessions.activeId)
        assertNull(sessions.active)
        scope.cancel()
    }

    @Test
    fun `open adds a session, makes it active and connects`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())

        val id = sessions.open(hostId = "host-a")

        assertEquals(1, sessions.tabs.size)
        assertEquals(id, sessions.activeId)
        assertIs<ConnectionUiState.Connected>(sessions.active!!.focusedPane.controller.uiState)
        scope.cancel()
    }

    @Test
    fun `opening a second session keeps order and activates the new one`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())

        val first = sessions.open(hostId = "host-a")
        val second = sessions.open(hostId = "host-b")

        assertEquals(listOf(first, second), sessions.tabs.map { it.id })
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

        assertEquals(listOf(a, c), sessions.tabs.map { it.id })
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

        assertTrue(sessions.tabs.isEmpty())
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
        assertEquals(listOf(a), sessions.tabs.map { it.id })
        scope.cancel()
    }

    @Test
    fun `sessionStatusFor reports the newest session of a host, idle for an unopened one`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        assertEquals(SessionStatus.Idle, sessions.sessionStatusFor("host-a"))

        sessions.open(hostId = "host-a")

        assertEquals(SessionStatus.Live, sessions.sessionStatusFor("host-a"))
        assertEquals(SessionStatus.Idle, sessions.sessionStatusFor("host-b"))
        scope.cancel()
    }

    // Panes: independent sessions tiled inside one tab

    private fun SessionsController.connectPane(tabId: String, paneId: String, hostId: String?) =
        connectPane(
            tabId = tabId, paneId = paneId, hostId = hostId, title = hostId ?: "",
            subtitle = "u@h:22", target = target, auth = auth,
        )

    // Grid shape as it reads on screen: rows separated by "|", panes within a row by ",".
    private fun shapeOf(tab: Tab): String = tab.layout.rows.joinToString("|") { row ->
        row.cells.joinToString(",") { cell -> tab.pane(cell.paneId)?.let { it.hostId ?: "empty" } ?: "?" }
    }

    @Test
    fun `a fresh session is a single pane, focused, with sync off`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val id = sessions.open(hostId = "host-a")
        val tab = sessions.active!!
        assertFalse(tab.isSplit)
        assertEquals(listOf(id), tab.layout.paneIds)
        assertEquals(id, tab.focusedPaneId)
        assertFalse(tab.syncInput)
        scope.cancel()
    }

    @Test
    fun `addPane puts an empty pane beside the first one and focuses it`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")

        val pane = sessions.addPane()!!

        val tab = sessions.active!!
        assertEquals("host-a,empty", shapeOf(tab)) // side by side, like the split it replaces
        assertEquals(pane, tab.focusedPaneId)
        assertTrue(tab.pane(pane)!!.isBlank) // shows the host picker until something is connected
        scope.cancel()
    }

    @Test
    fun `addPane fills a two by two grid and refuses a fifth pane`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        sessions.open(hostId = "host-a")

        repeat(MAX_PANES - 1) { assertNotNull(sessions.addPane()) }

        val tab = sessions.active!!
        assertEquals("host-a,empty|empty,empty", shapeOf(tab))
        assertNull(sessions.addPane())
        assertEquals(MAX_PANES, tab.panes.size)
        scope.cancel()
    }

    @Test
    fun `addPane lands where the drop asked`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        sessions.open(hostId = "host-a")

        sessions.addPane(slot = PaneSlot.NewRow(0))

        assertEquals("empty|host-a", shapeOf(sessions.active!!))
        scope.cancel()
    }

    @Test
    fun `addPane is refused on a remote-desktop tab`() = runTest {
        val (sessions, scope) = sessionsWithVnc(FakeVncTransport())
        sessions.openVnc(hostId = "host-a")

        assertNull(sessions.addPane())
        scope.cancel()
    }

    @Test
    fun `connectPane fills an empty pane in place and keeps its slot`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val pane = sessions.addPane()!!

        sessions.connectPane(tabId = a, paneId = pane, hostId = "host-b")

        val tab = sessions.active!!
        assertEquals("host-a,host-b", shapeOf(tab))
        assertEquals(pane, tab.panes.last().id) // filled in place: same pane, same slot
        assertEquals(pane, tab.focusedPaneId)
        assertTrue(tab.panes.last().controller !== tab.panes.first().controller) // its own connection
        assertIs<ConnectionUiState.Connected>(tab.panes.last().controller.uiState)
        scope.cancel()
    }

    @Test
    fun `connectPane on a live pane replaces its session in the same slot`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        val a = sessions.open(hostId = "host-a")
        val pane = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = pane, hostId = "host-b")
        val firstConnection = transport.connections[1]

        sessions.connectPane(tabId = a, paneId = pane, hostId = "host-c")

        val tab = sessions.active!!
        assertTrue(firstConnection.disconnected) // the replaced session is torn down, not leaked
        assertEquals("host-a,host-c", shapeOf(tab))
        assertEquals(2, tab.panes.size)
        assertEquals(tab.panes.last().id, tab.focusedPaneId)
        scope.cancel()
    }

    @Test
    fun `connectPane does not add the pane to the tab list`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val pane = sessions.addPane()!!

        sessions.connectPane(tabId = a, paneId = pane, hostId = "host-b")

        assertEquals(listOf(a), sessions.tabs.map { it.id }) // panes are not tabs
        scope.cancel()
    }

    @Test
    fun `closePane disconnects the pane and takes it off the grid`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        val a = sessions.open(hostId = "host-a")
        val pane = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = pane, hostId = "host-b")
        val paneConnection = transport.connections[1]

        sessions.closePane(a, pane)

        val tab = sessions.active!!
        assertFalse(tab.isSplit)
        assertEquals("host-a", shapeOf(tab))
        assertEquals(a, tab.focusedPaneId) // focus falls back to the remaining neighbor
        assertTrue(paneConnection.disconnected)
        scope.cancel()
    }

    @Test
    fun `closePane closes the tab's first pane like any other and keeps the tab`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        val a = sessions.open(hostId = "host-a")
        val pane = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = pane, hostId = "host-b")
        val firstConnection = transport.connections[0]

        sessions.closePane(a, a)

        val tab = sessions.active!!
        assertEquals("host-b", shapeOf(tab)) // the survivor takes the whole grid
        assertEquals(listOf(pane), tab.panes.map { it.id })
        assertEquals(pane, tab.focusedPaneId)
        assertTrue(firstConnection.disconnected)
        assertEquals(a, tab.id) // the tab keeps its identity, chip and position
        scope.cancel()
    }

    // Three panes on purpose: with two, "the pane after it" and "the pane before it" are the same
    // survivor, so the fallback order and the untouched-focus branch both look right either way.
    @Test
    fun `closePane hands the focus to the pane after it`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        val a = sessions.open(hostId = "host-a")
        val second = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = second, hostId = "host-b")
        val third = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = third, hostId = "host-c")
        sessions.focusPane(a, second)

        sessions.closePane(a, second)

        assertEquals(third, sessions.tab(a)!!.focusedPaneId)
        scope.cancel()
    }

    @Test
    fun `closing a pane the keyboard is not in leaves the focus where it was`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        val a = sessions.open(hostId = "host-a")
        val second = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = second, hostId = "host-b")
        val third = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = third, hostId = "host-c")
        // Focus sits on the first pane, while closing the middle one would otherwise move it to the
        // third: picking any other pane here would pass with or without the guard on the focused id.
        sessions.focusPane(a, a)

        sessions.closePane(a, second)

        assertEquals(a, sessions.tab(a)!!.focusedPaneId)
        scope.cancel()
    }

    @Test
    fun `closing the last pane closes its tab`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        val a = sessions.open(hostId = "host-a")
        sessions.open(hostId = "host-b")
        sessions.activate(a)

        sessions.closePane(a, a)

        assertEquals(1, sessions.tabs.size) // the tab went with its only pane
        assertTrue(transport.connections[0].disconnected)
        scope.cancel()
    }

    @Test
    fun `connectPane re-points the tab's first pane`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        val a = sessions.open(hostId = "host-a")

        sessions.connectPane(tabId = a, paneId = a, hostId = "host-b")

        val tab = sessions.active!!
        assertEquals("host-b", shapeOf(tab))
        assertEquals(1, tab.panes.size)
        assertTrue(transport.connections[0].disconnected) // the old session is torn down, not leaked
        assertEquals(tab.panes.single().id, tab.focusedPaneId)
        scope.cancel()
    }

    @Test
    fun `sessionStatusFor sees a host connected in a pane, not just in a tab of its own`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val pane = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = pane, hostId = "host-b")

        assertEquals(SessionStatus.Live, sessions.sessionStatusFor("host-b"))
        scope.cancel()
    }

    @Test
    fun `closing a tab disconnects every pane`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        val a = sessions.open(hostId = "host-a")
        val pane = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = pane, hostId = "host-b")

        sessions.close(a)

        assertTrue(sessions.tabs.isEmpty())
        assertTrue(transport.connections.all { it.disconnected })
        scope.cancel()
    }

    @Test
    fun `disconnectAll disconnects panes too`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        val a = sessions.open(hostId = "host-a")
        val pane = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = pane, hostId = "host-b")

        sessions.disconnectAll()

        assertTrue(sessions.tabs.isEmpty())
        assertTrue(transport.connections.all { it.disconnected })
        scope.cancel()
    }

    @Test
    fun `focusPane switches panes and ignores one from another tab`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val b = sessions.open(hostId = "host-b")
        sessions.activate(a)
        val pane = sessions.addPane()!!

        sessions.focusPane(a, pane)
        assertEquals(pane, sessions.active!!.focusedPaneId)

        sessions.focusPane(a, a)
        assertEquals(a, sessions.active!!.focusedPaneId)

        sessions.focusPane(a, b) // another tab's session is not a pane of this one
        assertEquals(a, sessions.active!!.focusedPaneId)
        scope.cancel()
    }

    @Test
    fun `focusNeighborPane walks the grid from the focused pane`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val right = sessions.addPane()!!
        val below = sessions.addPane(slot = PaneSlot.NewRow(1))!!
        sessions.focusPane(a, a)

        assertTrue(sessions.focusNeighborPane(PaneDirection.Right))
        assertEquals(right, sessions.active!!.focusedPaneId)

        assertTrue(sessions.focusNeighborPane(PaneDirection.Down))
        assertEquals(below, sessions.active!!.focusedPaneId)

        assertTrue(sessions.focusNeighborPane(PaneDirection.Up))
        assertEquals(a, sessions.active!!.focusedPaneId)
        scope.cancel()
    }

    @Test
    fun `focusNeighborPane reports nothing to move to at the edge of the grid`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")

        // Unsplit tab: no pane in any direction, so the caller lets the key reach the terminal.
        assertFalse(sessions.focusNeighborPane(PaneDirection.Right))

        sessions.addPane()
        sessions.focusPane(a, a)
        assertFalse(sessions.focusNeighborPane(PaneDirection.Left))
        assertEquals(a, sessions.active!!.focusedPaneId)
        scope.cancel()
    }

    @Test
    fun `focusNeighborPane does nothing while the tab shows something other than its panes`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        sessions.addPane()
        sessions.focusPane(a, a)

        // Over the file panel the grid isn't on screen and the arrows belong to the listing, so the
        // focus must not move behind the user's back (the file panel follows the focused pane).
        // Same for every other view a tab can show — none of them draws the pane grid.
        (SessionView.entries - SessionView.Terminal).forEach { view ->
            sessions.setActiveView(view)
            assertFalse(sessions.focusNeighborPane(PaneDirection.Right), "$view")
            assertEquals(a, sessions.active!!.focusedPaneId, "$view")
        }

        sessions.setActiveView(SessionView.Terminal)
        assertTrue(sessions.focusNeighborPane(PaneDirection.Right))
        scope.cancel()
    }

    @Test
    fun `movePane rearranges the grid`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val pane = sessions.addPane()!!
        assertEquals("host-a,empty", shapeOf(sessions.active!!))

        sessions.movePane(a, pane, PaneSlot.NewRow(0)) // dragged above the first pane

        assertEquals("empty|host-a", shapeOf(sessions.active!!))
        scope.cancel()
    }

    @Test
    fun `dividers move the panes' shares`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        sessions.addPane() // side by side
        sessions.addPane() // second row

        sessions.resizePaneCells(a, row = 0, boundary = 0, delta = 0.1f)
        sessions.resizePaneRows(a, boundary = 0, delta = -0.2f)

        val layout = sessions.active!!.layout
        assertEquals(0.6f, layout.rows[0].cells[0].weight, 1e-4f)
        assertEquals(0.3f, layout.rows[0].weight, 1e-4f)
        scope.cancel()
    }

    @Test
    fun `synchronized input is off until the tab's toggle is on`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val pane = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = pane, hostId = "host-b")
        val tab = sessions.active!!

        assertTrue(tab.syncTargetsFrom(a).isEmpty())

        sessions.toggleSyncInput(a)
        assertTrue(tab.syncInput)
        assertEquals(1, tab.syncTargetsFrom(a).size)

        sessions.toggleSyncInput(a)
        assertTrue(tab.syncTargetsFrom(a).isEmpty())
        scope.cancel()
    }

    @Test
    fun `synchronized input reaches the other connected panes only`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val connected = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = connected, hostId = "host-b")
        sessions.addPane() // left empty: nothing to type into
        sessions.open(hostId = "host-other") // another tab is not part of this tab's sync
        sessions.activate(a)
        val tab = sessions.active!!
        sessions.toggleSyncInput(a)

        // From the first pane: only the connected sibling, never the origin itself.
        val fromPrimary = tab.syncTargetsFrom(a)
        assertEquals(listOf(tab.pane(connected)!!.liveTerminal), fromPrimary)
        // And the other way round.
        assertEquals(listOf(tab.pane(a)!!.liveTerminal), tab.syncTargetsFrom(connected))
        scope.cancel()
    }

    @Test
    fun `mirrored input is delivered to the tab's other panes and stops there`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val paneId = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = paneId, hostId = "host-b")
        val other = sessions.open(hostId = "host-other") // a second tab, live, with sync of its own
        sessions.toggleSyncInput(other)
        sessions.activate(a)
        val tab = sessions.active!!
        val pane = tab.pane(paneId)!!
        sessions.toggleSyncInput(a)
        val before = pane.liveTerminal!!.inputVersion
        val otherBefore = sessions.tab(other)!!.focusedPane.liveTerminal!!.inputVersion

        mirrorPaneInput(tab, originPaneId = a, text = "uptime\n", kind = MirroredInput.Typed)

        // The sibling pane took the keystroke...
        assertTrue(pane.liveTerminal!!.inputVersion > before)
        // ...and did not hand it back: a mirror on the receiving side would have bounced it.
        assertNull(pane.liveTerminal!!.inputMirror)
        // ...and the other tab never saw it: synchronized input is scoped to one tab, so a second
        // tab with its own live session stays untouched however the fan-out is wired.
        assertEquals(otherBefore, sessions.tab(other)!!.focusedPane.liveTerminal!!.inputVersion)
        scope.cancel()
    }

    @Test
    fun `connectPane refuses a remote-desktop pane`() = runTest {
        val vncTransport = FakeVncTransport()
        val (sessions, scope) = sessionsWithVnc(vncTransport)
        val vnc = sessions.openVnc(hostId = "host-a")!!

        sessions.connectPane(tabId = vnc, paneId = vnc, hostId = "host-b")

        // Still the framebuffer it was: swapping a remote desktop for a shell under the same tab
        // would leave the tab belonging to neither section.
        val tab = sessions.tab(vnc)!!
        assertTrue(tab.isVnc)
        assertEquals(1, tab.panes.size)
        assertEquals("host-a", tab.focusedPane.hostId)
        scope.cancel()
    }

    @Test
    fun `broadcastTargets covers connected tabs and their panes`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a", title = "alpha")
        sessions.open(hostId = "host-b", title = "beta")
        sessions.activate(a)
        val pane = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = pane, hostId = "host-c")

        val targets = broadcastTargets(sessions)

        // Each pane is its own shell, so it is a target in its own right.
        assertEquals(3, targets.size)
        assertEquals(listOf("alpha", "beta"), targets.map { it.label }.filter { it == "alpha" || it == "beta" })
        assertTrue(targets.map { it.id }.contains(sessions.active!!.panes.last().id))
        scope.cancel()
    }

    @Test
    fun `a broadcast reaches only what the panel selected, not synchronized siblings`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val paneId = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = paneId, hostId = "host-b")
        val tab = sessions.active!!
        sessions.toggleSyncInput(a)
        val sibling = tab.pane(paneId)!!.liveTerminal!!
        val before = sibling.inputVersion
        // Wire the mirror exactly as PaneSyncBinder does in composition — without this the test
        // would pass no matter what, since nothing would be listening to fan out in the first place.
        tab.pane(a)!!.liveTerminal!!.inputMirror = { text, kind -> mirrorPaneInput(tab, a, text, kind) }

        // The panel's checkboxes are the target list: a send to the first pane alone must not
        // fan out through the tab's sync, which would reach a pane left unchecked on purpose —
        // past the production confirmation, which counted only the selected ones.
        val targets = broadcastTargets(sessions)
        targets.first { it.id == a }.send("uptime\n")

        assertEquals(before, sibling.inputVersion)
        scope.cancel()
    }

    @Test
    fun `broadcastTargets marks production sessions and delivers past their own guard`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val prod = sessions.open(hostId = "host-prod")
        sessions.open(hostId = "host-stage")

        val targets = broadcastTargets(sessions, isProduction = { it == "host-prod" })

        assertEquals(listOf(true, false), targets.map { it.production })
        // The per-session guard is deliberately off for a broadcast: the panel confirms once for the
        // whole fan-out, and holding here would park commands in tabs nobody is looking at. That is
        // also why the panel MUST ask — nothing downstream will.
        val terminal = sessions.tabs.first { it.id == prod }.focusedPane.liveTerminal!!
        terminal.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        assertTrue(targets.first { it.production }.send("rm -rf /srv\n"))
        assertNull(terminal.pendingGuarded)
        scope.cancel()
    }

    @Test
    fun `a held confirmation is visible to the window-level hotkey gate from either pane`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val pane = sessions.addPane()!!
        sessions.connectPane(tabId = a, paneId = pane, hostId = "host-b")
        val session = sessions.active!!

        assertFalse(prodGuardDialogOpen(session))
        assertFalse(prodGuardDialogOpen(null))

        // Held in the second pane — the one being typed into. The gate has to see it there too, or a
        // snippet chord would fire over the open dialog.
        val secondary = session.panes.last().liveTerminal!!
        secondary.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        secondary.typeInput("shutdown now\r")
        assertTrue(prodGuardDialogOpen(session))

        secondary.dismissGuardedCommand()
        assertFalse(prodGuardDialogOpen(session))
        scope.cancel()
    }

    @Test
    fun `broadcastTargets skips a session that is not connected`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        sessions.addPane() // pane open but nothing connected into it

        assertEquals(listOf(a), broadcastTargets(sessions).map { it.id })
        scope.cancel()
    }

    @Test
    fun `broadcastTargets excludes VNC tabs and an absent controller`() = runTest {
        val vncTransport = FakeVncTransport()
        val (sessions, scope) = sessionsWithVnc(vncTransport)
        sessions.openVnc(hostId = "host-a") // no shell to type into

        assertTrue(broadcastTargets(sessions).isEmpty())
        assertTrue(broadcastTargets(null).isEmpty())
        scope.cancel()
    }

    @Test
    fun `disconnectAll closes every session`() = runTest {
        val transport = FakeTransport()
        val (sessions, scope) = sessionsWith(transport)
        sessions.open(hostId = "host-a")
        sessions.open(hostId = "host-b")

        sessions.disconnectAll()

        assertTrue(sessions.tabs.isEmpty())
        assertNull(sessions.activeId)
        assertTrue(transport.connections.all { it.disconnected })
        scope.cancel()
    }

    // Blank tab with no session + per-tab view + connect-reuse

    @Test
    fun `openBlank adds an active tab with no connection`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())

        val id = sessions.openBlank()

        assertEquals(1, sessions.tabs.size)
        assertEquals(id, sessions.activeId)
        val tab = sessions.active!!
        assertTrue(tab.isBlank)
        assertNull(tab.focusedPane.hostId)
        assertIs<ConnectionUiState.Form>(tab.focusedPane.controller.uiState) // no connection is started
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
        val b = sessions.open(hostId = "host-b") // b is active

        sessions.setActiveView(SessionView.Sftp)

        assertEquals(SessionView.Sftp, sessions.tabs.first { it.id == b }.view)
        assertEquals(SessionView.Terminal, sessions.tabs.first { it.id == a }.view) // leaves the sibling untouched
        scope.cancel()
    }

    @Test
    fun `connect reuses the active blank tab in place`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val blank = sessions.openBlank()

        val id = sessions.connect(hostId = "host-a", title = "host-a", subtitle = "u@h:22", target = target, auth = auth)

        assertEquals(blank, id) // same tab, no new one created
        assertEquals(1, sessions.tabs.size)
        val tab = sessions.active!!
        assertEquals("host-a", tab.focusedPane.hostId)
        assertEquals("host-a", tab.focusedPane.title)
        assertFalse(tab.isBlank)
        assertIs<ConnectionUiState.Connected>(tab.focusedPane.controller.uiState)
        scope.cancel()
    }

    @Test
    fun `every real connection to a host is reported once, blank tabs and players never`() = runTest {
        // Feeds the Teams activity log (see TeamsCoordinator.reportSessionOpen): every path that
        // actually opens a session to a catalog host must report, and only those.
        val opened = mutableListOf<String>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var n = 0
        val vncTransport = FakeVncTransport()
        val sessions = SessionsController(
            newId = { "s${n++}" },
            controllerFactory = {
                ConnectionController(
                    transport = FakeTransport(),
                    scope = scope,
                    newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
                )
            },
            vncControllerFactory = { RemoteDesktopController(scope) },
            openVncSession = { target, auth -> VncRemoteDesktop(vncTransport.connect(target, auth)) },
            onHostSessionOpened = { opened += it },
        )

        val blank = sessions.openBlank()
        assertTrue(opened.isEmpty()) // no connection started yet
        sessions.connect(hostId = "host-a", title = "a", subtitle = "u@h:22", target = target, auth = auth)
        sessions.connect(hostId = "host-b", title = "b", subtitle = "u@h:22", target = target, auth = auth)
        sessions.open(hostId = "host-c")
        val pane = sessions.addPane(blank)!!
        sessions.connectPane(tabId = blank, paneId = pane, hostId = "host-d", title = "d", subtitle = "u@h:22", target = target, auth = auth)
        sessions.openVnc(hostId = "host-e")
        sessions.openPlayer("recording", Asciicast(80, 24, "cast", emptyList()))
        // An ad-hoc connection typed into the form belongs to no catalog host: nothing to report.
        sessions.open(hostId = null)

        assertEquals(listOf("host-a", "host-b", "host-c", "host-d", "host-e"), opened)
        scope.cancel()
    }

    @Test
    fun `connect opens a new tab when the active one is not blank`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a") // connected, not blank

        val id = sessions.connect(hostId = "host-b", title = "host-b", subtitle = "u@h:22", target = target, auth = auth)

        assertTrue(id != a)
        assertEquals(2, sessions.tabs.size)
        assertEquals(id, sessions.activeId)
        scope.cancel()
    }

    @Test
    fun `connect opens a new tab when there is no active session`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())

        val id = sessions.connect(hostId = "host-a", title = "host-a", subtitle = "u@h:22", target = target, auth = auth)

        assertEquals(1, sessions.tabs.size)
        assertEquals(id, sessions.activeId)
        scope.cancel()
    }

    // Drag-reorder tabs

    @Test
    fun `moveTab reorders tabs and keeps the active one`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val b = sessions.open(hostId = "host-b")
        val c = sessions.open(hostId = "host-c")
        sessions.activate(a)

        sessions.moveTab(fromIndex = 0, toIndex = 2) // a moves to the end

        assertEquals(listOf(b, c, a), sessions.tabs.map { it.id })
        assertEquals(a, sessions.activeId) // active tab does not change on move
        scope.cancel()
    }

    @Test
    fun `moveTab can move a tab to the front`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val b = sessions.open(hostId = "host-b")
        val c = sessions.open(hostId = "host-c")

        sessions.moveTab(fromIndex = 2, toIndex = 0) // c moves to front

        assertEquals(listOf(c, a, b), sessions.tabs.map { it.id })
        scope.cancel()
    }

    @Test
    fun `moveTab ignores out-of-range or no-op moves`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val a = sessions.open(hostId = "host-a")
        val b = sessions.open(hostId = "host-b")

        sessions.moveTab(fromIndex = 0, toIndex = 0) // no-op
        sessions.moveTab(fromIndex = 5, toIndex = 0) // out of range
        sessions.moveTab(fromIndex = 0, toIndex = 9) // out of range

        assertEquals(listOf(a, b), sessions.tabs.map { it.id })
        scope.cancel()
    }

    // VNC tabs

    private fun TestScope.sessionsWithVnc(
        vncTransport: FakeVncTransport,
    ): Pair<SessionsController, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var n = 0
        val controller = SessionsController(
            newId = { "s${n++}" },
            controllerFactory = {
                ConnectionController(
                    transport = FakeTransport(),
                    scope = scope,
                    newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
                )
            },
            vncControllerFactory = { RemoteDesktopController(scope) },
            openVncSession = { target, auth -> VncRemoteDesktop(vncTransport.connect(target, auth)) },
        )
        return controller to scope
    }

    private fun SessionsController.openVnc(hostId: String?) =
        openVnc(hostId = hostId, title = hostId ?: "", subtitle = "h:5900", target = target, auth = VncAuth.None)

    @Test
    fun `openVnc opens a connected VNC tab locked to the Vnc view`() = runTest {
        val vncTransport = FakeVncTransport()
        val (sessions, scope) = sessionsWithVnc(vncTransport)

        val id = sessions.openVnc(hostId = "host-a")

        assertEquals(id, sessions.activeId)
        val tab = sessions.active!!
        assertTrue(tab.isVnc)
        assertFalse(tab.isBlank)
        assertEquals(SessionView.Vnc, tab.view)
        assertEquals(1, vncTransport.sessions.size)

        sessions.setActiveView(SessionView.Sftp) // no-op on a VNC tab
        assertEquals(SessionView.Vnc, tab.view)
        scope.cancel()
    }

    @Test
    fun `openVnc without a VNC factory is a no-op returning null`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport()) // no vncControllerFactory wired

        val id = sessions.openVnc(hostId = "host-a")

        assertNull(id)
        assertTrue(sessions.tabs.isEmpty())
        scope.cancel()
    }

    @Test
    fun `openVnc opens a fresh tab instead of reusing a blank one`() = runTest {
        val (sessions, scope) = sessionsWithVnc(FakeVncTransport())
        val blank = sessions.openBlank()

        val id = sessions.openVnc(hostId = "host-a")

        assertTrue(id != blank)
        assertEquals(2, sessions.tabs.size)
        assertEquals(id, sessions.activeId)
        scope.cancel()
    }

    @Test
    fun `closing a VNC tab closes its session`() = runTest {
        val vncTransport = FakeVncTransport()
        val (sessions, scope) = sessionsWithVnc(vncTransport)
        val id = sessions.openVnc(hostId = "host-a")!!

        sessions.close(id)

        assertTrue(sessions.tabs.isEmpty())
        assertTrue(vncTransport.sessions.single().closed)
        scope.cancel()
    }

    @Test
    fun `disconnectAll closes VNC sessions too`() = runTest {
        val vncTransport = FakeVncTransport()
        val (sessions, scope) = sessionsWithVnc(vncTransport)
        sessions.openVnc(hostId = "host-a")

        sessions.disconnectAll()

        assertTrue(sessions.tabs.isEmpty())
        assertTrue(vncTransport.sessions.single().closed)
        scope.cancel()
    }

    @Test
    fun `a connected remote desktop reports a live status, not the placeholder controller's`() = runTest {
        val (sessions, scope) = sessionsWithVnc(FakeVncTransport())

        val desktop = sessions.openVnc(hostId = "screen")!!

        val session = sessions.tab(desktop)!!.focusedPane
        assertEquals(SessionStatus.Live, session.status)
        // The terminal controller beside it never connects — reading it alone reports Idle.
        assertEquals(SessionStatus.Idle, session.controller.uiState.asSessionStatus())
        scope.cancel()
    }

    @Test
    fun `the catalog dot sees a connected desktop, and nothing for an unopened host`() = runTest {
        val (sessions, scope) = sessionsWithVnc(FakeVncTransport())

        sessions.openVnc(hostId = "screen")

        assertEquals(SessionStatus.Live, sessions.sessionStatusFor("screen"))
        assertEquals(SessionStatus.Idle, sessions.sessionStatusFor("host-b"))
        scope.cancel()
    }

    @Test
    fun `a connected shell reports a live status`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())

        val id = sessions.open(hostId = "host-a")

        assertEquals(SessionStatus.Live, sessions.tab(id)!!.focusedPane.status)
        scope.cancel()
    }

    // Section-scoped active tab (terminal shell vs remote desktop)

    @Test
    fun `the active tab is reported to the section it belongs to`() = runTest {
        val (sessions, scope) = sessionsWithVnc(FakeVncTransport())
        val shell = sessions.open(hostId = "host-a")

        assertEquals(shell, sessions.activeTerminal?.id)
        assertNull(sessions.activeDesktop)

        val desktop = sessions.openVnc(hostId = "screen")!!
        assertEquals(desktop, sessions.activeDesktop?.id)
        assertNull(sessions.activeTerminal)
        scope.cancel()
    }

    @Test
    fun `a player tab belongs to the terminal section`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())

        val id = sessions.openPlayer("deploy", cast)

        assertEquals(id, sessions.activeTerminal?.id)
        assertNull(sessions.activeDesktop)
        sessions.close(id)
        scope.cancel()
    }

    // Player tabs

    private val cast = Asciicast(80, 24, "deploy", listOf(CastEvent(0.0, "hi")))

    @Test
    fun `openPlayer opens a player tab locked to the Player view`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())

        val id = sessions.openPlayer("deploy", cast)

        assertEquals(id, sessions.activeId)
        val tab = sessions.active!!
        assertTrue(tab.isPlayer)
        assertFalse(tab.isBlank) // a player tab is not an empty tab waiting for a connection
        assertEquals(SessionView.Player, tab.view)
        assertEquals(cast, tab.focusedPane.playback?.cast)

        sessions.setActiveView(SessionView.Sftp) // no-op on a player tab
        assertEquals(SessionView.Player, tab.view)
        sessions.close(id)
        scope.cancel()
    }

    @Test
    fun `openPlayer opens a fresh tab instead of reusing a blank one`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val blank = sessions.openBlank()

        val id = sessions.openPlayer("deploy", cast)

        assertTrue(id != blank)
        assertEquals(2, sessions.tabs.size)
        sessions.close(id)
        scope.cancel()
    }

    @Test
    fun `closing a player tab stops its playback`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        val id = sessions.openPlayer("deploy", cast)
        val playback = sessions.active!!.focusedPane.playback!!

        sessions.close(id)

        assertTrue(sessions.tabs.isEmpty())
        assertTrue(playback.stopped)
        scope.cancel()
    }

    @Test
    fun `disconnectAll stops playback too`() = runTest {
        val (sessions, scope) = sessionsWith(FakeTransport())
        sessions.openPlayer("deploy", cast)
        val playback = sessions.active!!.focusedPane.playback!!

        sessions.disconnectAll()

        assertTrue(sessions.tabs.isEmpty())
        assertTrue(playback.stopped)
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

/** VNC transport that returns a fresh fake session on each connect; list is used to verify closes. */
private class FakeVncTransport : VncTransport {
    val sessions = mutableListOf<FakeVncSession>()
    override suspend fun connect(target: SshTarget, auth: VncAuth): VncSession =
        FakeVncSession().also { sessions += it }
}

private class FakeVncSession : VncSession {
    var closed = false
        private set

    override val serverName = "desk"
    override val framebuffer = RemoteFramebuffer(1, 1)

    // Never emits: keeps the read loop parked (like a quiet server) until the scope is cancelled.
    override val updates: Flow<VncUpdate> = flow { awaitCancellation() }

    override suspend fun sendPointer(event: VncPointerEvent) {}
    override suspend fun sendKey(keySym: Long, down: Boolean) {}
    override suspend fun sendClientCutText(text: String) {}
    override suspend fun requestUpdate(incremental: Boolean) {}
    override suspend fun setQuality(quality: VncQuality) {}
    override suspend fun setDesktopSize(width: Int, height: Int) {}
    override suspend fun setLocalCursor(enabled: Boolean) {}
    override suspend fun close() {
        closed = true
    }
}

/** Transport that returns a fresh connection on each connect; list is used to verify disconnects. */
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

/**
 * Rail ⇄ tabs agreement: the work area, the sidebar and the highlighted rail item all read
 * [DesktopDesignState.section], so switching one must move the others.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSectionNavigationTest {

    private val target = SshTarget(host = "h", port = 22, username = "u")

    private fun TestScope.sessions(): Pair<SessionsController, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var n = 0
        val controller = SessionsController(
            newId = { "s${n++}" },
            controllerFactory = {
                ConnectionController(
                    transport = FakeTransport(),
                    scope = scope,
                    newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
                )
            },
            vncControllerFactory = { RemoteDesktopController(scope) },
            openVncSession = { target, auth -> VncRemoteDesktop(FakeVncTransport().connect(target, auth)) },
        )
        return controller to scope
    }

    @Test
    fun `opening a section keeps the running session on screen`() = runTest {
        val (sessions, scope) = sessions()
        val state = DesktopDesignState()
        val shell = sessions.open("a", "a", "", target, SshAuth.Password("pw"))

        openRailSection(state, HostSection.RemoteDesktops)

        // The rail moved the catalog to the desktops, but the shell is what the user was working
        // in: it stays selected and stays on screen. The desktops catalog is how a desktop opens.
        assertEquals(HostSection.RemoteDesktops, state.section)
        assertEquals(shell, sessions.activeId)
        assertEquals(HostSection.Terminal, workAreaSection(sessions.active, state.section))
        scope.cancel()
    }

    @Test
    fun `opening a section with no tabs switches the whole work area`() = runTest {
        val (sessions, scope) = sessions()
        val state = DesktopDesignState()

        openRailSection(state, HostSection.RemoteDesktops)

        // Nothing to keep on screen, so the rail decides: the desktops empty state.
        assertEquals(HostSection.RemoteDesktops, workAreaSection(sessions.active, state.section))
        scope.cancel()
    }

    @Test
    fun `a desktop session stays on screen while the terminal catalog is open`() = runTest {
        val (sessions, scope) = sessions()
        val state = DesktopDesignState()
        sessions.openVnc("screen", "screen", "", target, VncAuth.None)!!

        openRailSection(state, HostSection.Terminal)

        // Symmetric to the shell case: the framebuffer keeps rendering while the hosts catalog is
        // the one in the sidebar.
        assertEquals(HostSection.Terminal, state.section)
        assertEquals(HostSection.RemoteDesktops, workAreaSection(sessions.active, state.section))
        scope.cancel()
    }

    @Test
    fun `selecting a tab moves the work area, never the rail`() = runTest {
        val (sessions, scope) = sessions()
        val state = DesktopDesignState()
        val shell = sessions.open("a", "a", "", target, SshAuth.Password("pw"))
        val desktop = sessions.openVnc("screen", "screen", "", target, VncAuth.None)!!
        openRailSection(state, HostSection.Terminal)

        // Chip clicks and tab hotkeys swap what's on screen and nothing else: the catalog stays the
        // one the user opened, so tabbing through sessions doesn't shuffle the sidebar under them.
        sessions.activate(desktop)
        assertEquals(HostSection.Terminal, state.section)
        assertEquals(HostSection.RemoteDesktops, workAreaSection(sessions.active, state.section))

        sessions.activate(shell)
        assertEquals(HostSection.Terminal, state.section)
        assertEquals(HostSection.Terminal, workAreaSection(sessions.active, state.section))
        scope.cancel()
    }

    @Test
    fun `the rail highlights the open section, and nothing while an overlay is up`() {
        val state = DesktopDesignState()
        val terminal = RAIL.first { it.target == RailTarget.Section(HostSection.Terminal) }
        val desktops = RAIL.first { it.target == RailTarget.Section(HostSection.RemoteDesktops) }
        val vault = RAIL.first { it.target == RailTarget.View(DesktopView.Vault) }

        assertTrue(railItemActive(terminal, state))
        assertFalse(railItemActive(desktops, state))

        state.showSection(HostSection.RemoteDesktops)
        assertTrue(railItemActive(desktops, state))
        assertFalse(railItemActive(terminal, state))

        state.showView(DesktopView.Vault)
        assertTrue(railItemActive(vault, state))
        assertFalse(railItemActive(desktops, state)) // work-area items dim under an overlay
    }

    @Test
    fun `closing the last tab keeps the open catalog and shows its empty state`() = runTest {
        val (sessions, scope) = sessions()
        val state = DesktopDesignState()
        val desktop = sessions.openVnc("screen", "screen", "", target, VncAuth.None)!!
        openRailSection(state, HostSection.RemoteDesktops)
        assertEquals(HostSection.RemoteDesktops, state.section)

        sessions.close(desktop)

        assertTrue(sessions.tabs.isEmpty())
        assertEquals(HostSection.RemoteDesktops, state.section)
        assertEquals(HostSection.RemoteDesktops, workAreaSection(sessions.active, state.section))
        scope.cancel()
    }

    @Test
    fun `closing the active tab hands the screen to the neighbour, catalog unchanged`() = runTest {
        val (sessions, scope) = sessions()
        val state = DesktopDesignState()
        val shell = sessions.open("a", "a", "", target, SshAuth.Password("pw"))
        val desktop = sessions.openVnc("screen", "screen", "", target, VncAuth.None)!!
        openRailSection(state, HostSection.RemoteDesktops)
        assertEquals(HostSection.RemoteDesktops, state.section)

        sessions.close(desktop)

        // The shell takes over the screen; the rail and the sidebar stay on the desktops catalog.
        assertEquals(shell, sessions.activeId)
        assertEquals(HostSection.RemoteDesktops, state.section)
        assertEquals(HostSection.Terminal, workAreaSection(sessions.active, state.section))
        scope.cancel()
    }

    @Test
    fun `closing an inactive tab never moves the selection`() = runTest {
        val (sessions, scope) = sessions()
        val state = DesktopDesignState()
        val first = sessions.open("a", "a", "", target, SshAuth.Password("pw"))
        val second = sessions.open("b", "b", "", target, SshAuth.Password("pw"))
        sessions.openVnc("screen", "screen", "", target, VncAuth.None)
        sessions.activate(second)
        // Working in a shell while browsing the desktop catalog — the rail leaves the selection be.
        openRailSection(state, HostSection.RemoteDesktops)

        sessions.close(first)

        assertEquals(second, sessions.activeId)
        assertEquals(HostSection.RemoteDesktops, state.section)
        scope.cancel()
    }

    @Test
    fun `closing a tab of the other section leaves the open one alone`() = runTest {
        val (sessions, scope) = sessions()
        val state = DesktopDesignState()
        val shell = sessions.open("a", "a", "", target, SshAuth.Password("pw"))
        val desktop = sessions.openVnc("screen", "screen", "", target, VncAuth.None)!!
        sessions.activate(shell)
        openRailSection(state, HostSection.Terminal)

        // The "×" of a remote-desktop chip is reachable from the terminal section — one tab row.
        sessions.close(desktop)

        assertEquals(HostSection.Terminal, state.section)
        assertEquals(shell, sessions.activeId)
        scope.cancel()
    }
}
