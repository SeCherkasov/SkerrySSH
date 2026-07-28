package app.skerry.ui.connection

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A pane watching someone else's shared session: the controller holds a terminal it did not open,
 * so the whole terminal UI renders it unchanged while none of the connection-owning machinery
 * (SFTP, reconnect, transport) applies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachedSessionTest {

    private class WatchedSession : TerminalSession {
        val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
        override val state: StateFlow<TerminalState> = _state.asStateFlow()
        val emissions = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
        override val output: Flow<ByteArray> = emissions
        val sent = mutableListOf<ByteArray>()
        var closed = false

        override suspend fun send(data: ByteArray) { sent += data }
        override suspend fun resize(size: PtySize) = Unit
        override suspend fun close() {
            closed = true
            _state.value = TerminalState.Closed(cleanExit = false)
        }
    }

    private object NoTransport : SshTransport {
        override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
            error("a watched session never opens a connection")
    }

    private fun TestScope.controller(): Pair<ConnectionController, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return ConnectionController(
            transport = NoTransport,
            scope = scope,
            newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
        ) to scope
    }

    @Test
    fun `attaching a watched session shows it as a live terminal`() = runTest {
        val watched = WatchedSession()
        val (controller, scope) = controller()

        controller.attachSession(watched)
        advanceUntilIdle()

        val state = assertIs<ConnectionUiState.Connected>(controller.uiState)
        watched.emissions.emit("hello".encodeToByteArray())
        advanceUntilIdle()
        assertTrue(state.terminal.output.contains("hello"), "watched output did not reach the screen")
        // Nothing here can open a file panel: there is no connection behind this pane.
        assertFalse(controller.supportsSftp)
        scope.cancel()
    }

    @Test
    fun `the watched session ending freezes the screen without reconnecting`() = runTest {
        val watched = WatchedSession()
        val (controller, scope) = controller()
        controller.attachSession(watched)
        advanceUntilIdle()

        watched._state.value = TerminalState.Closed(cleanExit = true)
        advanceUntilIdle()

        val closed = assertIs<ConnectionUiState.Disconnected>(controller.uiState)
        assertTrue(closed.cleanExit)
        assertFalse(closed.reconnecting, "a watched session has no credentials to reconnect with")
        scope.cancel()
    }

    @Test
    fun `closing the pane releases the watched session`() = runTest {
        val watched = WatchedSession()
        val (controller, scope) = controller()
        controller.attachSession(watched)
        advanceUntilIdle()

        controller.disconnect()
        advanceUntilIdle()

        assertTrue(watched.closed, "the relay socket stayed open after the pane was closed")
        assertEquals(ConnectionUiState.Form, controller.uiState)
        scope.cancel()
    }

    @Test
    fun `a watched pane is marked as watched until it is released`() = runTest {
        val watched = WatchedSession()
        val (controller, scope) = controller()
        assertFalse(controller.isWatched)

        controller.attachSession(watched)
        advanceUntilIdle()
        // What the toolbar reads to dim the info button: this pane shows a session it doesn't own.
        assertTrue(controller.isWatched)

        controller.disconnect()
        advanceUntilIdle()
        assertFalse(controller.isWatched, "the pane went back to the form still marked as watching")
        scope.cancel()
    }

    @Test
    fun `a watched session measures no throughput`() = runTest {
        val watched = WatchedSession()
        val (controller, scope) = controller()
        controller.attachSession(watched)
        advanceUntilIdle()

        // The status bar polls the active pane on every Connected state; the bytes of a colleague's
        // channel are not ours to count, so this asks for nothing instead of throwing at it.
        assertNull(controller.openThroughput())
        scope.cancel()
    }

    @Test
    fun `a watched session polls no host metrics`() = runTest {
        val watched = WatchedSession()
        val (controller, scope) = controller()
        controller.attachSession(watched)
        advanceUntilIdle()

        // Same for the info panel / monitor sheet: there is no connection here to run `exec` on.
        assertNull(controller.openMetrics())
        scope.cancel()
    }

    @Test
    fun `a second attach is refused so one pane never holds two sessions`() = runTest {
        val first = WatchedSession()
        val second = WatchedSession()
        val (controller, scope) = controller()
        controller.attachSession(first)
        advanceUntilIdle()

        controller.attachSession(second)
        advanceUntilIdle()

        assertFalse(second.closed)
        assertFalse(first.closed, "the live watched session was replaced behind the pane's back")
        scope.cancel()
    }
}
