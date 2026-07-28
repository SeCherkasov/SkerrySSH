package app.skerry.ui.share

import app.skerry.shared.guard.ProductionGuardPolicy
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.session.SessionsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Joining a colleague's share from the team screen, and what the team is allowed to do with it. */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinSharedSessionTest {

    private class WatchedSession : TerminalSession {
        private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
        override val state: StateFlow<TerminalState> = _state.asStateFlow()
        override val output: Flow<ByteArray> = emptyFlow()
        override suspend fun send(data: ByteArray) = Unit
        override suspend fun resize(size: PtySize) = Unit
        override suspend fun close() { _state.value = TerminalState.Closed(cleanExit = true) }
    }

    private object NoTransport : SshTransport {
        override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
            error("a watched session never opens a connection")
    }

    private fun TestScope.sessions(): Pair<SessionsController, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var n = 0
        return SessionsController(
            newId = { "s${n++}" },
            controllerFactory = {
                ConnectionController(
                    transport = NoTransport,
                    scope = scope,
                    newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
                )
            },
        ) to scope
    }

    @Test
    fun `joining opens the watched session and brings the terminal forward`() = runTest {
        val (sessions, scope) = sessions()
        var terminalShown = false
        var boundPane: String? = null

        showWatchedSession(
            sessions = sessions,
            title = "deploy",
            subtitle = "acc-1",
            viewer = WatchedSession(),
            onPaneOpened = { boundPane = it },
            showTerminal = { terminalShown = true },
        )

        val tab = sessions.active
        assertIs<ConnectionUiState.Connected>(tab!!.focusedPane.controller.uiState)
        assertEquals(tab.focusedPane.id, boundPane, "the viewer was not bound to the pane it opened in")
        // Join is pressed on the team screen: without this the session opens behind it, and the
        // user is left looking at the team they joined from.
        assertTrue(terminalShown, "the terminal was not brought forward after joining")
        scope.cancel()
    }

    @Test
    fun `a session with no prod tag is shared with input allowed`() {
        // Settings -> Terminal "confirm warnings too" is carried in the same policy but says nothing
        // about the host: on its own it must not turn a share into watch-only.
        val warnings = ProductionGuardPolicy(production = false, confirmWarnings = true)

        assertFalse(viewersMayOnlyWatch(warnings), "a share was locked to watch-only without a prod tag")
        assertFalse(viewersMayOnlyWatch(ProductionGuardPolicy.Off))
    }

    @Test
    fun `a prod-tagged session is shared watch-only`() {
        assertTrue(viewersMayOnlyWatch(ProductionGuardPolicy(production = true)))
        assertTrue(viewersMayOnlyWatch(ProductionGuardPolicy(production = true, confirmWarnings = true)))
    }
}
