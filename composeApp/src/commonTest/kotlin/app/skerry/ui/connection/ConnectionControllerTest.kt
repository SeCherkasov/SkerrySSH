package app.skerry.ui.connection

import app.skerry.shared.files.FileContentBrowser
import app.skerry.shared.files.FileItem
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.mosh.MoshSetupException
import app.skerry.shared.sftp.SftpProgress
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshAuthenticationException
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionControllerTest {

    private val target = SshTarget(host = "h", port = 22, username = "u")

    private fun TestScope.controllerWith(
        transport: SshTransport,
        maxReconnectAttempts: Int = 0,
        // No backoff by default (determinism). The reconnect-cancellation test sets a nonzero delay
        // so the attempt actually "hangs" on delay and can be cancelled (delay(0) doesn't suspend).
        reconnectDelayMillis: (Int) -> Long = { 0L },
    ): Pair<ConnectionController, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = ConnectionController(
            transport = transport,
            scope = scope,
            newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
            maxReconnectAttempts = maxReconnectAttempts,
            reconnectDelayMillis = reconnectDelayMillis,
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
    fun `connect invokes onConnected once with the live terminal`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        var calls = 0
        var received: Any? = null

        controller.connect(target, SshAuth.Password("pw")) { t -> calls++; received = t }

        val state = controller.uiState
        assertIs<ConnectionUiState.Connected>(state)
        assertEquals(1, calls)
        assertSame(state.terminal, received)
        scope.cancel()
    }

    @Test
    fun `auto-reconnect does not re-invoke onConnected`() = runTest {
        val ch1 = FakeShellChannel()
        val ch2 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(ch2))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)
        var calls = 0

        controller.connect(target, SshAuth.Password("pw")) { calls++ }
        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(1, calls)

        ch1.close() // drop → auto-reconnect restores the session
        advanceUntilIdle()

        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(1, calls) // "Run on host" isn't repeated on reconnect
        scope.cancel()
    }

    @Test
    fun `connect failure transitions to Error with message`() = runTest {
        val transport = FakeSshTransport(error = SshAuthenticationException("access denied"))
        val (controller, scope) = controllerWith(transport)

        controller.connect(target, SshAuth.Password("pw"))

        val state = controller.uiState
        assertIs<ConnectionUiState.Error>(state)
        assertEquals("access denied", state.message)
        assertNull(state.moshReason)
        scope.cancel()
    }

    @Test
    fun `mosh setup failure carries the typed reason into Error`() = runTest {
        val transport = FakeSshTransport(
            error = MoshSetupException(
                reason = MoshSetupException.Reason.SERVER_NOT_INSTALLED,
                message = "mosh-server was not found",
            ),
        )
        val (controller, scope) = controllerWith(transport)

        controller.connect(target, SshAuth.Password("pw"))

        val state = controller.uiState
        assertIs<ConnectionUiState.Error>(state)
        assertEquals(MoshSetupException.Reason.SERVER_NOT_INSTALLED, state.moshReason)
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
    fun `losing the shell transitions Connected to Disconnected keeping the frozen terminal`() = runTest {
        val channel = FakeShellChannel()
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(channel)))
        controller.connect(target, SshAuth.Password("pw"))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        // Server closed the channel / transport drop (not our disconnect): no EOF ends output collection.
        channel.close()

        val lost = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(lost)
        // The screen freezes at the moment of loss — it's the same TerminalScreenState as Connected had.
        assertSame(connected.terminal, lost.terminal)
        scope.cancel()
    }

    @Test
    fun `clean shell exit closes the session without auto-reconnect`() = runTest {
        val channel = FakeShellChannel()
        val transport = ScriptedTransport(listOf(Result.success(FakeSshConnection(channel))))
        // Even with reconnect allowed, a normal exit does not trigger it.
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)
        controller.connect(target, SshAuth.Password("pw"))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        channel.exit() // user exited the shell themselves (`exit`): EOF, not a drop
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        assertTrue(st.cleanExit)
        assertFalse(st.reconnecting)
        assertSame(connected.terminal, st.terminal) // screen froze on the final output (logout)
        assertEquals(1, transport.connectCalls) // no reconnect attempts
        scope.cancel()
    }

    @Test
    fun `our disconnect goes to Form and never flips to Disconnected on channel close`() = runTest {
        val channel = FakeShellChannel()
        val conn = FakeSshConnection(channel)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        controller.disconnect() // cancels session-scope before Closed would have been observed
        channel.close()

        assertEquals(ConnectionUiState.Form, controller.uiState)
        scope.cancel()
    }

    @Test
    fun `auto-reconnect restores Connected after the shell drops, reusing target and auth`() = runTest {
        val ch1 = FakeShellChannel()
        val ch2 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(ch2))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)
        val auth = SshAuth.Password("pw")
        controller.connect(target, auth)
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        ch1.close() // server-side drop
        advanceUntilIdle()

        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(2, transport.connectCalls) // initial + one successful reconnect
        assertEquals(target, transport.targets[1]) // same host
        assertEquals(auth, transport.auths[1]) // same credentials
        scope.cancel()
    }

    @Test
    fun `auto-reconnect gives up after the attempt limit and stays Disconnected`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(
                Result.success(FakeSshConnection(ch1)),
                Result.failure(IllegalStateException("down")),
                Result.failure(IllegalStateException("down")),
            ),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 2)
        controller.connect(target, SshAuth.Password("pw"))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        ch1.close()
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        assertFalse(st.reconnecting) // attempts exhausted
        assertSame(connected.terminal, st.terminal) // screen stayed frozen
        assertEquals(3, transport.connectCalls) // 1 initial + 2 failed attempts
        scope.cancel()
    }

    @Test
    fun `non-SSH drop does not auto-reconnect`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            // The second attempt must NOT happen — there's no reconnect for Telnet/Serial.
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(FakeShellChannel()))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)
        val telnetTarget = SshTarget(host = "h", port = 23, username = "", connectionType = ConnectionType.TELNET)
        controller.connect(telnetTarget, SshAuth.Password(""))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        ch1.close() // server-side drop
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        assertFalse(st.reconnecting) // no auto-reconnect for Telnet/Serial
        assertSame(connected.terminal, st.terminal) // screen stayed frozen
        assertEquals(1, transport.connectCalls) // only the initial connect, no reconnect attempts
        scope.cancel()
    }

    @Test
    fun `disconnect during reconnect cancels further attempts and returns to Form`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(FakeShellChannel()))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 5, reconnectDelayMillis = { 1_000L })
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        ch1.close() // triggers reconnect (hangs on the backoff delay until advance)
        controller.disconnect() // cancel it before it reaches the second attempt
        advanceUntilIdle()

        assertEquals(ConnectionUiState.Form, controller.uiState)
        assertEquals(1, transport.connectCalls) // second attempt never happened
        scope.cancel()
    }

    @Test
    fun `clearReconnectCredentials stops auto-reconnect after a drop without killing the live session`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(FakeShellChannel()))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 5)
        controller.connect(target, SshAuth.Password("pw"))
        val connected = controller.uiState
        assertIs<ConnectionUiState.Connected>(connected)

        controller.clearReconnectCredentials() // lock vault: reconnect disabled, session still alive
        assertIs<ConnectionUiState.Connected>(controller.uiState) // socket untouched

        ch1.close() // drop happens with the vault already locked
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        assertFalse(st.reconnecting) // no stored credentials → no reconnect
        assertEquals(1, transport.connectCalls) // no re-authentication happened
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

        // A repeated connect from Connected must not open a second shell/session.
        controller.connect(target, SshAuth.Password("pw"))

        assertEquals(connected, controller.uiState)
        assertEquals(1, conn.openShellCalls)
        scope.cancel()
    }

    @Test
    fun `openSftp opens a channel on the live connection`() = runTest {
        val sftp = RecordingSftpClient()
        val conn = FakeSshConnection(FakeShellChannel(), sftp = sftp)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        val opened = controller.openSftp()

        assertSame(sftp, opened)
        scope.cancel()
    }

    @Test
    fun `openSftp without a live connection fails`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)

        assertFailsWith<IllegalStateException> { controller.openSftp() }
        scope.cancel()
    }

    @Test
    fun `openTransferCoordinator caches one coordinator and opens a single channel`() = runTest {
        val sftp = RecordingSftpClient()
        val conn = FakeSshConnection(FakeShellChannel(), sftp = sftp)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        // Cached per connection: the dual-pane SFTP survives view switches, so a repeated
        // call returns the same coordinator and doesn't open a second channel.
        val first = controller.openTransferCoordinator(FakeFileBrowser(), "host")
        val second = controller.openTransferCoordinator(FakeFileBrowser(), "host")

        assertSame(first, second)
        assertEquals(1, conn.openSftpCalls)
        scope.cancel()
    }

    @Test
    fun `openTransferCoordinator without a live connection fails`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)

        assertFailsWith<IllegalStateException> { controller.openTransferCoordinator(FakeFileBrowser(), "host") }
        scope.cancel()
    }

    @Test
    fun `disconnect closes the opened sftp channel`() = runTest {
        val sftp = RecordingSftpClient()
        val conn = FakeSshConnection(FakeShellChannel(), sftp = sftp)
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        controller.openTransferCoordinator(FakeFileBrowser(), "host")
        assertTrue(!sftp.closed)

        controller.disconnect()

        assertTrue(sftp.closed)
        scope.cancel()
    }

    @Test
    fun `openPortForwards returns the same controller for one session`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        // Cached per connection: tunnels must survive UI tab switches, so every
        // openPortForwards call returns the same controller instead of a new one.
        val first = controller.openPortForwards()
        val second = controller.openPortForwards()

        assertSame(first, second)
        scope.cancel()
    }

    @Test
    fun `openPortForwards without a live connection fails`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)

        assertFailsWith<IllegalStateException> { controller.openPortForwards() }
        scope.cancel()
    }

    @Test
    fun `openMetrics caches one controller for one session`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        // Cached per connection: the info panel survives tab switches, a repeated call returns
        // the same controller (and the same polling job) instead of spinning up a second one.
        val first = controller.openMetrics()
        val second = controller.openMetrics()

        assertSame(first, second)
        controller.disconnect()
        scope.cancel()
    }

    @Test
    fun `disconnect stops the metrics poller`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        controller.openMetrics()
        testScheduler.advanceTimeBy(1)
        val pollsWhileConnected = conn.execCalls

        controller.disconnect()
        testScheduler.advanceTimeBy(30_000)

        // The poller lives on the session scope: dropping the session must end the round-trips,
        // not leave a loop polling a dead connection.
        assertEquals(pollsWhileConnected, conn.execCalls)
        scope.cancel()
    }

    @Test
    fun `openMetrics without a live connection fails`() = runTest {
        val (controller, scope) = controllerWith(FakeSshTransport(FakeSshConnection(FakeShellChannel())))
        assertEquals(ConnectionUiState.Form, controller.uiState)

        assertFailsWith<IllegalStateException> { controller.openMetrics() }
        scope.cancel()
    }

    // Keep-alive: SshTarget.keepAliveSeconds > 0 starts a keepalive ping loop with the session
    // itself (not lazily from the status bar), so an idle session behind a NAT stays alive.
    // These tests advance virtual time explicitly (advanceTimeBy) — advanceUntilIdle would never
    // return with a periodic loop running.

    @Test
    fun `keep-alive pings from connect at the target interval`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))

        controller.connect(target.copy(keepAliveSeconds = 30), SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        assertEquals(1, conn.roundTrips) // first ping fires immediately on connect
        advanceTimeBy(65_000) // two more cycles land at t=30s and t=60s
        assertEquals(3, conn.roundTrips)
        scope.cancel()
    }

    @Test
    fun `keep-alive off never pings and openPing exposes nothing`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))

        controller.connect(target, SshAuth.Password("pw")) // default keepAliveSeconds = 0
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        advanceTimeBy(120_000)
        assertEquals(0, conn.roundTrips)
        assertNull(controller.openPing()) // no poller -> no RTT for the status bar
        scope.cancel()
    }

    @Test
    fun `openPing exposes the running keep-alive poller with its RTT`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))

        controller.connect(target.copy(keepAliveSeconds = 30), SshAuth.Password("pw"))

        val ping = controller.openPing()
        assertNotNull(ping)
        assertEquals(7L, ping.rttMs) // published by the immediate first ping
        assertSame(ping, controller.openPing())
        scope.cancel()
    }

    @Test
    fun `disconnect stops keep-alive pings`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target.copy(keepAliveSeconds = 30), SshAuth.Password("pw"))
        advanceTimeBy(35_000)
        assertEquals(2, conn.roundTrips)

        controller.disconnect()
        advanceTimeBy(120_000)

        assertEquals(2, conn.roundTrips)
        scope.cancel()
    }

    @Test
    fun `disconnect stops the port-forward telemetry poller`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))
        controller.connect(target, SshAuth.Password("pw"))
        controller.openPortForwards()

        controller.disconnect()

        // The forward controller polls on the shared controller scope (it outlives the session), so
        // disconnect must cancel the poll job — otherwise every closed session leaks a live loop.
        // Check before cancel, but cancel unconditionally: a leaked poller re-schedules forever and
        // would hang runTest's idle-wait.
        val leaked = scope.coroutineContext[Job]!!.children.any { it.isActive }
        scope.cancel()
        assertFalse(leaked)
    }

    @Test
    fun `unanswered keep-alives force the drop path and auto-reconnect`() = runTest {
        val ch1 = FakeShellChannel()
        val conn1 = FakeSshConnection(ch1).apply { roundTripResult = null } // dead link from the start
        val conn2 = FakeSshConnection(FakeShellChannel())
        val transport = ScriptedTransport(listOf(Result.success(conn1), Result.success(conn2)))
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)

        controller.connect(target.copy(keepAliveSeconds = 30), SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        // Failures at t=0/30/60s reach the death threshold: the channel is force-closed and the
        // loss flows through the regular drop path into auto-reconnect — no waiting for a TCP
        // timeout on a frozen terminal.
        advanceTimeBy(65_000)

        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(2, transport.connectCalls) // reconnected to the healthy session
        assertTrue(conn1.disconnected) // the dead connection was torn down
        scope.cancel()
    }

    @Test
    fun `a throwing onConnected action stops the started keep-alive`() = runTest {
        val conn = FakeSshConnection(FakeShellChannel())
        val (controller, scope) = controllerWith(FakeSshTransport(conn))

        controller.connect(target.copy(keepAliveSeconds = 30), SshAuth.Password("pw")) { error("boom") }

        assertIs<ConnectionUiState.Error>(controller.uiState)
        assertTrue(conn.disconnected) // full session teardown, not just the ping loop
        val before = conn.roundTrips
        advanceTimeBy(120_000)
        assertEquals(before, conn.roundTrips) // loop died with the failed session
        assertNull(controller.openPing())
        scope.cancel()
    }

    @Test
    fun `auto-reconnect resumes keep-alive on the new connection`() = runTest {
        val ch1 = FakeShellChannel()
        val conn1 = FakeSshConnection(ch1)
        val conn2 = FakeSshConnection(FakeShellChannel())
        val transport = ScriptedTransport(listOf(Result.success(conn1), Result.success(conn2)))
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)

        controller.connect(target.copy(keepAliveSeconds = 30), SshAuth.Password("pw"))
        assertEquals(1, conn1.roundTrips)

        ch1.close() // drop -> zero-backoff reconnect (the interval rides in lastTarget)
        advanceTimeBy(1_000)

        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(1, conn1.roundTrips) // old loop stopped with the old session
        assertEquals(1, conn2.roundTrips) // new session pings immediately again
        scope.cancel()
    }

}

