package app.skerry.ui.session

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.remote.FakeRemoteDesktop
import app.skerry.ui.remote.RemoteDesktopScreenState
import app.skerry.ui.remote.RemoteDesktopUiState
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.terminal.eagerPublishClock
import app.skerry.ui.vnc.VncFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two mappings onto [SessionStatus] decide the color of every status dot (tab chip, host row).
 * An inverted `cleanExit` or a swapped `reconnecting` branch compiles and shows a dropped session
 * as idle, so every branch is pinned here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionStatusTest {

    @Test
    fun `a terminal connection maps every state onto its status`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val terminal = TerminalScreenState(FakeSession(), scope, nowMillis = eagerPublishClock())

        assertEquals(SessionStatus.Idle, (null as ConnectionUiState?).asSessionStatus())
        assertEquals(SessionStatus.Idle, ConnectionUiState.Form.asSessionStatus())
        assertEquals(SessionStatus.Connecting, ConnectionUiState.Connecting.asSessionStatus())
        assertEquals(SessionStatus.Live, ConnectionUiState.Connected(terminal).asSessionStatus())
        assertEquals(SessionStatus.Failed, ConnectionUiState.Error("boom").asSessionStatus())
        scope.cancel()
    }

    @Test
    fun `a dropped shell is a failure until it exits cleanly or starts retrying`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val terminal = TerminalScreenState(FakeSession(), scope, nowMillis = eagerPublishClock())
        fun dropped(reconnecting: Boolean, cleanExit: Boolean) =
            ConnectionUiState.Disconnected(terminal, reconnecting, attempt = 1, cleanExit = cleanExit)

        assertEquals(SessionStatus.Idle, dropped(reconnecting = false, cleanExit = true).asSessionStatus())
        assertEquals(SessionStatus.Connecting, dropped(reconnecting = true, cleanExit = false).asSessionStatus())
        // Retries exhausted: the session is gone and says so, rather than fading to idle.
        assertEquals(SessionStatus.Failed, dropped(reconnecting = false, cleanExit = false).asSessionStatus())
        scope.cancel()
    }

    @Test
    fun `a remote desktop maps every state onto its status`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val screen = RemoteDesktopScreenState(FakeRemoteDesktop(), scope)

        assertEquals(SessionStatus.Idle, (null as RemoteDesktopUiState?).asSessionStatus())
        assertEquals(SessionStatus.Connecting, RemoteDesktopUiState.Connecting.asSessionStatus())
        assertEquals(SessionStatus.Live, RemoteDesktopUiState.Connected(screen).asSessionStatus())
        assertEquals(SessionStatus.Failed, RemoteDesktopUiState.Error(VncFailure.Auth).asSessionStatus())
        // No auto-reconnect on this side: a close is either the user's own exit or a drop.
        assertEquals(SessionStatus.Idle, RemoteDesktopUiState.Disconnected(screen, cleanExit = true).asSessionStatus())
        assertEquals(SessionStatus.Failed, RemoteDesktopUiState.Disconnected(screen, cleanExit = false).asSessionStatus())
        scope.cancel()
    }
}

/** A terminal session that never emits: the states above only need a screen to carry. */
private class FakeSession : TerminalSession {
    override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
    override val output: Flow<ByteArray> = emptyFlow()
    override suspend fun send(data: ByteArray) {}
    override suspend fun resize(size: PtySize) {}
    override suspend fun close() {}
}
