@file:OptIn(ExperimentalCoroutinesApi::class)

package app.skerry.ui.connection

import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Auto-reconnect after a drop: what the loop retries, when it gives up, and what it tells the
 * user afterwards. Split from [ConnectionControllerTest], which covers connect, disconnect and
 * the live session, and shares its fixture (`target`, `controllerWith`).
 */
class ConnectionReconnectTest {
    @Test
    fun `auto-reconnect does not re-invoke onConnected`() = runTest {
        val ch1 = FakeShellChannel()
        val ch2 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(ch2))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)
        var calls = 0

        controller.connect(testTarget, SshAuth.Password("pw")) { calls++ }
        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(1, calls)

        ch1.close() // drop → auto-reconnect restores the session
        advanceUntilIdle()

        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(1, calls) // "Run on host" isn't repeated on reconnect
        scope.cancel()
    }

    @Test
    fun `clean shell exit closes the session without auto-reconnect`() = runTest {
        val channel = FakeShellChannel()
        val transport = ScriptedTransport(listOf(Result.success(FakeSshConnection(channel))))
        // Even with reconnect allowed, a normal exit does not trigger it.
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)
        controller.connect(testTarget, SshAuth.Password("pw"))
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
    fun `auto-reconnect restores Connected after the shell drops, reusing target and auth`() = runTest {
        val ch1 = FakeShellChannel()
        val ch2 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(ch2))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)
        val auth = SshAuth.Password("pw")
        controller.connect(testTarget, auth)
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        ch1.close() // server-side drop
        advanceUntilIdle()

        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(2, transport.connectCalls) // initial + one successful reconnect
        assertEquals(testTarget, transport.targets[1]) // same host
        assertEquals(auth, transport.auths[1]) // same credentials
        scope.cancel()
    }

    @Test
    fun `a failure without a message still names what threw`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(
                Result.success(FakeSshConnection(ch1)),
                Result.failure(IllegalStateException()), // no message, as several transport errors have
            ),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 1)
        controller.connect(testTarget, SshAuth.Password("pw"))
        ch1.close()
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        // Without the fallback the banner drops back to the bare "connection lost" it showed before
        // the reason was carried at all — the blind spot the field exists to close.
        assertEquals("IllegalStateException", st.lastError)
        scope.cancel()
    }

    @Test
    fun `a wrapper without its own message falls through to the cause`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(
                Result.success(FakeSshConnection(ch1)),
                // sshj's shape: the transport exception carries no message of its own and the
                // server's disconnect reason sits on the cause.
                Result.failure(IllegalStateException(null, IllegalStateException("Too many authentication failures"))),
            ),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 1)
        controller.connect(testTarget, SshAuth.Password("pw"))
        ch1.close()
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        // Naming the wrapper type would throw away the only sentence that says what happened.
        assertEquals("Too many authentication failures", st.lastError)
        scope.cancel()
    }

    @Test
    fun `a hostile disconnect reason cannot leave an empty detail`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(
                Result.success(FakeSshConnection(ch1)),
                // A server-supplied reason built only from bidi overrides: not blank by
                // String.isBlank, but nothing survives sanitising.
                Result.failure(IllegalStateException("\u202E\u202E\u2066")),
            ),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 1)
        controller.connect(testTarget, SshAuth.Password("pw"))
        ch1.close()
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        // Otherwise the banner reads "Connection lost: " with an empty tail.
        assertEquals("IllegalStateException", st.lastError)
        scope.cancel()
    }

    @Test
    fun `a disconnect reason is stripped of anything that could reorder the banner`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(
                Result.success(FakeSshConnection(ch1)),
                Result.failure(IllegalStateException("Access denied\u202E\nby policy")),
            ),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 1)
        controller.connect(testTarget, SshAuth.Password("pw"))
        ch1.close()
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        // Bidi override gone (it is not whitespace, so removing it simply closes the gap) and the
        // newline folded to a space: the banner stays one line and reads left to right.
        assertEquals("Access denied by policy", st.lastError)
        scope.cancel()
    }

    @Test
    fun `giving up on auto-reconnect keeps the last failure reason`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(
                Result.success(FakeSshConnection(ch1)),
                Result.failure(IllegalStateException("route to host lost")),
                Result.failure(IllegalStateException("auth rejected after reboot")),
            ),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 2)
        controller.connect(testTarget, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        ch1.close()
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        assertFalse(st.reconnecting)
        // Without this the banner says only "reconnect failed": the user cannot tell a dead route
        // from a rejected credential, which are two entirely different next steps.
        assertEquals("auth rejected after reboot", st.lastError)
        scope.cancel()
    }

    @Test
    fun `an in-flight reconnect carries no failure reason yet`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(
                Result.success(FakeSshConnection(ch1)),
                Result.failure(IllegalStateException("route to host lost")),
                Result.success(FakeSshConnection(FakeShellChannel())),
            ),
        )
        val backoffMs = 1_000L
        val (controller, scope) = controllerWith(
            transport,
            maxReconnectAttempts = 3,
            reconnectDelayMillis = { backoffMs },
        )
        controller.connect(testTarget, SshAuth.Password("pw"))
        ch1.close()

        // The first attempt has already failed and the second is waiting out its backoff: the loop
        // holds a reason, but it is not the verdict yet and must not surface as "connection lost".
        advanceTimeBy(backoffMs + backoffMs / 2)
        val midFlight = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(midFlight)
        assertTrue(midFlight.reconnecting)
        assertNull(midFlight.lastError)

        advanceUntilIdle()
        assertIs<ConnectionUiState.Connected>(controller.uiState) // the third attempt got through
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
        controller.connect(testTarget, SshAuth.Password("pw"))
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
    fun `container drop auto-reconnects like ssh`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(FakeShellChannel()))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)
        // A container session is carried by SSH, so a transport drop is the same kind of event as
        // on SSH: reconnecting re-execs into the container (a fresh shell, like a new SSH shell).
        val containerTarget = SshTarget(
            host = "h", port = 22, username = "ops", connectionType = ConnectionType.CONTAINER,
            container = app.skerry.shared.container.ContainerSpec(target = "web"),
        )
        controller.connect(containerTarget, SshAuth.Password("pw"))
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        ch1.close() // server-side drop
        advanceUntilIdle()

        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(2, transport.connectCalls)
        scope.cancel()
    }

    @Test
    fun `disconnect during reconnect cancels further attempts and returns to Form`() = runTest {
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(FakeShellChannel()))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 5, reconnectDelayMillis = { 1_000L })
        controller.connect(testTarget, SshAuth.Password("pw"))
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
        controller.connect(testTarget, SshAuth.Password("pw"))
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
    fun `unanswered keep-alives force the drop path and auto-reconnect`() = runTest {
        val ch1 = FakeShellChannel()
        val conn1 = FakeSshConnection(ch1).apply { roundTripResult = null } // dead link from the start
        val conn2 = FakeSshConnection(FakeShellChannel())
        val transport = ScriptedTransport(listOf(Result.success(conn1), Result.success(conn2)))
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)

        controller.connect(testTarget.copy(keepAliveSeconds = 30), SshAuth.Password("pw"))
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
    fun `auto-reconnect resumes keep-alive on the new connection`() = runTest {
        val ch1 = FakeShellChannel()
        val conn1 = FakeSshConnection(ch1)
        val conn2 = FakeSshConnection(FakeShellChannel())
        val transport = ScriptedTransport(listOf(Result.success(conn1), Result.success(conn2)))
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3)

        controller.connect(testTarget.copy(keepAliveSeconds = 30), SshAuth.Password("pw"))
        assertEquals(1, conn1.roundTrips)

        ch1.close() // drop -> zero-backoff reconnect (the interval rides in lastTarget)
        advanceTimeBy(1_000)

        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(1, conn1.roundTrips) // old loop stopped with the old session
        assertEquals(1, conn2.roundTrips) // new session pings immediately again
        scope.cancel()
    }

    /**
     * A drop and the user closing the pane are handled by two different threads: the loss lands on
     * the controller's scope, the click on the UI one. [ConnectionController.onSessionLost] read
     * "still Connected" and then wrote [ConnectionUiState.Disconnected] without holding the lock
     * [disconnect] writes [ConnectionUiState.Form] under, so a click that landed in between was
     * undone — and [ConnectionController.connect] only ever starts from Form, so the pane could not
     * be brought back at all, silently (issue #353: how the desktop toolbar test flaked on CI).
     *
     * Rounds rather than one attempt, because the window is the width of one teardown; the fix
     * closes it, so a green run here is not luck.
     */
    @Test
    fun `a drop racing an explicit disconnect leaves the form ready to connect`() {
        // Real threads on purpose: with the loss handler and the disconnect serialized onto one
        // dispatcher the Connected guard alone is enough, and the defect cannot be staged at all.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            repeat(RACE_ROUNDS) { round ->
                val dropped = FakeShellChannel()
                val transport = ScriptedTransport(
                    listOf(
                        Result.success(FakeSshConnection(dropped)),
                        Result.success(FakeSshConnection(FakeShellChannel())),
                    ),
                )
                val controller = ConnectionController(transport, scope, maxReconnectAttempts = 0)
                controller.connect(testTarget, SshAuth.Password("pw"))
                controller.awaitState<ConnectionUiState.Connected>("round $round: the first connect")

                dropped.drop() // the transport drops — handled on the controller's scope
                controller.disconnect() // ...while the user closes the pane

                controller.holdState<ConnectionUiState.Form>("round $round: the closed pane")
                controller.connect(testTarget, SshAuth.Password("pw"))
                controller.awaitState<ConnectionUiState.Connected>("round $round: the connect after the drop")
                controller.disconnect()
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * The vault lock ends a mid-flight reconnect — the credentials it would use are gone, so no
     * path can bring that session back. The attempt in flight has to end with it: past its last
     * cancellation check it would otherwise publish a live session whose credentials were just
     * wiped, on a pane the lock had already stood down. Staged through [newSessionScope], as above.
     */
    @Test
    fun `a reconnect finishing after the vault locked leaves nothing open`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dropped = FakeShellChannel()
        val reconnected = FakeSshConnection(FakeShellChannel())
        val reachedWindow = CompletableDeferred<Unit>()
        val leaveWindow = CompletableDeferred<Unit>()
        try {
            var sessions = 0
            val controller = ConnectionController(
                ScriptedTransport(listOf(Result.success(FakeSshConnection(dropped)), Result.success(reconnected))),
                scope,
                newSessionScope = {
                    // Only the reconnect's own handshake is held; the first connect must complete.
                    if (sessions++ > 0) {
                        reachedWindow.complete(Unit)
                        while (!leaveWindow.isCompleted) Thread.sleep(1)
                    }
                    CoroutineScope(SupervisorJob() + Dispatchers.Default)
                },
                maxReconnectAttempts = 3,
                reconnectDelayMillis = { 0L },
            )

            controller.connect(testTarget, SshAuth.Password("pw"))
            controller.awaitState<ConnectionUiState.Connected>("the first connect")
            dropped.drop() // the transport drops → the reconnect attempt reaches the window
            awaitTrue("the reconnect to reach the publication") { reachedWindow.isCompleted }

            controller.clearReconnectCredentials() // the vault locks
            leaveWindow.complete(Unit)

            controller.holdState<ConnectionUiState.Disconnected>("the stood-down pane")
            assertFalse(
                (controller.uiState as ConnectionUiState.Disconnected).reconnecting,
                "the pane must not claim it is reconnecting after the lock",
            )
            awaitTrue("the reconnected session to be released") { reconnected.disconnected }
        } finally {
            leaveWindow.complete(Unit)
            scope.cancel()
        }
    }

    /**
     * The same defect on the connect side, staged rather than raced. Cancelling the connect job
     * cannot stop a handshake that is already past its last `ensureActive` — everything from there
     * to the transition is synchronous — so the pane the user has just closed came back Connected
     * over a session whose teardown had already run, holding a connection nobody would ever close.
     *
     * [newSessionScope] is the hook: it is called inside exactly that window, so blocking in it
     * puts the disconnect where the race would have put it, every run.
     */
    @Test
    fun `a handshake finishing after the pane was closed leaves nothing open`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = FakeSshConnection(FakeShellChannel())
        val reachedWindow = CompletableDeferred<Unit>()
        val leaveWindow = CompletableDeferred<Unit>()
        try {
            val controller = ConnectionController(
                ScriptedTransport(listOf(Result.success(connection))),
                scope,
                newSessionScope = {
                    reachedWindow.complete(Unit)
                    while (!leaveWindow.isCompleted) Thread.sleep(1)
                    CoroutineScope(SupervisorJob() + Dispatchers.Default)
                },
                maxReconnectAttempts = 0,
            )

            controller.connect(testTarget, SshAuth.Password("pw"))
            awaitTrue("the handshake to reach the publication") { reachedWindow.isCompleted }
            controller.disconnect() // the user closes the pane; the handshake cannot be cancelled now
            leaveWindow.complete(Unit)

            controller.holdState<ConnectionUiState.Form>("the pane closed mid-handshake")
            awaitTrue("the connection to be released") { connection.disconnected }
        } finally {
            leaveWindow.complete(Unit)
            scope.cancel()
        }
    }

    /**
     * The tail of the retry loop is the same trap once more: nothing suspends between the last
     * attempt's failure and the give-up write, so cancelling the job cannot stop it. It used to put
     * [ConnectionUiState.Disconnected] — "reconnect failed" — over the [ConnectionUiState.Form] the
     * user's close had already set, leaving the pane in the state [ConnectionController.connect]
     * refuses to start from. The transport blocks without suspending, so the close lands exactly
     * there on every run.
     */
    @Test
    fun `a reconnect giving up after the pane was closed leaves the form alone`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dropped = FakeShellChannel()
        val reachedRetry = CompletableDeferred<Unit>()
        val leaveRetry = CompletableDeferred<Unit>()
        try {
            val controller = ConnectionController(
                GatedRetryTransport(FakeSshConnection(dropped), reachedRetry, leaveRetry),
                scope,
                maxReconnectAttempts = 1, // one attempt, so its failure is the verdict
                reconnectDelayMillis = { 0L },
            )

            controller.connect(testTarget, SshAuth.Password("pw"))
            controller.awaitState<ConnectionUiState.Connected>("the first connect")
            dropped.drop() // the transport drops → the only retry starts and blocks
            awaitTrue("the last reconnect attempt to start") { reachedRetry.isCompleted }

            controller.disconnect() // the user closes the pane while that attempt is doomed but alive
            leaveRetry.complete(Unit)

            controller.holdState<ConnectionUiState.Form>("the pane closed during the last attempt")
            // The hold alone would also pass on a runner slow enough that the stale write simply
            // had not landed yet, so the verdict is what the user does next: connecting again must
            // get through and stay through. A stale "reconnect failed" landing before this leaves a
            // pane connect refuses to start from; landing after it, it overwrites a live session.
            controller.connect(testTarget, SshAuth.Password("pw"))
            controller.awaitState<ConnectionUiState.Connected>("the connect after the closed pane")
            controller.holdState<ConnectionUiState.Connected>("the pane connected again")
        } finally {
            leaveRetry.complete(Unit)
            scope.cancel()
        }
    }
}