/**
 * Transport that returns a predefined sequence of outcomes (success/failure) — one per
 * [connect] call. Counts calls and records targets/credentials for reconnect verification.
 */
private class ScriptedTransport(private val outcomes: List<Result<SshConnection>>) : SshTransport {
    var connectCalls = 0
        private set
    val targets = mutableListOf<SshTarget>()
    val auths = mutableListOf<SshAuth>()

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        val index = connectCalls++
        targets += target
        auths += auth
        return outcomes[index].getOrThrow()
    }
}

/** Fake transport: optional delay via a gate, otherwise success or failure. */
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

private class FakeSshConnection(
    private val channel: ShellChannel,
    private val sftp: SftpClient? = null,
) : SshConnection {
    var disconnected = false
        private set
    var openSftpCalls = 0
        private set
    var roundTrips = 0
        private set

    /** Scripted ping outcome: `null` models a dead link (keepalives unanswered). */
    var roundTripResult: Long? = 7L

    override val isConnected: Boolean get() = !disconnected
    override suspend fun measureRoundTrip(): Long? {
        roundTrips++
        return roundTripResult
    }
    /** Metrics polling round-trips: parsable output, so the poller keeps running while connected. */
    var execCalls = 0
    override suspend fun exec(command: String): ExecResult {
        execCalls++
        return ExecResult(0, "cpu  1 0 1 8 0 0 0 0\n@MEM\nMem: 400 200 200\n@DISK\n/dev/sda1 100 87 13 87% /", "")
    }
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = channel
    override suspend fun openSftp(): SftpClient {
        openSftpCalls++
        return sftp ?: throw UnsupportedOperationException()
    }
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun disconnect() {
        disconnected = true
    }
}

