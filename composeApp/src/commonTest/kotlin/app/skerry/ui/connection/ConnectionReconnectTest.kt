@file:OptIn(ExperimentalCoroutinesApi::class)

package app.skerry.ui.connection

import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
