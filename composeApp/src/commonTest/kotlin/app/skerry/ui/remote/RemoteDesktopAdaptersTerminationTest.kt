package app.skerry.ui.remote

import app.skerry.shared.graphics.RemoteDesktopSession
import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.shared.graphics.RemoteFramebuffer
import app.skerry.shared.rdp.RdpMouseButton
import app.skerry.shared.rdp.RdpRect
import app.skerry.shared.rdp.RdpRemoteDesktop
import app.skerry.shared.rdp.RdpSession
import app.skerry.shared.rdp.RdpUpdate
import app.skerry.shared.rdp.RdpWheelAxis
import app.skerry.shared.vnc.VncPointerEvent
import app.skerry.shared.vnc.VncQuality
import app.skerry.shared.vnc.VncRemoteDesktop
import app.skerry.shared.vnc.VncSession
import app.skerry.shared.vnc.VncUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Both production protocol adapters must carry termination into the shared controller. */
class RemoteDesktopAdaptersTerminationTest {
    @Test
    fun vnc_stream_completion_releases_the_session() = runTest {
        val end = CompletableDeferred<Unit>()
        val session = LifecycleVnc(flow { end.await() })
        val controller = RemoteDesktopController(this) { CoroutineScope(StandardTestDispatcher(testScheduler)) }
        controller.connect { VncRemoteDesktop(session) }
        advanceUntilIdle()
        assertIs<RemoteDesktopUiState.Connected>(controller.uiState)
        end.complete(Unit)
        advanceUntilIdle()
        assertIs<RemoteDesktopUiState.Disconnected>(controller.uiState)
        assertTrue(session.closed)
    }

    @Test
    fun rdp_stream_completion_releases_the_session() = runTest {
        val end = CompletableDeferred<Unit>()
        val session = LifecycleRdp(flow { end.await() })
        val controller = RemoteDesktopController(this) { CoroutineScope(StandardTestDispatcher(testScheduler)) }
        controller.connect { RdpRemoteDesktop(session) }
        advanceUntilIdle()
        assertIs<RemoteDesktopUiState.Connected>(controller.uiState)
        end.complete(Unit)
        advanceUntilIdle()
        assertIs<RemoteDesktopUiState.Disconnected>(controller.uiState)
        assertTrue(session.closed)
    }

    @Test
    fun protocol_closures_preserve_the_known_reason_and_release_both_adapters() = runTest {
        val vnc = LifecycleVnc(flow { emit(VncUpdate.Closed(true)) })
        val rdp = LifecycleRdp(flow { emit(RdpUpdate.Closed(true, "the session ended because it was idle")) })
        for ((adapter, expectedReason) in listOf(
            VncRemoteDesktop(vnc) to "",
            RdpRemoteDesktop(rdp) to "the session ended because it was idle",
        )) {
            val controller = RemoteDesktopController(this) { CoroutineScope(StandardTestDispatcher(testScheduler)) }
            controller.connect { adapter }
            advanceUntilIdle()
            val state = assertIs<RemoteDesktopUiState.Disconnected>(controller.uiState)
            assertTrue(state.cleanExit)
            assertEquals(expectedReason, state.reason)
        }
        assertTrue(vnc.closed)
        assertTrue(rdp.closed)
    }

    @Test
    fun read_failures_end_both_adapters_as_connection_loss() = runTest {
        val vnc = LifecycleVnc(flow { error("read failed") })
        val rdp = LifecycleRdp(flow { error("read failed") })
        for (adapter in listOf<RemoteDesktopSession>(VncRemoteDesktop(vnc), RdpRemoteDesktop(rdp))) {
            val controller = RemoteDesktopController(this) { CoroutineScope(StandardTestDispatcher(testScheduler)) }
            controller.connect { adapter }
            advanceUntilIdle()
            val state = assertIs<RemoteDesktopUiState.Disconnected>(controller.uiState)
            assertEquals(RemoteDesktopUpdate.Closed(false), state.screen.close.value)
        }
        assertTrue(vnc.closed)
        assertTrue(rdp.closed)
    }
}

private class LifecycleVnc(override val updates: Flow<VncUpdate>) : VncSession {
    override val serverName = "test-vnc"
    override val framebuffer = RemoteFramebuffer(2, 1)
    var closed = false
    override suspend fun sendPointer(event: VncPointerEvent) = Unit
    override suspend fun sendKey(keySym: Long, down: Boolean) = Unit
    override suspend fun sendClientCutText(text: String) = Unit
    override suspend fun requestUpdate(incremental: Boolean) = Unit
    override suspend fun setQuality(quality: VncQuality) = Unit
    override suspend fun setDesktopSize(width: Int, height: Int) = Unit
    override suspend fun setLocalCursor(enabled: Boolean) = Unit
    override suspend fun close() { closed = true }
}

private class LifecycleRdp(override val updates: Flow<RdpUpdate>) : RdpSession {
    override val connectedHost = "test-rdp"
    override val desktopWidth = 2
    override val desktopHeight = 1
    override val framebuffer = RemoteFramebuffer(2, 1)
    override val outputSuppressionSupported = true
    override val audioAvailable = true
    override val clipboardAvailable = true
    var closed = false
    override suspend fun sendKey(scancode: Int, down: Boolean, extended: Boolean, extended1: Boolean) = Unit
    override suspend fun sendUnicode(code: Int, down: Boolean) = Unit
    override suspend fun sendPointerMove(x: Int, y: Int) = Unit
    override suspend fun sendPointerButton(button: RdpMouseButton, down: Boolean, x: Int, y: Int) = Unit
    override suspend fun sendWheel(clicks: Int, axis: RdpWheelAxis, x: Int, y: Int) = Unit
    override suspend fun sendLockKeys(scroll: Boolean, num: Boolean, caps: Boolean) = Unit
    override suspend fun requestRefresh(rects: List<RdpRect>) = Unit
    override suspend fun setOutputVisible(visible: Boolean) = Unit
    override suspend fun setDesktopSize(width: Int, height: Int, scale: Float) = Unit
    override suspend fun sendClipboardText(text: String) = Unit
    override fun setAudioMuted(muted: Boolean) = Unit
    override suspend fun close() { closed = true }
}