/** SFTP client stub: only object identity and the closed flag matter. */
private class RecordingSftpClient : SftpClient {
    var closed = false
        private set

    override suspend fun list(path: String): List<SftpEntry> = emptyList()
    override suspend fun stat(path: String): SftpEntry? = null
    override suspend fun realpath(path: String): String = "/"
    override suspend fun read(path: String, maxBytes: Long): ByteArray = ByteArray(0)
    override suspend fun write(path: String, data: ByteArray) = Unit
    override suspend fun download(remotePath: String, localPath: String, onProgress: SftpProgress) = Unit
    override suspend fun upload(localPath: String, remotePath: String, onProgress: SftpProgress) = Unit
    override suspend fun mkdir(path: String) = Unit
    override suspend fun remove(path: String) = Unit
    override suspend fun rmdir(path: String) = Unit
    override suspend fun rename(from: String, to: String) = Unit
    override suspend fun close() {
        closed = true
    }
}

/** Local file browser stub for the coordinator's left pane: only identity matters. */
private class FakeFileBrowser : FileContentBrowser {
    override val label: String = "local"
    override suspend fun realpath(path: String): String = "/"
    override suspend fun list(path: String): List<FileItem> = emptyList()
    override suspend fun mkdir(path: String) = Unit
    override suspend fun delete(item: FileItem) = Unit
    override suspend fun rename(from: String, to: String) = Unit
    override suspend fun stat(path: String): FileItem? = null
    override suspend fun readFile(path: String, maxBytes: Long): ByteArray = ByteArray(0)
    override suspend fun writeFile(path: String, data: ByteArray) = Unit
}

/** Counts openShell calls — verifies a repeated connect doesn't open a second shell. */
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
    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward = throw UnsupportedOperationException()
    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward = throw UnsupportedOperationException()

    override suspend fun disconnect() {}
}

private class FakeShellChannel : ShellChannel {
    private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
    private var eof = false
    override val isOpen: Boolean = true
    override val endedWithEof: Boolean get() = eof
    override val output: Flow<ByteArray> = flow { for (chunk in emissions) emit(chunk) }

    suspend fun emit(chunk: ByteArray) {
        emissions.send(chunk)
    }

    /** Normal shell exit (`exit`): channel EOF — endedWithEof=true. */
    fun exit() {
        eof = true
        emissions.close()
    }

    override suspend fun write(data: ByteArray) {}
    override suspend fun resize(size: PtySize) {}
    /** Server/transport-side drop: the channel ends WITHOUT EOF (reconnect candidate). */
    override suspend fun close() {
        emissions.close()
    }
}
