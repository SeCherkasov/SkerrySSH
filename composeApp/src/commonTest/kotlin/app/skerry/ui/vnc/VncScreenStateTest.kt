package app.skerry.ui.vnc

import app.skerry.shared.vnc.VncFramebuffer
import app.skerry.shared.vnc.VncPointerEvent
import app.skerry.shared.vnc.VncQuality
import app.skerry.shared.vnc.VncRect
import app.skerry.shared.vnc.VncUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun cursor_shape_becomes_a_sprite_and_an_empty_one_clears_it() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val updates = MutableSharedFlow<VncUpdate>(extraBufferCapacity = 8)
        val screen = VncScreenState(FakeVncSession(updates = updates), scope)

        assertNull(screen.cursor)
        updates.emit(VncUpdate.CursorShape(IntArray(4) { 0xFFFFFFFF.toInt() }, 2, 2, 1, 1))
        assertEquals(1, screen.cursor?.hotspotX)

        // The server hides the cursor by sending a 0x0 shape — the sprite has to go with it.
        updates.emit(VncUpdate.CursorShape(IntArray(0), 0, 0, 0, 0))
        assertNull(screen.cursor)
        scope.cancel()
    }

    @Test
    fun view_only_hands_the_cursor_back_to_the_server_and_repaints() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeVncSession()
        val screen = VncScreenState(session, scope)

        screen.toggleViewOnly()
        // Nothing drives our pointer now, so the server must paint the cursor where it really is; the
        // full update is what clears the sprite-era framebuffer of its cursor-shaped hole.
        assertEquals(listOf(false), session.localCursor)
        assertEquals(listOf(false), session.fullUpdates)

        screen.toggleViewOnly()
        assertEquals(listOf(false, true), session.localCursor)
        // ...and back, so the cursor the server just painted doesn't stay burnt in next to the sprite.
        assertEquals(listOf(false, false), session.fullUpdates)
        scope.cancel()
    }

    @Test
    fun a_write_to_a_dead_session_does_not_escape_the_launch() = runTest {
        // Every input write races the read loop, so the socket can already be gone. An exception out
        // of a bare launch reaches the default handler and kills the process on Android — the dropped
        // session has to surface through `closed` instead.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = object : FakeVncSession() {
            override suspend fun sendPointer(event: VncPointerEvent): Unit = throw IllegalStateException("Socket closed")
            override suspend fun setQuality(quality: VncQuality): Unit = throw IllegalStateException("Socket closed")
            override suspend fun setLocalCursor(enabled: Boolean): Unit = throw IllegalStateException("Socket closed")
        }
        val screen = VncScreenState(session, scope)

        screen.onPointer(1, 1, 0)
        screen.applyQuality(VncQuality.High)
        screen.toggleViewOnly()

        // Reaching here at all is the assertion: an escaping exception would have failed the test.
        assertTrue(screen.viewOnly)
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
