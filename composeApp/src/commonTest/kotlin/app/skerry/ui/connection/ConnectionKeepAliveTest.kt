@file:OptIn(ExperimentalCoroutinesApi::class)

package app.skerry.ui.connection

import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.ui.keepalive.SessionKeepAliveBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The platform keep-alive bridge contract: the bridge learns a session exists when it becomes
 * usable and learns it is gone only when the controller has truly given it up — an intentional
 * close, a clean shell exit, or auto-reconnect being exhausted. A transient drop that enters
 * auto-reconnect must NOT be reported as ended: on Android that would stop the foreground
 * service, and restarting it from the background is forbidden (Android 12+), killing the
 * reconnect the feature exists to protect.
 */
class ConnectionKeepAliveTest {

    private class RecordingBridge : SessionKeepAliveBridge {
        val events = mutableListOf<String>()
        override fun onSessionStarted(sessionId: String, hostLabel: String) {
            events += "start:$sessionId:$hostLabel"
        }
        override fun onSessionEnded(sessionId: String) {
            events += "end:$sessionId"
        }
    }

    @Test
    fun `connect reports the session started under its bound id`() = runTest {
        val bridge = RecordingBridge()
        val transport = ScriptedTransport(listOf(Result.success(FakeSshConnection(FakeShellChannel()))))
        val (controller, scope) = controllerWith(transport, keepAlive = bridge)
        controller.bindSessionId("sess-7")

        controller.connect(testTarget, SshAuth.Password("pw"))

        assertEquals(listOf("start:sess-7:h"), bridge.events)
        scope.cancel()
    }

    @Test
    fun `disconnect reports the session ended exactly once`() = runTest {
        val bridge = RecordingBridge()
        val transport = ScriptedTransport(listOf(Result.success(FakeSshConnection(FakeShellChannel()))))
        val (controller, scope) = controllerWith(transport, keepAlive = bridge)
        controller.bindSessionId("sess-1")
        controller.connect(testTarget, SshAuth.Password("pw"))

        controller.disconnect()

        assertEquals(listOf("start:sess-1:h", "end:sess-1"), bridge.events)
        scope.cancel()
    }

