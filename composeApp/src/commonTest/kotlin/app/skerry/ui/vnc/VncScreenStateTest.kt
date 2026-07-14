package app.skerry.ui.vnc

import app.skerry.shared.vnc.VncFramebuffer
import app.skerry.shared.vnc.VncPointerEvent
import app.skerry.shared.vnc.VncRect
import app.skerry.shared.vnc.VncUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VncScreenStateTest {

    @Test
    fun region_update_bumps_the_frame_counter() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<VncUpdate>(extraBufferCapacity = 8)
        val session = FakeVncSession(framebuffer = VncFramebuffer(2, 1), updates = updates)
        val screen = VncScreenState(session, scope)

        assertEquals(0, screen.frame)
        updates.emit(VncUpdate.Region(listOf(VncRect(0, 0, 2, 1))))
        assertEquals(1, screen.frame)
        scope.cancel()
    }

    @Test
    fun resize_update_tracks_the_new_desktop_size() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<VncUpdate>(extraBufferCapacity = 8)
        val session = FakeVncSession(framebuffer = VncFramebuffer(2, 1), updates = updates)
        val screen = VncScreenState(session, scope)

        updates.emit(VncUpdate.Resize(800, 600))
        assertEquals(800, screen.desktopSize.width)
        assertEquals(600, screen.desktopSize.height)
        scope.cancel()
    }

    @Test
    fun close_update_marks_closed() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<VncUpdate>(extraBufferCapacity = 8)
        val session = FakeVncSession(updates = updates)
        val screen = VncScreenState(session, scope)

        updates.emit(VncUpdate.Closed(cleanExit = true))
        assertTrue(screen.closed)
        assertTrue(screen.cleanExit)
        scope.cancel()
    }

    @Test
    fun pointer_key_and_clipboard_are_forwarded() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeVncSession()
        val screen = VncScreenState(session, scope)

        screen.onPointer(5, 7, 0b001)
        screen.onKey(0xFF0DL, down = true)
        screen.onLocalClipboard("hello")

        assertEquals(VncPointerEvent(5, 7, 0b001), session.pointers.single())
        assertEquals(0xFF0DL to true, session.keys.single())
        assertEquals("hello", session.cutText.single())
        scope.cancel()
    }

    @Test
    fun server_clipboard_reaches_the_callback() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<VncUpdate>(extraBufferCapacity = 8)
        val session = FakeVncSession(updates = updates)
        val received = mutableListOf<String>()
        VncScreenState(session, scope, onClipboard = { received += it })

        updates.emit(VncUpdate.ClipboardText("copied"))
        assertEquals("copied", received.single())
        scope.cancel()
    }
}