/**
 * Succeeds, then blocks the retry inside `connect` — without suspending, so cancelling the
 * reconnect job cannot stop it — and fails that attempt when released. Every connect after it
 * succeeds again, so a test can ask whether the pane still works afterwards.
 */
private class GatedRetryTransport(
    private val first: SshConnection,
    private val reachedRetry: CompletableDeferred<Unit>,
    private val leaveRetry: CompletableDeferred<Unit>,
) : SshTransport {
    private var calls = 0

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        when (calls++) {
            0 -> return first
            1 -> {
                reachedRetry.complete(Unit)
                while (!leaveRetry.isCompleted) Thread.sleep(1)
                error("route to host lost")
            }
            else -> return FakeSshConnection(FakeShellChannel())
        }
    }
}

/**
 * Rounds of the races above. The window is one teardown wide, so a handful of rounds already hits it
 * on a loaded machine; this many keeps it honest on an idle one. Costs a few seconds per test.
 */
private const val RACE_ROUNDS = 200

/** Spins until [uiState] is [T], naming what was waited for and what it actually holds. */
private inline fun <reified T> ConnectionController.awaitState(what: String, timeout: Duration = 5.seconds) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (uiState !is T) {
        if (deadline.hasPassedNow()) {
            fail("$what never reached ${T::class.simpleName}: uiState=${uiState::class.simpleName}")
        }
        // Parks rather than spins: the work being waited for runs on a pool that a busy loop on
        // a small CI runner would starve — the very condition these tests exist for.
        Thread.sleep(1)
    }
}

/** Spins until [condition] holds, naming what was waited for. */
private fun awaitTrue(what: String, timeout: Duration = 5.seconds, condition: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (!condition()) {
        if (deadline.hasPassedNow()) fail("$what never happened")
        Thread.sleep(1)
    }
}

/**
 * Fails if [uiState] leaves [T] within [window]. The assertion has to be a held one: the handler
 * that used to overwrite the state runs on another thread and may not have landed yet.
 */
private inline fun <reified T> ConnectionController.holdState(what: String, window: Duration = 10.milliseconds) {
    val deadline = TimeSource.Monotonic.markNow() + window
    while (!deadline.hasPassedNow()) {
        val state = uiState
        if (state !is T) fail("$what left ${T::class.simpleName} for ${state::class.simpleName}")
        Thread.sleep(1)
    }
}