    @Test
    fun `a drop entering auto-reconnect never reports the session ended`() = runTest {
        val bridge = RecordingBridge()
        val ch1 = FakeShellChannel()
        val ch2 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(ch2))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3, keepAlive = bridge)
        controller.bindSessionId("sess-1")
        controller.connect(testTarget, SshAuth.Password("pw"))

        ch1.close() // transport drop → auto-reconnect restores the session
        advanceUntilIdle()

        assertIs<ConnectionUiState.Connected>(controller.uiState)
        // The re-established session re-announces itself (idempotent on the platform side), but
        // "ended" must never appear — the service would stop and could not restart from background.
        assertEquals(listOf("start:sess-1:h", "start:sess-1:h"), bridge.events)
        scope.cancel()
    }

    @Test
    fun `clean shell exit reports the session ended`() = runTest {
        val bridge = RecordingBridge()
        val channel = FakeShellChannel()
        val transport = ScriptedTransport(listOf(Result.success(FakeSshConnection(channel))))
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3, keepAlive = bridge)
        controller.bindSessionId("sess-1")
        controller.connect(testTarget, SshAuth.Password("pw"))

        channel.exit() // `exit` in the shell: EOF, no reconnect
        advanceUntilIdle()

        assertEquals(listOf("start:sess-1:h", "end:sess-1"), bridge.events)
        scope.cancel()
    }

    @Test
    fun `exhausted auto-reconnect reports the session ended once`() = runTest {
        val bridge = RecordingBridge()
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(
                Result.success(FakeSshConnection(ch1)),
                Result.failure(RuntimeException("route died")),
                Result.failure(RuntimeException("route died")),
            ),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 2, keepAlive = bridge)
        controller.bindSessionId("sess-1")
        controller.connect(testTarget, SshAuth.Password("pw"))

        ch1.close()
        advanceUntilIdle()

        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        assertFalse(st.reconnecting)
        assertEquals(listOf("start:sess-1:h", "end:sess-1"), bridge.events)
        scope.cancel()
    }

    @Test
    fun `a failed initial connect reports nothing`() = runTest {
        val bridge = RecordingBridge()
        val transport = ScriptedTransport(listOf(Result.failure(RuntimeException("no route"))))
        val (controller, scope) = controllerWith(transport, keepAlive = bridge)
        controller.bindSessionId("sess-1")

        controller.connect(testTarget, SshAuth.Password("pw"))
        advanceUntilIdle()

        assertIs<ConnectionUiState.Error>(controller.uiState)
        assertEquals(emptyList(), bridge.events)
        scope.cancel()
    }

    @Test
    fun `a vault lock during auto-reconnect reports the session ended`() = runTest {
        val bridge = RecordingBridge()
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(FakeShellChannel()))),
        )
        // Nonzero backoff so the retry is suspended in delay() when the lock lands.
        val (controller, scope) = controllerWith(
            transport,
            maxReconnectAttempts = 3,
            reconnectDelayMillis = { 60_000L },
            keepAlive = bridge,
        )
        controller.bindSessionId("sess-1")
        controller.connect(testTarget, SshAuth.Password("pw"))

        ch1.close() // drop → reconnect window opens
        advanceTimeBy(1) // let the loss dispatch, but not the 60s backoff
        assertIs<ConnectionUiState.Disconnected>(controller.uiState)

        controller.clearReconnectCredentials() // vault lock kills the retry, credentials are gone
        advanceUntilIdle()

        // No path can bring this session back — the keep-alive must be retracted, and the pane
        // must stop claiming it is reconnecting.
        assertEquals(listOf("start:sess-1:h", "end:sess-1"), bridge.events)
        val st = controller.uiState
        assertIs<ConnectionUiState.Disconnected>(st)
        assertFalse(st.reconnecting)
        scope.cancel()
    }

    @Test
    fun `a vault lock after a successful auto-reconnect keeps the keep-alive`() = runTest {
        val bridge = RecordingBridge()
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(
            listOf(Result.success(FakeSshConnection(ch1)), Result.success(FakeSshConnection(FakeShellChannel()))),
        )
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3, keepAlive = bridge)
        controller.bindSessionId("sess-1")
        controller.connect(testTarget, SshAuth.Password("pw"))

        ch1.close() // drop → reconnect succeeds, session is live again
        advanceUntilIdle()
        assertIs<ConnectionUiState.Connected>(controller.uiState)

        controller.clearReconnectCredentials() // lock AFTER the reconnect finished

        // The reconnect is over, the session is live: the lock must not retract its keep-alive —
        // on Android that would stop the foreground service under an open connection.
        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(listOf("start:sess-1:h", "start:sess-1:h"), bridge.events)
        scope.cancel()
    }

    @Test
    fun `a vault lock on a live session keeps the keep-alive`() = runTest {
        val bridge = RecordingBridge()
        val transport = ScriptedTransport(listOf(Result.success(FakeSshConnection(FakeShellChannel()))))
        val (controller, scope) = controllerWith(transport, keepAlive = bridge)
        controller.bindSessionId("sess-1")
        controller.connect(testTarget, SshAuth.Password("pw"))

        controller.clearReconnectCredentials() // lock: socket stays open by design

        assertIs<ConnectionUiState.Connected>(controller.uiState)
        assertEquals(listOf("start:sess-1:h"), bridge.events)
        scope.cancel()
    }

    @Test
    fun `a drop after credentials were cleared reports the session ended`() = runTest {
        val bridge = RecordingBridge()
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(listOf(Result.success(FakeSshConnection(ch1))))
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3, keepAlive = bridge)
        controller.bindSessionId("sess-1")
        controller.connect(testTarget, SshAuth.Password("pw"))
        controller.clearReconnectCredentials() // locked before the drop

        ch1.close()
        advanceUntilIdle()

        assertEquals(listOf("start:sess-1:h", "end:sess-1"), bridge.events)
        scope.cancel()
    }

    @Test
    fun `a non-SSH drop reports the session ended`() = runTest {
        val bridge = RecordingBridge()
        val ch1 = FakeShellChannel()
        val transport = ScriptedTransport(listOf(Result.success(FakeSshConnection(ch1))))
        val (controller, scope) = controllerWith(transport, maxReconnectAttempts = 3, keepAlive = bridge)
        controller.bindSessionId("sess-1")
        controller.connect(
            SshTarget(host = "h", port = 23, username = "", connectionType = ConnectionType.TELNET),
            SshAuth.Password(""),
        )

        ch1.close() // Telnet/Serial have no reconnect: the drop is final
        advanceUntilIdle()

        assertEquals(listOf("start:sess-1:h", "end:sess-1"), bridge.events)
        scope.cancel()
    }

    @Test
    fun `a throwing onConnected action retracts the started session`() = runTest {
        val bridge = RecordingBridge()
        val transport = ScriptedTransport(listOf(Result.success(FakeSshConnection(FakeShellChannel()))))
        val (controller, scope) = controllerWith(transport, keepAlive = bridge)
        controller.bindSessionId("sess-1")

        controller.connect(testTarget, SshAuth.Password("pw")) { error("boom") }
        advanceUntilIdle()

        // "started" was announced when Connected was published; the failure must retract it.
        assertIs<ConnectionUiState.Error>(controller.uiState)
        assertEquals(listOf("start:sess-1:h", "end:sess-1"), bridge.events)
        scope.cancel()
    }

    @Test
    fun `the host label is sanitised before reaching the bridge`() = runTest {
        val bridge = RecordingBridge()
        val transport = ScriptedTransport(listOf(Result.success(FakeSshConnection(FakeShellChannel()))))
        val (controller, scope) = controllerWith(transport, keepAlive = bridge)
        controller.bindSessionId("sess-1")

        // A peer-authored host profile can carry bidi overrides that make the notification lie
        // about the target (issue #227 precedent) — the label must cross the untrusted-text filter.
        controller.connect(
            SshTarget(host = "prod\u202E.evil", port = 22, username = "u"),
            SshAuth.Password("pw"),
        )

        val started = bridge.events.single()
        assertTrue(started.startsWith("start:sess-1:"))
        assertFalse('\u202E' in started, "bidi override must not reach the notification label")
        scope.cancel()
    }
}
