package app.skerry.ui.vnc

import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.vnc.VncAuth
import app.skerry.shared.vnc.VncFramebuffer
import app.skerry.shared.vnc.VncPointerEvent
import app.skerry.shared.vnc.VncQuality
import app.skerry.shared.vnc.VncSession
import app.skerry.shared.vnc.VncTransport
import app.skerry.shared.vnc.VncUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A fake session with a controllable update flow and captured input. */
open class FakeVncSession(
    override val serverName: String = "fake-desktop",
    override val framebuffer: VncFramebuffer = VncFramebuffer(2, 1),
    override val updates: Flow<VncUpdate> = MutableSharedFlow(),
) : VncSession {
    val pointers = mutableListOf<VncPointerEvent>()
    val keys = mutableListOf<Pair<Long, Boolean>>()
    val cutText = mutableListOf<String>()
    val localCursor = mutableListOf<Boolean>()
    val fullUpdates = mutableListOf<Boolean>()
    val desktopSizes = mutableListOf<Pair<Int, Int>>()
    var closed = false

    override suspend fun sendPointer(event: VncPointerEvent) { pointers += event }
    override suspend fun sendKey(keySym: Long, down: Boolean) { keys += keySym to down }
    override suspend fun sendClientCutText(text: String) { cutText += text }
    override suspend fun requestUpdate(incremental: Boolean) { fullUpdates += incremental }
    override suspend fun setQuality(quality: VncQuality) {}
    override suspend fun setDesktopSize(width: Int, height: Int) { desktopSizes += width to height }
    override suspend fun setLocalCursor(enabled: Boolean) { localCursor += enabled }
    override suspend fun close() { closed = true }
}

private fun target() = SshTarget(host = "10.0.0.9", port = 5900, username = "")

/** VncTransport is a plain interface (no SAM), so wrap the lambda in an object for tests. */
private fun transportOf(block: suspend (SshTarget, VncAuth) -> VncSession) = object : VncTransport {
    override suspend fun connect(target: SshTarget, auth: VncAuth): VncSession = block(target, auth)
}

class VncSessionControllerTest {

    @Test
    fun connect_transitions_to_connected_with_a_screen() = runTest {
        val session = FakeVncSession()
        val transport = transportOf { _, _ -> session }
        val controller = VncSessionController(transport, this, newSessionScope = { CoroutineScope(StandardTestDispatcher(testScheduler)) })

        assertTrue(controller.uiState is VncUiState.Connecting)
        controller.connect(target(), VncAuth.None)
        advanceUntilIdle()

        val state = controller.uiState
        assertTrue(state is VncUiState.Connected)
        assertEquals("fake-desktop", state.screen.serverName)

        controller.disconnect()
    }

    @Test
    fun connect_failure_becomes_error() = runTest {
        val transport = transportOf { _, _ -> throw IllegalStateException("refused") }
        val controller = VncSessionController(transport, this, newSessionScope = { CoroutineScope(StandardTestDispatcher(testScheduler)) })

        controller.connect(target(), VncAuth.None)
        advanceUntilIdle()

        val state = controller.uiState
        assertTrue(state is VncUiState.Error)
        assertEquals(VncFailure.Other, state.failure)
        assertEquals("refused", state.detail)
    }

    @Test
    fun disconnect_closes_the_session() = runTest {
        val session = FakeVncSession()
        val transport = transportOf { _, _ -> session }
        val controller = VncSessionController(transport, this, newSessionScope = { CoroutineScope(StandardTestDispatcher(testScheduler)) })

        controller.connect(target(), VncAuth.None)
        advanceUntilIdle()
        controller.disconnect()
        advanceUntilIdle()

        assertTrue(session.closed)
        assertTrue(controller.uiState is VncUiState.Connecting)
    }
}
