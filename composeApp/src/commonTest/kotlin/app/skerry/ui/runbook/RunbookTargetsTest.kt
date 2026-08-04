package app.skerry.ui.runbook

import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.FakeShellChannel
import app.skerry.ui.connection.FakeSshConnection
import app.skerry.ui.connection.FakeSshTransport
import app.skerry.ui.session.SessionsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Turning a picked pane into a run target. A pane that is no longer connected must not become a
 * target that could only fail on its first step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunbookTargetsTest {

    private val target = SshTarget(host = "h", port = 22, username = "u")
    private val auth = SshAuth.Password("pw")

    private fun TestScope.sessions(): Pair<SessionsController, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var n = 0
        val controller = SessionsController(
            newId = { "s${n++}" },
            controllerFactory = {
                ConnectionController(
                    transport = FakeSshTransport(FakeSshConnection(FakeShellChannel())),
                    scope = scope,
                    newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
                )
            },
        )
        return controller to scope
    }

    private fun SessionsController.openHost(hostId: String) =
        open(hostId = hostId, title = hostId, subtitle = "u@h:22", target = target, auth = auth)

    @Test
    fun `a target carries the pane it runs in and the name it goes by`() = runTest {
        val (sessions, scope) = sessions()
        sessions.openHost("web-01")
        advanceUntilIdle()
        val pane = sessions.tabs.single().focusedPane.id

        val target = runbookTargets(sessions, listOf(pane)).single()

        assertEquals(pane, target.sessionId)
        assertEquals("web-01", target.label)
        scope.cancel()
    }

    @Test
    fun `a pane that is gone drops out instead of becoming a dead target`() = runTest {
        val (sessions, scope) = sessions()
        val id = sessions.openHost("web-01")
        advanceUntilIdle()
        val pane = sessions.tabs.first { it.id == id }.focusedPane.id

        val targets = runbookTargets(sessions, listOf(pane, "closed-pane"))

        assertEquals(listOf(pane), targets.map { it.sessionId })
        scope.cancel()
    }

    @Test
    fun `connected panes are what the picker offers`() = runTest {
        val (sessions, scope) = sessions()
        sessions.openHost("web-01")
        advanceUntilIdle()

        val offered = connectedRunbookPanes(sessions)

        assertEquals(listOf("web-01"), offered.map { it.label })
        assertEquals(sessions.tabs.single().focusedPane.id, offered.single().paneId)
        scope.cancel()
    }

    @Test
    fun `a host with a connected session resolves to its pane, an unopened one to nothing`() = runTest {
        val (sessions, scope) = sessions()
        val id = sessions.openHost("web-01")
        advanceUntilIdle()

        assertEquals(sessions.tabs.first { it.id == id }.focusedPane.id, connectedPaneOf(sessions, "web-01"))
        assertTrue(connectedPaneOf(sessions, "web-09") == null)
        scope.cancel()
    }
}
